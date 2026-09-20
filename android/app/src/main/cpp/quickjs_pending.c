// QuickJS 的 Promise 任务泵。taoweiji 绑定没有暴露 JS_ExecutePendingJob，
// 这里通过 dlopen 已加载的 libquickjs.so 调用符号，不复制引擎、不绑定版本。
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>

typedef struct JSRuntime JSRuntime;
typedef struct JSContext JSContext;
typedef int (*JS_ExecutePendingJobFn)(JSRuntime *, JSContext **);

static JS_ExecutePendingJobFn pending_fn = NULL;

JNIEXPORT jint JNICALL
Java_com_leyu_melora_playback_lx_QuickJsPending_executePendingNative(
        JNIEnv *env, jobject thiz, jlong runtime_ptr, jlong context_ptr) {
    (void) env;
    (void) thiz;
    if (pending_fn == NULL) {
        void *handle = dlopen("libquickjs.so", RTLD_NOW | RTLD_GLOBAL);
        if (handle != NULL) {
            pending_fn = (JS_ExecutePendingJobFn) dlsym(handle, "JS_ExecutePendingJob");
        }
    }
    if (pending_fn == NULL || runtime_ptr == 0 || context_ptr == 0) return 0;
    jint executed = 0;
    JSContext *context = (JSContext *) (intptr_t) context_ptr;
    while (pending_fn((JSRuntime *) (intptr_t) runtime_ptr, &context) > 0) {
        if (++executed >= 1000) break;
    }
    return executed;
}
