package com.quickjs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 仅合成JS对象，不加载用户脚本/设置，也不访问网络。 */
@RunWith(AndroidJUnit4.class)
public class QuickJsLifecycleInstrumentedTest {
    @Test(timeout = 5000)
    public void queuedErrorsReachTheCallerWithoutKillingTheWorker() {
        try (QuickJS runtime = QuickJS.createRuntimeWithEventQueue(); JSContext context = runtime.createContext()) {
            Error expected = new OutOfMemoryError("synthetic callback failure");
            try {
                runtime.quickJSNative.postVoid(() -> { throw expected; });
                fail("queued Error must reach its caller");
            } catch (Error actual) {
                assertSame(expected, actual);
            }
            assertEquals(42, context.executeIntegerScript("6 * 7", "after-error.js"));
            runtime.postEventQueue(() -> { throw expected; });
            assertEquals(42, context.executeIntegerScript("6 * 7", "after-async-error.js"));
        }
    }

    @Test(timeout = 10000)
    public void interruptedCallerWaitsForNativeCompletionAndRetainsItsInterrupt() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try (QuickJS runtime = QuickJS.createRuntimeWithEventQueue(); JSContext context = runtime.createContext()) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(42, context.executeIntegerScript("6 * 7", "interrupted-caller.js"));
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                Thread.interrupted();
            }
        });
        caller.start();
        caller.join(5000);
        assertFalse("native result must not leave its caller waiting forever", caller.isAlive());
        assertNull(failure.get());
    }

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

    @Test(timeout = 30000)
    public void concurrentRuntimeCloseReleasesOnlyOwnedContextsOutsideTheRegistryLock() throws Exception {
        int workers = 4;
        CyclicBarrier closing = new CyclicBarrier(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> results = new ArrayList<>();
        try (QuickJS survivor = QuickJS.createRuntimeWithEventQueue(); JSContext survivorContext = survivor.createContext()) {
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    for (int round = 0; round < 64; round++) {
                        try (QuickJS runtime = QuickJS.createRuntime()) {
                            List<JSContext> contexts = new ArrayList<>();
                            for (int index = 0; index < 4; index++) {
                                JSContext context = runtime.createContext();
                                context.addPlugin(new Plugin() {
                                    @Override protected void setup(JSContext context) { }
                                    @Override protected void close(JSContext context) {
                                        assertFalse("context cleanup must not hold the global registry lock",
                                                Thread.holdsLock(QuickJS.sContextMap));
                                    }
                                });
                                contexts.add(context);
                            }
                            closing.await(5, TimeUnit.SECONDS);
                            runtime.close();
                            assertTrue("every runtime must finish closing", runtime.isReleased());
                            for (JSContext context : contexts) {
                                assertTrue("runtime close must release every owned context", context.released);
                                assertNotSame("released contexts must leave the registry", context,
                                        QuickJS.sContextMap.get(context.getContextPtr()));
                            }
                            // 本轮全部关闭完成后才重新分配native指针，隔离并发关闭这一条链路。
                            closing.await(5, TimeUnit.SECONDS);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
            assertFalse(survivor.isReleased());
            assertFalse(survivorContext.released);
            assertEquals(42, survivorContext.executeIntegerScript("6 * 7", "after-concurrent-close.js"));
        } finally {
            executor.shutdownNow();
            assertTrue("runtime close workers must terminate", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
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
