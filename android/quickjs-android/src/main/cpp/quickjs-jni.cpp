#include <jni.h>
#include <quickjs/quickjs.h>
#include <atomic>
#include <climits>
#include <cstdint>
#include <new>
#include <time.h>

#if INTPTR_MAX >= INT64_MAX
#define JS_PTR64
#define JS_PTR64_DEF(a) a
#else
#define JS_PTR64_DEF(a)
#endif

#ifndef JS_PTR64
#define JS_NAN_BOXING
#endif

const int TYPE_NULL = 0;
const int TYPE_UNKNOWN = 0;
const int TYPE_INTEGER = 1;
const int TYPE_INT_32_ARRAY = 1;
const int TYPE_DOUBLE = 2;
const int TYPE_FLOAT_64_ARRAY = 2;
const int TYPE_BOOLEAN = 3;
const int TYPE_STRING = 4;
const int TYPE_JS_ARRAY = 5;
const int TYPE_JS_OBJECT = 6;
const int TYPE_JS_FUNCTION = 7;
const int TYPE_JS_TYPED_ARRAY = 8;
const int TYPE_BYTE = 9;
const int TYPE_INT_8_ARRAY = 9;
const int TYPE_JS_ARRAY_BUFFER = 10;
const int TYPE_UNSIGNED_INT_8_ARRAY = 11;
const int TYPE_UNSIGNED_INT_8_CLAMPED_ARRAY = 12;
const int TYPE_INT_16_ARRAY = 13;
const int TYPE_UNSIGNED_INT_16_ARRAY = 14;
const int TYPE_UNSIGNED_INT_32_ARRAY = 15;
const int TYPE_FLOAT_32_ARRAY = 16;
const int TYPE_UNDEFINED = 99;

jclass integerCls = nullptr;
jclass longCls = nullptr;
jclass doubleCls = nullptr;
jclass booleanCls = nullptr;
jclass stringCls = nullptr;

jmethodID integerInitMethodID = nullptr;
jmethodID longInitMethodID = nullptr;
jmethodID doubleInitMethodID = nullptr;
jmethodID booleanInitMethodID = nullptr;

jmethodID intValueMethodID = nullptr;
jmethodID longValueMethodID = nullptr;
jmethodID doubleValueMethodID = nullptr;
jmethodID booleanValueMethodID = nullptr;
jmethodID throwableMessageMethodID = nullptr;


jclass quickJSCls = nullptr;
jmethodID callJavaCallbackMethodID = nullptr;
jmethodID createJSValueMethodID = nullptr;
jmethodID getModuleScriptMethodID = nullptr;
jmethodID convertModuleNameMethodID = nullptr;

jclass jsValueCls = nullptr;
jfieldID js_value_tag_id;
jfieldID js_value_u_int32_id;
jfieldID js_value_u_float64_id;
jfieldID js_value_u_ptr_id;

void initES6Module(JSRuntime *rt);

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value),
          chars_(value != nullptr ? env->GetStringUTFChars(value, nullptr) : nullptr) {}

    ~ScopedUtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }

    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;
    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

struct RuntimeInterruptState {
    // begin/interrupt/end/release are serialized by QuickJS.interruptLifecycleLock.
    uint64_t next_ticket = 0;
    std::atomic<uint64_t> active_ticket{0};
    std::atomic<uint64_t> cancelled_ticket{0};
    std::atomic<int64_t> deadline_nanos{0};
};

static uint64_t monotonicNanos() {
    struct timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return static_cast<uint64_t>(now.tv_sec) * 1000000000ULL +
           static_cast<uint64_t>(now.tv_nsec);
}

static int interruptQuickJS(JSRuntime *, void *opaque) {
    auto *state = static_cast<RuntimeInterruptState *>(opaque);
    if (state == nullptr) return 0;
    const uint64_t active = state->active_ticket.load(std::memory_order_acquire);
    if (active == 0) return 0;
    if (state->cancelled_ticket.load(std::memory_order_acquire) == active) return 1;
    const int64_t deadline = state->deadline_nanos.load(std::memory_order_relaxed);
    return deadline > 0 && monotonicNanos() >= static_cast<uint64_t>(deadline);
}

static RuntimeInterruptState *runtimeInterruptState(JSRuntime *runtime) {
    return runtime == nullptr ? nullptr :
        static_cast<RuntimeInterruptState *>(JS_GetRuntimeOpaque(runtime));
}

static void throwIllegalState(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message);
}

bool JS_Equals(JSValue v1, JSValue v2) {
#if defined(JS_NAN_BOXING)
    return v1 == v2;
#else
    return v1.tag == v2.tag && v1.u.uint64 == v2.u.uint64;
#endif
}

