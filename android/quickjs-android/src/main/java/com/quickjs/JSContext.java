package com.quickjs;

import java.io.Closeable;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JSContext extends JSObject implements Closeable {
    final QuickJS quickJS;
    final long contextPtr;
    final Set<Plugin> plugins = Collections.synchronizedSet(new HashSet<>());
    // Native所有权记录必须一直保留到释放；仅弱引用Java包装对象，不弱引用临时Integer键。
    final Set<ValueReference> refs = Collections.synchronizedSet(new HashSet<>());
    private final ReferenceQueue<JSValue> collectedReferences = new ReferenceQueue<>();

    static final class ValueReference extends WeakReference<JSValue> {
        final long tag, pointer;
        final int integer;
        final double decimal;
        ValueReference(JSValue value, ReferenceQueue<JSValue> queue) {
            super(value, queue);
            tag = value.tag;
            pointer = value.u_ptr;
            integer = value.u_int32;
            decimal = value.u_float64;
        }
    }
    final Map<Integer, QuickJS.MethodDescriptor> functionRegistry = Collections.synchronizedMap(new HashMap<>());

    JSContext(QuickJS quickJS, long contextPtr) {
        super(null, quickJS.getNative()._getGlobalObject(contextPtr));
        this.quickJS = quickJS;
        this.contextPtr = contextPtr;
        this.context = this;
        QuickJS.sContextMap.put(contextPtr, this);
    }

    long getContextPtr() {
        return this.contextPtr;
    }

    void addObjRef(JSValue value) {
        if (value != this) {
            value.reference = new ValueReference(value, collectedReferences);
            refs.add(value.reference);
        }
    }

    void releaseObjRef(JSValue value) {
        if (value == this) {
            getNative()._releasePtr(contextPtr, tag, u_int32, u_float64, u_ptr);
            return;
        }
        ValueReference ref = value.reference;
        postEventQueue(() -> releaseReference(ref));
    }

    // 显式释放、GC、context销毁共用同一份所有权记录；只在runtime线程执行一次native free。
    private void releaseReference(ValueReference ref) {
        if (ref == null || !refs.remove(ref)) return;
        JSValue value = ref.get();
        if (value != null) value.released = true;
        getNative()._releasePtr(contextPtr, ref.tag, ref.integer, ref.decimal, ref.pointer);
        ref.clear();
    }

    private void drainCollectedReferences() {
        ValueReference first = (ValueReference) collectedReferences.poll();
        if (first == null) return;
        postEventQueue(() -> {
            releaseReference(first);
            ValueReference next;
            while ((next = (ValueReference) collectedReferences.poll()) != null) releaseReference(next);
        });
    }

    // 包装对象类型转换只是转移所有权，不减少native引用计数。
    void removeObjRef(JSValue value) {
        ValueReference ref = value.reference;
        if (ref != null) { refs.remove(ref); ref.clear(); }
        value.reference = null;
    }

    @Override
    public void close() {
        quickJS.quickJSNative.postVoid(() -> {
            if (released) {
                return;
            }
            for (Plugin plugin : plugins) {
                plugin.close(JSContext.this);
            }
            plugins.clear();
            functionRegistry.clear();
            ValueReference[] values;
            synchronized (refs) { values = refs.toArray(new ValueReference[0]); }
            for (ValueReference ref : values) releaseReference(ref);
            JSContext.super.close();
            getNative()._releaseContext(contextPtr);
            QuickJS.sContextMap.remove(getContextPtr());
        });
    }

    protected Object executeScript(TYPE expectedType, String source, String fileName) throws QuickJSScriptException {
        checkReleased();
        Object object = getNative()._executeScript(this.getContextPtr(), expectedType.value, source, fileName, QuickJS.JS_EVAL_TYPE_GLOBAL);
        QuickJS.checkException(context);
        return object;
    }

    /**
     * @return Integer/Double/Boolean/String/JSObject/JSArray/JSFunction
     */
    public Object executeScript(String source, String fileName) throws QuickJSScriptException {
        return executeScript(JSValue.TYPE.UNKNOWN, source, fileName);
    }

    public Object executeScript(String source, String fileName, int evalType) throws QuickJSScriptException {
        Object object = getNative()._executeScript(this.getContextPtr(), JSValue.TYPE.UNKNOWN.value, source, fileName, evalType);
        QuickJS.checkException(context);
        return object;
    }

    public Object executeModuleScript(String source, String fileName, int evalType) throws QuickJSScriptException {
        Object object = getNative()._executeScript(this.getContextPtr(), JSValue.TYPE.UNKNOWN.value, source, fileName, QuickJS.JS_EVAL_TYPE_MODULE);
        QuickJS.checkException(context);
        return object;
    }


    public int executeIntegerScript(String source, String fileName) throws QuickJSScriptException {
        return (int) executeScript(JSValue.TYPE.INTEGER, source, fileName);
    }

    public double executeDoubleScript(String source, String fileName) throws QuickJSScriptException {
        return (double) executeScript(JSValue.TYPE.DOUBLE, source, fileName);
    }

    public boolean executeBooleanScript(String source, String fileName) throws QuickJSScriptException {
        return (boolean) executeScript(JSValue.TYPE.BOOLEAN, source, fileName);
    }

    public String executeStringScript(String source, String fileName) throws QuickJSScriptException {
        return (String) executeScript(JSValue.TYPE.STRING, source, fileName);
    }

    public void executeVoidScript(String source, String fileName) throws QuickJSScriptException {
        Object result = executeScript(JSValue.TYPE.NULL, source, fileName);
        if (result instanceof JSValue) ((JSValue) result).close();
    }

    public JSArray executeArrayScript(String source, String fileName) throws QuickJSScriptException {
        return (JSArray) executeScript(JSValue.TYPE.JS_ARRAY, source, fileName);
    }

    public JSObject executeObjectScript(String source, String fileName) throws QuickJSScriptException {
        return (JSObject) executeScript(JSValue.TYPE.JS_OBJECT, source, fileName);
    }

    public boolean isReleased() {
        if (getQuickJS().isReleased()) {
            return true;
        }
        return this.released;
    }

    void _registerCallback(JavaCallback callback, JSFunction functionHandle) {
        QuickJS.MethodDescriptor methodDescriptor = new QuickJS.MethodDescriptor();
        methodDescriptor.callback = callback;
        functionRegistry.put(callback.hashCode(), methodDescriptor);
    }

    void _registerCallback(JavaVoidCallback callback, JSFunction functionHandle) {
        QuickJS.MethodDescriptor methodDescriptor = new QuickJS.MethodDescriptor();
        methodDescriptor.voidCallback = callback;
        functionRegistry.put(callback.hashCode(), methodDescriptor);
    }

    void checkRuntime(JSValue value) {
        if (value != null && !value.isUndefined()) {
            if (value.context == null) {
                throw new Error("Invalid target runtime");
            }
            QuickJS quickJS = value.context.quickJS;
            if (quickJS == null || quickJS.isReleased() || quickJS != this.quickJS) {
                throw new Error("Invalid target runtime");
            }
        }
    }

    public void addPlugin(Plugin plugin) {
        checkReleased();
        if (plugins.contains(plugin)) {
            return;
        }
        plugin.setup(context);
        this.plugins.add(plugin);
    }

    void checkReleased() {
        if (this.isReleased()) throw new Error("Context disposed error");
        drainCollectedReferences();
    }

    public QuickJSNative getNative() {
        return quickJS.getNative();
    }

    public QuickJS getQuickJS() {
        return quickJS;
    }
}
