package com.quickjs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 仅合成JS对象，不加载用户脚本/设置，也不访问网络。 */
@RunWith(AndroidJUnit4.class)
public class QuickJsLifecycleInstrumentedTest {
    private int tracked(JSContext context) throws Exception {
        java.lang.reflect.Field field = JSContext.class.getDeclaredField("refs");
        field.setAccessible(true);
        Object refs = field.get(context);
        return refs instanceof Map ? ((Map<?, ?>) refs).size() : ((Collection<?>) refs).size();
    }

    @Test
    public void heldNativeHandlesMustNotDisappearWhenJavaGcRuns() throws Exception {
        QuickJS runtime = QuickJS.createRuntimeWithEventQueue();
        JSContext context = runtime.createContext();
        List<JSValue> held = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) held.add(context.executeObjectScript("({value: 42})", "held-object.js"));
            for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(50); }
            assertTrue("native handle registry lost strongly held Java objects: " + tracked(context), tracked(context) >= held.size());
        } finally {
            // 旧版先显式释放仍持有的handle，避免测试断言失败掩盖成进程abort。
            for (JSValue value : held) value.close();
            context.close();
            runtime.close();
        }
    }
    @Test
    public void closingContextReleasesEvenLiveWrappersBeforeRuntime() {
        QuickJS runtime = QuickJS.createRuntimeWithEventQueue();
        JSContext context = runtime.createContext();
        List<JSValue> held = new ArrayList<>();
        for (int i = 0; i < 128; i++) held.add(context.executeObjectScript("({promise: Promise.resolve(42)})", "close-live.js"));
        System.gc();
        context.close();
        for (JSValue value : held) {
            assertTrue(value.released);
            value.close(); // 重复释放不得再触碰已关闭的context。
        }
        runtime.close();
        runtime.close();
    }

    @Test
    public void discardedWrappersAreReclaimedWithoutKeepingEverythingUntilShutdown() throws Exception {
        try (QuickJS runtime = QuickJS.createRuntimeWithEventQueue(); JSContext context = runtime.createContext()) {
            for (int i = 0; i < 1024; i++) context.executeObjectScript("({value: 'temporary'})", "discard.js");
            for (int i = 0; i < 4; i++) {
                System.gc(); Thread.sleep(50);
                context.executeIntegerScript("1", "drain.js");
            }
            assertTrue("must not retain every wrapper for the whole source lifetime", tracked(context) < 16);
        }
    }

    @Test
    public void nativeObjectCoercionToJavaStringReleasesTheTemporaryValue() {
        for (int round = 0; round < 5; round++) {
            try (QuickJS runtime = QuickJS.createRuntimeWithEventQueue(); JSContext context = runtime.createContext()) {
                for (int i = 0; i < 100; i++) {
                    assertEquals("converted", context.executeStringScript("({toString(){ return 'converted' }})", "coerce.js"));
                    context.executeVoidScript("({ignored: Promise.resolve(1)})", "void.js");
                }
            }
        }
    }

    @Test
    public void globalJavaCallbacksAndExceptionsCanBeClosedAfterGc() throws Exception {
        try (QuickJS runtime = QuickJS.createRuntimeWithEventQueue(); JSContext context = runtime.createContext()) {
            context.registerJavaMethod((JavaCallback) (receiver, args) -> args.getString(0), "echo");
            for (int i = 0; i < 100; i++) {
                assertEquals("ok", context.executeStringScript("echo('ok')", "callback.js"));
                try {
                    context.executeVoidScript("throw new Error('expected')", "error.js");
                    fail("exception expected");
                } catch (QuickJSException expected) { assertTrue(expected.getMessage().contains("expected")); }
                if (i % 20 == 0) { System.gc(); Thread.sleep(20); }
            }
        }
    }

}