JSValue TO_JS_VALUE(JNIEnv *env, jobject object_handle) {
    jlong tag = env->GetLongField(object_handle, js_value_tag_id);
#if defined(JS_NAN_BOXING)
    return tag;
#else
    JSValue value;
    value.tag = tag;
    value.u.uint64 = static_cast<uint64_t>(env->GetIntField(object_handle, js_value_u_int32_id));
    value.u.float64 = env->GetDoubleField(object_handle, js_value_u_float64_id);
    value.u.ptr = (void *) env->GetLongField(object_handle, js_value_u_ptr_id);
    return value;
#endif
}

jobject TO_JAVA_OBJECT(JNIEnv *env, JSContext *ctx, JSValue value) {
    if (JS_IsException(value) || env->ExceptionCheck()) {
        JS_FreeValue(ctx, value);
        return nullptr;
    }
    int type = TYPE_UNKNOWN;
    if (JS_IsUndefined(value)) {
        type = TYPE_UNDEFINED;
    } else if (JS_IsArray(ctx, value)) {
        type = TYPE_JS_ARRAY;
    } else if (JS_IsFunction(ctx, value)) {
        type = TYPE_JS_FUNCTION;
    } else if (JS_IsObject(value)) {
        type = TYPE_JS_OBJECT;
    }
#if defined(JS_NAN_BOXING)
    jobject result = env->CallStaticObjectMethod(quickJSCls,
                                       createJSValueMethodID,
                                       (jlong) ctx,
                                       type,
                                       (jlong) value,
                                       0,
                                       0.0,
                                       (jlong) 0);
#else
    jobject result = env->CallStaticObjectMethod(quickJSCls,
                                       createJSValueMethodID,
                                       (jlong) ctx,
                                       type,
                                       value.tag,
                                       static_cast<jint>(value.u.uint64),
                                       (jdouble) value.u.float64,
                                       (jlong) value.u.ptr);
#endif
    if (result == nullptr) JS_FreeValue(ctx, value);
    return result;
}

JSValue JobjectToJSValue(JNIEnv *env, JSContext *ctx, jobject value) {
    if (value == nullptr) {
        return JS_NULL;
    } else if (env->IsInstanceOf(value, integerCls)) {
        return JS_NewInt32(ctx, env->CallIntMethod(value, intValueMethodID));
    } else if (env->IsInstanceOf(value, longCls)) {
        return JS_NewInt64(ctx, env->CallLongMethod(value, longValueMethodID));
    } else if (env->IsInstanceOf(value, doubleCls)) {
        return JS_NewFloat64(ctx, env->CallDoubleMethod(value, doubleValueMethodID));
    } else if (env->IsInstanceOf(value, booleanCls)) {
        return JS_NewBool(ctx, env->CallBooleanMethod(value, booleanValueMethodID));
    } else if (env->IsInstanceOf(value, stringCls)) {
        ScopedUtfChars text(env, (jstring) value);
        if (text.get() == nullptr) return JS_EXCEPTION;
        return JS_NewString(ctx, text.get());
    } else if (env->IsInstanceOf(value, jsValueCls)) {
        return JS_DupValue(ctx, TO_JS_VALUE(env, value));
    }
    return JS_UNDEFINED;
}

int GetObjectType(JSContext *ctx, JSValue result) {
    if (JS_IsArray(ctx, result)) {
        return TYPE_JS_ARRAY;
    } else if (JS_IsFunction(ctx, result)) {
        return TYPE_JS_FUNCTION;
    } else if (JS_IsObject(result)) {
        return TYPE_JS_OBJECT;
    } else if (JS_IsString(result)) {
        return TYPE_STRING;
    } else if (JS_IsBool(result)) {
        return TYPE_BOOLEAN;
    } else if (JS_IsBigInt(ctx, result)) {
        return TYPE_INTEGER;
    } else if (JS_IsNull(result)) {
        return TYPE_NULL;
    } else if (JS_IsUndefined(result)) {
        return TYPE_UNDEFINED;
    } else if (JS_IsNumber(result)) {
        int tag = JS_VALUE_GET_TAG(result);
        if (tag == JS_TAG_INT) {
            return TYPE_INTEGER;
        }
        return TYPE_DOUBLE;
    }
    return TYPE_UNKNOWN;
}

