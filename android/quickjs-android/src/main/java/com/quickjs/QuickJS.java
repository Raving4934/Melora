package com.quickjs;

import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.Closeable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class QuickJS implements Closeable {
    volatile boolean released;
    final long runtimePtr;
    final EventQueue quickJSNative;
    static final Map<Long, JSContext> sContextMap = Collections.synchronizedMap(new HashMap<>());

    private QuickJS(long runtimePtr, HandlerThread handlerThread) {
        this.runtimePtr = runtimePtr;
        this.quickJSNative = new EventQueue(this, handlerThread);
    }

    public static QuickJS createRuntime() {
        return new QuickJS(QuickJSNativeImpl._createRuntime(), null);
    }

    public void postEventQueue(Runnable event) {
        quickJSNative.postVoid(event, false);
    }

    private static int sId = 0;

    public static QuickJS createRuntimeWithEventQueue() {
        RuntimeThread thread = new RuntimeThread("QuickJS-" + (sId++));
        thread.start();
        return awaitRuntime(thread.creation, thread);
    }

    /** 初始化直接在已准备好的Looper上执行，不在调用线程等待HandlerThread.getLooper。 */
    private static final class RuntimeThread extends HandlerThread {
        final FutureTask<QuickJS> creation = new FutureTask<>(() -> {
            QuickJSNativeImpl nativeImpl = new QuickJSNativeImpl();
            long runtimePtr = QuickJSNativeImpl._createRuntime();
            try {
                return new QuickJS(runtimePtr, this);
            } catch (RuntimeException | Error error) {
                try {
                    nativeImpl._releaseRuntime(runtimePtr);
                } catch (Throwable cleanupError) {
                    if (cleanupError != error) error.addSuppressed(cleanupError);
                }
                throw error;
            }
        });

        RuntimeThread(String name) { super(name); }
        @Override protected void onLooperPrepared() { creation.run(); }
    }

    private static QuickJS awaitRuntime(FutureTask<QuickJS> creation, HandlerThread handlerThread) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return creation.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    stopAndJoin(handlerThread);
                    throw propagate(e.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void stopAndJoin(HandlerThread handlerThread) {
        handlerThread.quitSafely();
        boolean interrupted = false;
        while (handlerThread.isAlive()) {
            try {
                handlerThread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException("Unable to initialize QuickJS runtime", error);
    }

    public JSContext createContext() {
        long contextPtr = getNative()._createContext(runtimePtr);
        if (contextPtr == 0) throw new IllegalStateException("Unable to create QuickJS context");
        try {
            return new JSContext(this, contextPtr);
        } catch (RuntimeException | Error error) {
            try {
                getNative()._releaseContext(contextPtr);
            } catch (Throwable cleanupError) {
                if (cleanupError != error) error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }


    public void close() {
        quickJSNative.postVoid(() -> {
            if (QuickJS.this.released) {
                return;
            }
            JSContext[] values = new JSContext[sContextMap.size()];
            sContextMap.values().toArray(values);
            for (JSContext context : values) {
                if (context.getQuickJS() == QuickJS.this) {
                    context.close();
                }
            }
            getNative()._releaseRuntime(runtimePtr);
            QuickJS.this.released = true;
            quickJSNative.interrupt();
        });
    }

    public void checkReleased() {
        if (this.isReleased()) {
            throw new Error("Runtime disposed error");
        }
    }

    public QuickJSNative getNative() {
        return quickJSNative;
    }

    static class MethodDescriptor {
        public JavaVoidCallback voidCallback;
        public JavaCallback callback;
    }

    @Keep
    static Object callJavaCallback(long context_ptr, int javaCallerId, JSValue objectHandle, JSArray argsHandle, boolean void_method) {
        JSContext context = sContextMap.get(context_ptr);
        if (context == null) {
            return null;
        }
        MethodDescriptor methodDescriptor = context.functionRegistry.get(javaCallerId);
        if (methodDescriptor == null) return null;
        JSObject receiver = null;
        if (objectHandle instanceof JSObject) {
            receiver = (JSObject) objectHandle;
        }
        if (void_method) {
            methodDescriptor.voidCallback.invoke(receiver, argsHandle);
            return null;
        }
        return methodDescriptor.callback.invoke(receiver, argsHandle);
    }

    @Keep
    static JSValue createJSValue(long contextPtr, int type, long tag, int u_int32, double u_float64, long u_ptr) {
        JSContext context = sContextMap.get(contextPtr);
        switch (type) {
            case JSValue.TYPE_JS_FUNCTION:
                return new JSFunction(context, tag, u_int32, u_float64, u_ptr);
            case JSValue.TYPE_JS_ARRAY:
                return new JSArray(context, tag, u_int32, u_float64, u_ptr);
            case JSValue.TYPE_JS_OBJECT:
                return new JSObject(context, tag, u_int32, u_float64, u_ptr);
            case JSValue.TYPE_UNDEFINED:
                return new JSObject.Undefined(context, tag, u_int32, u_float64, u_ptr);
            default:
                return new JSValue(context, tag, u_int32, u_float64, u_ptr);
        }
    }

    @Keep
    static String getModuleScript(long contextPtr, String moduleName) {
        JSContext context = sContextMap.get(contextPtr);
        if (context == null) {
            return null;
        }
        if (context instanceof Module) {
            Module module = (Module) context;
            return module.getModuleScript(moduleName);
        }
        return null;
    }

    @Keep
    static String convertModuleName(long contextPtr, String moduleBaseName, String moduleName) {
        JSContext context = sContextMap.get(contextPtr);
        if (context == null) {
            return null;
        }
        if (context instanceof Module) {
            Module module = (Module) context;
            String result = module.convertModuleName(moduleBaseName, moduleName);
            return result;
        }
        return null;
    }


    static Object executeFunction(JSContext context, JSValue objectHandle, String name, Object[] parameters) {
        JSArray args = new JSArray(context);
        if (parameters != null) {
            for (Object item : parameters) {
                if (item instanceof Integer) {
                    args.push((int) item);
                } else if (item instanceof Double) {
                    args.push((double) item);
                } else if (item instanceof Boolean) {
                    args.push((boolean) item);
                } else if (item instanceof String) {
                    args.push((String) item);
                } else if (item instanceof JSValue) {
                    args.push((JSValue) item);
                } else {
                    args.push((JSValue) null);
                }
            }
        }
        return context.getNative()._executeFunction(context.getContextPtr(), JSValue.TYPE_UNKNOWN, objectHandle, name, args);
    }

    static void checkException(JSContext context) {
        String[] result = context.getNative()._getException(context.getContextPtr());
        if (result == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(result[1]).append('\n');
        for (int i = 2; i < result.length; i++) {
            message.append(result[i]);
        }
        throw new QuickJSException(result[0], message.toString());
    }


    public boolean isReleased() {
        return this.released;
    }


    /* JS_Eval() flags */
    static int JS_EVAL_TYPE_GLOBAL = (0); /* global code (default) */
    static int JS_EVAL_TYPE_MODULE = (1); /* module code */
    static int JS_EVAL_TYPE_MASK = (3);
    static int JS_EVAL_FLAG_STRICT = (1 << 3); /* force 'strict' mode */
    static int JS_EVAL_FLAG_STRIP = (1 << 4); /* force 'strip' mode */
    /* compile but do not run. The result is an object with a
       JS_TAG_FUNCTION_BYTECODE or JS_TAG_MODULE tag. It can be executed
       with JS_EvalFunction(). */
    static int JS_EVAL_FLAG_COMPILE_ONLY = (1 << 5);
    /* don't include the stack frames before this eval in the Error() backtraces */
    static int JS_EVAL_FLAG_BACKTRACE_BARRIER = (1 << 6);


    static {
        System.loadLibrary("quickjs");
        System.loadLibrary("quickjs-android");
    }
}