jobject To_JObject(JNIEnv *env, jlong context_ptr, int expected_type, JSValue result) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    if (JS_IsException(result) || env->ExceptionCheck()) {
        JS_FreeValue(ctx, result);
        return nullptr;
    }
    if (expected_type == TYPE_UNKNOWN) {
        expected_type = GetObjectType(ctx, result);
    }
    if (JS_IsUndefined(result)) {
        expected_type = TYPE_UNDEFINED;
    } else if (JS_IsNull(result)) {
        expected_type = TYPE_NULL;
    }
    jobject converted = nullptr;
    switch (expected_type) {
        case TYPE_INTEGER:
            converted = env->NewObject(integerCls, integerInitMethodID, JS_VALUE_GET_INT(result));
            break;
        case TYPE_DOUBLE: {
            double pres;
            if (JS_ToFloat64(ctx, &pres, result) < 0) break;
            converted = env->NewObject(doubleCls, doubleInitMethodID, pres);
            break;
        }
        case TYPE_BOOLEAN:
            converted = env->NewObject(booleanCls, booleanInitMethodID, JS_VALUE_GET_BOOL(result));
            break;
        case TYPE_STRING: {
            const char *text = JS_ToCString(ctx, result);
            if (text != nullptr) {
                converted = env->NewStringUTF(text);
                JS_FreeCString(ctx, text);
            }
            break;
        }
        case TYPE_JS_ARRAY:
        case TYPE_JS_OBJECT:
        case TYPE_JS_FUNCTION:
        case TYPE_UNDEFINED:
            // Java包装对象接管引用；不能在这里再free。
            return TO_JAVA_OBJECT(env, ctx, result);
    }
    // 转换为Java值后不再有native所有者，连同强制字符串化的对象一起释放。
    JS_FreeValue(ctx, result);
    return converted;
}

JavaVM *jvm;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    jvm = vm;
    JNIEnv *env;
    jint onLoad_err = -1;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return onLoad_err;
    }
    if (env == nullptr) {
        return onLoad_err;
    }

    integerCls = (jclass) env->NewGlobalRef((env)->FindClass("java/lang/Integer"));
    longCls = (jclass) env->NewGlobalRef((env)->FindClass("java/lang/Long"));
    doubleCls = (jclass) env->NewGlobalRef((env)->FindClass("java/lang/Double"));
    booleanCls = (jclass) env->NewGlobalRef((env)->FindClass("java/lang/Boolean"));
    stringCls = (jclass) env->NewGlobalRef((env)->FindClass("java/lang/String"));
    quickJSCls = (jclass) env->NewGlobalRef((env)->FindClass("com/quickjs/QuickJS"));

    integerInitMethodID = env->GetMethodID(integerCls, "<init>", "(I)V");
    longInitMethodID = env->GetMethodID(longCls, "<init>", "(J)V");
    doubleInitMethodID = env->GetMethodID(doubleCls, "<init>", "(D)V");
    booleanInitMethodID = env->GetMethodID(booleanCls, "<init>", "(Z)V");

    callJavaCallbackMethodID = env->GetStaticMethodID(quickJSCls, "callJavaCallback",
                                                      "(JILcom/quickjs/JSValue;Lcom/quickjs/JSArray;Z)Ljava/lang/Object;");

    createJSValueMethodID = env->GetStaticMethodID(quickJSCls, "createJSValue",
                                                   "(JIJIDJ)Lcom/quickjs/JSValue;");
    getModuleScriptMethodID = env->GetStaticMethodID(quickJSCls, "getModuleScript",
                                                     "(JLjava/lang/String;)Ljava/lang/String;");
    convertModuleNameMethodID = env->GetStaticMethodID(quickJSCls, "convertModuleName",
                                                       "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");

    intValueMethodID = env->GetMethodID(integerCls, "intValue", "()I");
    longValueMethodID = env->GetMethodID(longCls, "longValue", "()J");
    doubleValueMethodID = env->GetMethodID(doubleCls, "doubleValue", "()D");
    booleanValueMethodID = env->GetMethodID(booleanCls, "booleanValue", "()Z");
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass == nullptr) return onLoad_err;
    throwableMessageMethodID = env->GetMethodID(throwableClass, "getMessage", "()Ljava/lang/String;");
    env->DeleteLocalRef(throwableClass);
    if (throwableMessageMethodID == nullptr) return onLoad_err;


    jsValueCls = (jclass) env->NewGlobalRef((env)->FindClass("com/quickjs/JSValue"));
    js_value_tag_id = env->GetFieldID(jsValueCls, "tag", "J");
    js_value_u_int32_id = env->GetFieldID(jsValueCls, "u_int32", "I");
    js_value_u_float64_id = env->GetFieldID(jsValueCls, "u_float64", "D");
    js_value_u_ptr_id = env->GetFieldID(jsValueCls, "u_ptr", "J");
    return JNI_VERSION_1_6;
}

int GetArrayLength(JSContext *ctx, JSValue this_obj) {
    JSValue lenValue = JS_GetPropertyStr(ctx, this_obj, "length");
    uint64_t len;
    const int result = JS_ToIndex(ctx, &len, lenValue);
    JS_FreeValue(ctx, lenValue);
    if (result < 0) return -1;
    if (len > INT_MAX) {
        JS_ThrowRangeError(ctx, "QuickJS array is too large");
        return -1;
    }
    return static_cast<int>(len);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_quickjs_QuickJSNativeImpl__1createRuntime(JNIEnv *env, jclass clazz) {
    JSRuntime *runtime = JS_NewRuntime();
    if (runtime == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
        if (exceptionClass == nullptr) return 0;
        env->ThrowNew(exceptionClass, "Unable to allocate QuickJS runtime");
        return 0;
    }
    auto *interrupt_state = new (std::nothrow) RuntimeInterruptState();
    if (interrupt_state == nullptr) {
        JS_FreeRuntime(runtime);
        jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, "Unable to allocate QuickJS interrupt state");
        return 0;
    }
    JS_SetRuntimeOpaque(runtime, interrupt_state);
    JS_SetInterruptHandler(runtime, interruptQuickJS, interrupt_state);
    initES6Module(runtime);
    return reinterpret_cast<jlong>(runtime);
}
extern "C"
JNIEXPORT jlong JNICALL
Java_com_quickjs_QuickJSNativeImpl__1beginInterruptibleExecution(
        JNIEnv *env, jclass, jlong runtime_ptr, jlong deadline_nanos) {
    auto *runtime = reinterpret_cast<JSRuntime *>(runtime_ptr);
    auto *state = runtimeInterruptState(runtime);
    if (state == nullptr) {
        throwIllegalState(env, "QuickJS interrupt state is unavailable");
        return 0;
    }
    if (state->active_ticket.load(std::memory_order_relaxed) != 0) {
        throwIllegalState(env, "QuickJS runtime already has an active execution");
        return 0;
    }
    uint64_t ticket = ++state->next_ticket;
    if (ticket == 0) ticket = ++state->next_ticket;
    state->cancelled_ticket.store(0, std::memory_order_relaxed);
    state->deadline_nanos.store(static_cast<int64_t>(deadline_nanos), std::memory_order_relaxed);
    state->active_ticket.store(ticket, std::memory_order_release);
    return static_cast<jlong>(ticket);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1interruptExecution(
        JNIEnv *env, jclass, jlong runtime_ptr, jlong ticket_value) {
    auto *state = runtimeInterruptState(reinterpret_cast<JSRuntime *>(runtime_ptr));
    if (state == nullptr || ticket_value == 0) return;
    const uint64_t ticket = static_cast<uint64_t>(ticket_value);
    if (state->active_ticket.load(std::memory_order_relaxed) == ticket) {
        state->cancelled_ticket.store(ticket, std::memory_order_release);
    }
}
extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1endInterruptibleExecution(
        JNIEnv *env, jclass, jlong runtime_ptr, jlong ticket_value) {
    auto *state = runtimeInterruptState(reinterpret_cast<JSRuntime *>(runtime_ptr));
    if (state == nullptr || ticket_value == 0) return;
    const uint64_t ticket = static_cast<uint64_t>(ticket_value);
    if (state->active_ticket.load(std::memory_order_relaxed) == ticket) {
        state->cancelled_ticket.store(0, std::memory_order_relaxed);
        state->deadline_nanos.store(0, std::memory_order_relaxed);
        state->active_ticket.store(0, std::memory_order_release);
    }
}
extern "C"
JNIEXPORT jlong JNICALL
Java_com_quickjs_QuickJSNativeImpl__1createContext(JNIEnv *env, jclass clazz, jlong runtime_ptr) {
    auto *runtime = reinterpret_cast<JSRuntime *>(runtime_ptr);
    if (runtime == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        if (exceptionClass == nullptr) return 0;
        env->ThrowNew(exceptionClass, "QuickJS runtime is not available");
        return 0;
    }
    auto *ctx = JS_NewContext(runtime);
    if (ctx == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/OutOfMemoryError");
        if (exceptionClass == nullptr) return 0;
        env->ThrowNew(exceptionClass, "Unable to allocate QuickJS context");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1releaseRuntime(JNIEnv *env, jclass clazz, jlong runtime_ptr) {
    auto *runtime = reinterpret_cast<JSRuntime *>(runtime_ptr);
    if (runtime == nullptr) return;
    auto *interrupt_state = runtimeInterruptState(runtime);
    JS_SetInterruptHandler(runtime, nullptr, nullptr);
    JS_SetRuntimeOpaque(runtime, nullptr);
    JS_FreeRuntime(runtime);
    delete interrupt_state;
}extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1releaseContext(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JS_FreeContext(ctx);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_quickjs_QuickJSNativeImpl__1executePendingJobs(JNIEnv *env, jobject, jlong context_ptr) {
    auto *runtime = JS_GetRuntime(reinterpret_cast<JSContext *>(context_ptr));
    for (int executed = 0; executed < 1000; ++executed) {
        const int result = JS_ExecutePendingJob(runtime, nullptr);
        if (result <= 0) return result;
        if (env->ExceptionCheck()) return -1;
    }
    return JS_IsJobPending(runtime) ? 1 : 0;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1executeScript(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                   jint expected_type,
                                                   jstring source, jstring file_name,
                                                   jint eval_flags) {
    if (source == nullptr) {
        return nullptr;
    }
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    ScopedUtfChars source_text(env, source);
    if (source_text.get() == nullptr) return nullptr;
    ScopedUtfChars file_name_text(env, file_name);
    if (file_name != nullptr && file_name_text.get() == nullptr) return nullptr;
    JSValue val = JS_Eval(ctx, source_text.get(), (size_t) env->GetStringUTFLength(source),
                         file_name != nullptr ? file_name_text.get() : "", eval_flags);
    return To_JObject(env, context_ptr, expected_type, val);
}
extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1getGlobalObject(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue global_obj = JS_GetGlobalObject(ctx);
    return TO_JAVA_OBJECT(env, ctx, global_obj);
}


extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1newJobScope(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    return TO_JAVA_OBJECT(env, ctx, JS_NewJobScope(ctx));
}
extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1initNewJSObject(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue jsValue = JS_NewObject(ctx);
    return TO_JAVA_OBJECT(env, ctx, jsValue);
}
extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1initNewJSArray(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue jsValue = JS_NewArray(ctx);
    return TO_JAVA_OBJECT(env, ctx, jsValue);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1releasePtr(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                jlong tag,
                                                jint u_int32, jdouble u_float64, jlong u_ptr) {

    JSValue value;
#if defined(JS_NAN_BOXING)
    (void) u_int32;
    (void) u_float64;
    (void) u_ptr;
    value = tag;
#else
    value.tag = tag;
    value.u.uint64 = static_cast<uint32_t>(u_int32);
    value.u.float64 = u_float64;
    value.u.ptr = (void *) u_ptr;
#endif
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JS_FreeValue(ctx, value);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1get(JNIEnv *env, jclass clazz, jlong context_ptr,
                                         int expected_type,
                                         jobject object_handle, jstring key) {
    ScopedUtfChars key_text(env, key);
    if (key_text.get() == nullptr) return nullptr;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue result = JS_GetPropertyStr(ctx, this_obj, key_text.get());
    return To_JObject(env, context_ptr, expected_type, result);
}
extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1getValue(JNIEnv *env, jclass clazz, jlong context_ptr,
                                              jobject object_handle, jstring key) {
    ScopedUtfChars key_text(env, key);
    if (key_text.get() == nullptr) return nullptr;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue result = JS_GetPropertyStr(ctx, this_obj, key_text.get());
    return TO_JAVA_OBJECT(env, ctx, result);
}



extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1arrayGet(JNIEnv *env, jclass clazz, jlong context_ptr,
                                              int expected_type,
                                              jobject object_handle, jint index) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue result = JS_GetPropertyUint32(ctx, this_obj, index);
    jobject jo = To_JObject(env, context_ptr, expected_type, result);
//    JS_FreeValue(ctx, result);
    return jo;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1arrayGetValue(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                   jobject object_handle, jint index) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue result = JS_GetPropertyUint32(ctx, this_obj, index);
    return TO_JAVA_OBJECT(env, ctx, result);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_quickjs_QuickJSNativeImpl__1contains(JNIEnv *env, jclass clazz, jlong context_ptr,
                                              jobject object_handle, jstring key) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    ScopedUtfChars key_text(env, key);
    if (key_text.get() == nullptr) return JNI_FALSE;
    JSAtom atom = JS_NewAtom(ctx, key_text.get());
    int result = JS_HasProperty(ctx, this_obj, atom);
    JS_FreeAtom(ctx, atom);
    return result;
}

static bool setStringElement(JNIEnv *env, jobjectArray array, jsize index, const char *text) {
    if (env->ExceptionCheck()) return false;
    jstring value = env->NewStringUTF(text);
    if (value == nullptr) return false;
    env->SetObjectArrayElement(array, index, value);
    env->DeleteLocalRef(value);
    return !env->ExceptionCheck();
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_quickjs_QuickJSNativeImpl__1getKeys(JNIEnv *env, jclass clazz, jlong context_ptr,
                                             jobject object_handle) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSPropertyEnum *tab = nullptr;
    uint32_t len = 0;
    if (JS_GetOwnPropertyNames(ctx, &tab, &len, this_obj, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) < 0) {
        return nullptr;
    }
    jobjectArray stringArray = env->NewObjectArray(len, stringCls, nullptr);
    for (uint32_t i = 0; stringArray != nullptr && i < len; ++i) {
        JSValue name = JS_AtomToString(ctx, tab[i].atom);
        const char *text = JS_IsException(name) ? nullptr : JS_ToCStringLen2(ctx, nullptr, name, true);
        bool stored = text != nullptr && setStringElement(env, stringArray, i, text);
        JS_FreeCString(ctx, text);
        JS_FreeValue(ctx, name);
        if (!stored) {
            env->DeleteLocalRef(stringArray);
            stringArray = nullptr;
        }
    }
    JS_FreePropertyEnum(ctx, tab, len);
    return stringArray;
}

JSValue executeFunction(JNIEnv *env, jlong context_ptr, jobject object_handle, JSValue func,
                        jobject parameters_handle) {
    if (JS_IsException(func)) return func;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);

    JSValue argArray = parameters_handle != nullptr ? TO_JS_VALUE(env, parameters_handle) : JS_UNDEFINED;
    const int argc = parameters_handle != nullptr ? GetArrayLength(ctx, argArray) : 0;
    JSValue *argv = argc > 0 ? new (std::nothrow) JSValue[argc] : nullptr;
    if (argc < 0 || (argc > 0 && argv == nullptr)) {
        JS_FreeValue(ctx, func);
        return argc < 0 ? JS_EXCEPTION : JS_ThrowOutOfMemory(ctx);
    }

    int read = 0;
    for (; read < argc; ++read) {
        argv[read] = JS_GetPropertyUint32(ctx, argArray, read);
        if (JS_IsException(argv[read])) break;
    }
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue thisArg = JS_Equals(this_obj, global) ? JS_UNDEFINED : this_obj;
    JSValue result = read == argc ? JS_Call(ctx, func, thisArg, argc, argv) : JS_EXCEPTION;
    JS_FreeValue(ctx, func);
    JS_FreeValue(ctx, global);
    for (int i = 0; i < read; ++i) JS_FreeValue(ctx, argv[i]);
    delete[] argv;
    return result;
}


extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1executeFunction2(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                      jint expected_type, jobject object_handle,
                                                      jobject functionHandle,
                                                      jobject parameters_handle) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue func_obj = TO_JS_VALUE(env, functionHandle);
    JS_DupValue(ctx, func_obj);
    JSValue value = executeFunction(env, context_ptr, object_handle, func_obj, parameters_handle);
    jobject result = To_JObject(env, context_ptr, expected_type, value);
    return result;
}


extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1executeFunction(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                     jint expected_type, jobject object_handle,
                                                     jstring name, jobject parameters_handle) {
    ScopedUtfChars name_text(env, name);
    if (name_text.get() == nullptr) return nullptr;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue func_obj = JS_GetPropertyStr(ctx, this_obj, name_text.get());
    JSValue value = executeFunction(env, context_ptr, object_handle, func_obj, parameters_handle);
    jobject result = To_JObject(env, context_ptr, expected_type, value);
    return result;
}

static JSValue javaCallbackError(JNIEnv *env, JSContext *ctx) {
    if (!env->ExceptionCheck()) return JS_ThrowInternalError(ctx, "Java callback unavailable");
    jthrowable failure = env->ExceptionOccurred();
    env->ExceptionClear();
    auto message = static_cast<jstring>(env->CallObjectMethod(failure, throwableMessageMethodID));
    if (env->ExceptionCheck()) env->ExceptionClear();
    {
        ScopedUtfChars text(env, message);
        if (env->ExceptionCheck()) env->ExceptionClear();
        JS_ThrowInternalError(ctx, "%s", text.get() != nullptr ? text.get() : "Java callback failed");
    }
    env->DeleteLocalRef(message);
    env->DeleteLocalRef(failure);
    return JS_EXCEPTION;
}

static JSValue invokeJavaCallback(JSContext *ctx, JSValueConst this_val, int argc,
                                  JSValueConst *argv, int callbackId, bool void_method) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JS_ThrowInternalError(ctx, "Java callback thread is detached");
    }
    if (env->ExceptionCheck()) return JS_EXCEPTION;
    JSValue args = JS_NewArray(ctx);
    if (JS_IsException(args)) return args;
    for (int i = 0; i < argc; ++i) {
        if (JS_SetPropertyUint32(ctx, args, i, JS_DupValue(ctx, argv[i])) < 0) {
            JS_FreeValue(ctx, args);
            return JS_EXCEPTION;
        }
    }

    jobject objectHandle = TO_JAVA_OBJECT(env, ctx, JS_DupValue(ctx, this_val));
    if (objectHandle == nullptr) {
        JS_FreeValue(ctx, args);
        if (JS_HasException(ctx) && !env->ExceptionCheck()) return JS_EXCEPTION;
        return javaCallbackError(env, ctx);
    }
    jobject argsHandle = TO_JAVA_OBJECT(env, ctx, args);
    jobject result = argsHandle == nullptr ? nullptr : env->CallStaticObjectMethod(
            quickJSCls, callJavaCallbackMethodID, (jlong) ctx, callbackId, objectHandle,
            argsHandle, static_cast<jboolean>(void_method));
    JSValue converted = argsHandle == nullptr || env->ExceptionCheck()
            ? JS_EXCEPTION : JobjectToJSValue(env, ctx, result);
    env->DeleteLocalRef(result);
    env->DeleteLocalRef(argsHandle);
    env->DeleteLocalRef(objectHandle);
    if (env->ExceptionCheck() || (argsHandle == nullptr && !JS_HasException(ctx))) {
        JS_FreeValue(ctx, converted);
        return javaCallbackError(env, ctx);
    }
    return converted;
}

JSValue callJavaCallback(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                         int magic, JSValue *func_data) {
    return invokeJavaCallback(ctx, this_val, argc, argv, JS_VALUE_GET_INT(func_data[0]),
                              JS_VALUE_GET_BOOL(func_data[1]));
}

JSValue newFunction(jlong context_ptr, jboolean void_method, int callbackId) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValueConst func_data[2];
    func_data[0] = JS_NewInt32(ctx, callbackId);
    func_data[1] = JS_NewBool(ctx, void_method);
    JSValue func = JS_NewCFunctionData(ctx, callJavaCallback, 1, 0, 2, func_data);
    return func;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1initNewJSFunction(JNIEnv *env,
                                                       jclass clazz,
                                                       jlong context_ptr,
                                                       jint callbackId,
                                                       jboolean void_method) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue func = newFunction(context_ptr, void_method, callbackId);
    return TO_JAVA_OBJECT(env, ctx, func);
}
extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1registerJavaMethod(JNIEnv *env, jclass clazz,
                                                        jlong context_ptr,
                                                        jobject object_handle,
                                                        jstring function_name,
                                                        jint callbackId,
                                                        jboolean void_method) {
    ScopedUtfChars name_text(env, function_name);
    if (name_text.get() == nullptr) return nullptr;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue func = newFunction(context_ptr, void_method, callbackId);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JS_SetPropertyStr(ctx, this_obj, name_text.get(), JS_DupValue(ctx, func));
    return TO_JAVA_OBJECT(env, ctx, func);
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_quickjs_QuickJSNativeImpl__1getObjectType(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                   jobject object_handle) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue value = TO_JS_VALUE(env, object_handle);
    return GetObjectType(ctx, value);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1set(JNIEnv *env, jclass clazz, jlong context_ptr,
                                         jobject object_handle, jstring key, jobject value) {
    ScopedUtfChars key_text(env, key);
    if (key_text.get() == nullptr) return;
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    JSValue js_value = JobjectToJSValue(env, ctx, value);
    if (JS_IsException(js_value)) return;
    JS_SetPropertyStr(ctx, this_obj, key_text.get(), js_value);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_quickjs_QuickJSNativeImpl__1arrayAdd(JNIEnv *env, jclass clazz, jlong context_ptr,
                                              jobject object_handle, jobject value) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue this_obj = TO_JS_VALUE(env, object_handle);
    int len = GetArrayLength(ctx, this_obj);
    if (len < 0) return;
    JSValue js_value = JobjectToJSValue(env, ctx, value);
    if (JS_IsException(js_value)) return;
    JS_SetPropertyUint32(ctx, this_obj, len, js_value);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_quickjs_QuickJSNativeImpl__1isUndefined(JNIEnv *env, jclass clazz, jlong context_ptr,
                                                 jobject js_value) {
    JSValue value = TO_JS_VALUE(env, js_value);
    return JS_IsUndefined(value);
}extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1Undefined(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    return TO_JAVA_OBJECT(env, ctx, JS_UNDEFINED);
}

static bool setExceptionField(JNIEnv *env, JSContext *ctx, jobjectArray array, jsize index,
                              JSValue value, const char *fallback) {
    const char *text = JS_IsString(value) ? JS_ToCStringLen2(ctx, nullptr, value, true) : nullptr;
    const bool stored = setStringElement(env, array, index, text != nullptr ? text : fallback);
    JS_FreeCString(ctx, text);
    JS_FreeValue(ctx, value);
    // 清掉读取 name/message/stack 时产生的二次异常，原异常由调用方报告。
    if (JS_HasException(ctx)) JS_FreeValue(ctx, JS_GetException(ctx));
    return stored;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_quickjs_QuickJSNativeImpl__1getException(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    if (!JS_HasException(ctx)) return nullptr;
    JSValue error = JS_GetException(ctx);
    const bool isError = JS_IsError(ctx, error);
    jobjectArray fields = env->NewObjectArray(3, stringCls, nullptr);
    if (fields != nullptr) {
        const auto field = [&](const char *key) {
            return isError ? JS_GetPropertyStr(ctx, error, key) : JS_UNDEFINED;
        };
        const bool stored = setExceptionField(env, ctx, fields, 0, field("name"), "Error") &&
            setExceptionField(env, ctx, fields, 1,
                              isError || JS_IsObject(error) ? field("message") : JS_ToString(ctx, error),
                              isError ? "" : "JavaScript threw a non-Error value") &&
            setExceptionField(env, ctx, fields, 2, field("stack"), "");
        if (!stored) {
            env->DeleteLocalRef(fields);
            fields = nullptr;
        }
    }
    JS_FreeValue(ctx, error);
    return fields;
}

JSModuleDef *_JSModuleLoaderFunc(JSContext *ctx, const char *module_name, void *opaque) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return nullptr;
    jstring name = env->NewStringUTF(module_name);
    if (name == nullptr) return nullptr;
    auto result = (jstring) env->CallStaticObjectMethod(
        quickJSCls, getModuleScriptMethodID, (jlong) ctx, name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck() || result == nullptr) {
        env->DeleteLocalRef(result);
        return nullptr;
    }

    JSValue func_val = JS_EXCEPTION;
    {
        ScopedUtfChars script(env, result);
        if (script.get() != nullptr) {
            func_val = JS_Eval(ctx, script.get(), (size_t) env->GetStringUTFLength(result),
                              module_name, JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
        }
    }
    env->DeleteLocalRef(result);
    if (JS_IsException(func_val)) return nullptr;
    auto *module = (JSModuleDef *) JS_VALUE_GET_PTR(func_val);
    JS_FreeValue(ctx, func_val);
    return module;
}

char *_JSModuleNormalizeFunc(JSContext *ctx,
                             const char *module_base_name,
                             const char *module_name, void *opaque) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return nullptr;
    jstring base_name = env->NewStringUTF(module_base_name);
    if (base_name == nullptr) return nullptr;
    jstring name = env->NewStringUTF(module_name);
    if (name == nullptr) {
        env->DeleteLocalRef(base_name);
        return nullptr;
    }
    auto result = (jstring) env->CallStaticObjectMethod(
        quickJSCls, convertModuleNameMethodID, (jlong) ctx, base_name, name);
    env->DeleteLocalRef(base_name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck() || result == nullptr) {
        env->DeleteLocalRef(result);
        return nullptr;
    }

    char *normalized = nullptr;
    {
        ScopedUtfChars text(env, result);
        // QuickJS 会 js_free 返回值，不能转交 JNI 所有的 UTF 缓冲区。
        if (text.get() != nullptr) normalized = js_strdup(ctx, text.get());
    }
    env->DeleteLocalRef(result);
    return normalized;
}

void initES6Module(JSRuntime *rt) {
    JS_SetModuleLoaderFunc(rt, _JSModuleNormalizeFunc, _JSModuleLoaderFunc, nullptr);
}

JSValue js_worker_constructor(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                              int me) {
    JSValue id = JS_GetPropertyStr(ctx, this_val, "java_caller_id");
    int32_t callbackId;
    const int status = JS_ToInt32(ctx, &callbackId, id);
    JS_FreeValue(ctx, id);
    if (status < 0) return JS_EXCEPTION;
    return invokeJavaCallback(ctx, this_val, argc, argv, callbackId, false);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_quickjs_QuickJSNativeImpl__1newClass(JNIEnv *env, jobject thiz, jlong context_ptr,
                                              jint java_caller_id) {
    auto *ctx = reinterpret_cast<JSContext *>(context_ptr);
    JSValue func = JS_NewCFunctionMagic(ctx, js_worker_constructor, "Worker", 1,
                                        JS_CFUNC_constructor_magic, java_caller_id);
    JS_SetPropertyStr(ctx, func, "java_caller_id", JS_NewInt32(ctx, java_caller_id));
    return TO_JAVA_OBJECT(env, ctx, func);
}
