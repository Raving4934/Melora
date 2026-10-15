// 真实 QuickJS/JNI 源码 + 可计数、可失败的 JNI 函数表。
#include "../src/main/cpp/quickjs-jni.cpp"
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>

static void require(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "%s\n", message);
        std::exit(1);
    }
}

struct JavaString : _jstring {
    std::string text;
    explicit JavaString(const char *value) : text(value) {}
};

struct JavaValue : _jobject {
    JSValue value;
    explicit JavaValue(JSValue value) : value(value) {}
};

static std::map<const char *, jstring> utf;
static std::set<jobject> locals;
static int utfCalls, releaseCalls, newCalls, callbackCalls;
static int failUtf, failNew;
static bool pending, failCallback, nullCallback;
static bool invalidModule;
static JNIEnv environment;
static const char *moduleScript = "export const answer = 42;";
static const char *moduleName = "module.js";

static void checkedJni() {
    require(!pending, "JNI operation called with pending Java exception");
}

static const char *JNICALL getUtf(JNIEnv *, jstring value, jboolean *) {
    checkedJni();
    require(value != nullptr, "GetStringUTFChars called with null jstring");
    if (++utfCalls == failUtf) {
        pending = true;
        return nullptr;
    }
    char *copy = ::strdup(static_cast<JavaString *>(value)->text.c_str());
    require(copy != nullptr, "test allocation failed");
    utf.emplace(copy, value);
    return copy;
}

static void JNICALL releaseUtf(JNIEnv *, jstring value, const char *chars) {
    auto entry = utf.find(chars);
    require(entry != utf.end() && entry->second == value,
            "ReleaseStringUTFChars missing acquisition or wrong jstring");
    utf.erase(entry);
    std::free(const_cast<char *>(chars));
    ++releaseCalls;
}

static jsize JNICALL utfLength(JNIEnv *, jstring value) {
    checkedJni();
    return static_cast<jsize>(static_cast<JavaString *>(value)->text.size());
}

static jstring JNICALL newString(JNIEnv *, const char *value) {
    checkedJni();
    if (++newCalls == failNew) {
        pending = true;
        return nullptr;
    }
    auto *result = new JavaString(value);
    locals.insert(result);
    return result;
}

static void JNICALL deleteLocal(JNIEnv *, jobject value) {
    if (value == nullptr) return;
    require(locals.erase(value) == 1, "invalid local reference deletion");
    for (const auto &entry : utf) {
        require(entry.second != value, "local jstring deleted before UTF release");
    }
    delete static_cast<JavaString *>(value);
}

static jboolean JNICALL exceptionCheck(JNIEnv *) { return pending; }

static jlong JNICALL getLong(JNIEnv *, jobject object, jfieldID field) {
    checkedJni();
    auto value = static_cast<JavaValue *>(object)->value;
    return field == js_value_tag_id ? value.tag : reinterpret_cast<jlong>(value.u.ptr);
}

static jint JNICALL getInt(JNIEnv *, jobject object, jfieldID) {
    checkedJni();
    return static_cast<JavaValue *>(object)->value.u.int32;
}

static jdouble JNICALL getDouble(JNIEnv *, jobject object, jfieldID) {
    checkedJni();
    return static_cast<JavaValue *>(object)->value.u.float64;
}

static jboolean JNICALL instanceOf(JNIEnv *, jobject value, jclass cls) {
    checkedJni();
    return value != nullptr && cls == stringCls;
}

static jobject JNICALL staticObject(JNIEnv *, jclass, jmethodID method, va_list args) {
    checkedJni();
    (void) va_arg(args, jlong);  // context
    if (method == createJSValueMethodID) {
        (void) va_arg(args, int);  // type
        JSValue result;
        result.tag = va_arg(args, jlong);
        (void) va_arg(args, int);
        (void) va_arg(args, jdouble);
        result.u.ptr = reinterpret_cast<void *>(va_arg(args, jlong));
        return new JavaValue(result);
    }
    ++callbackCalls;
    if (failCallback) {
        pending = true;
        return nullptr;
    }
    if (nullCallback) return nullptr;
    const char *text = method == getModuleScriptMethodID
        ? (invalidModule ? "export const = ;" : moduleScript) : moduleName;
    auto *result = new JavaString(text);
    locals.insert(result);
    return result;
}

static jint JNICALL getEnv(JavaVM *, void **env, jint) {
    *env = &environment;
    return JNI_OK;
}

static JSValue nullFunction(JSContext *, JSValueConst, int, JSValueConst *) {
    return JS_NULL;
}

static void freeWrapper(JSContext *ctx, jobject object) {
    if (object == nullptr) return;
    auto *wrapper = static_cast<JavaValue *>(object);
    JS_FreeValue(ctx, wrapper->value);
    delete wrapper;
}

int main(int argc, char **argv) {
    require(argc == 2, "expected case name");
    std::string name = argv[1];
    const auto colon = name.find(':');
    if (colon != std::string::npos) {
        int failure = std::stoi(name.substr(colon + 1));
        name.resize(colon);
        if (name == "loaderNew" || name == "normalizeNew") failNew = failure;
        else failUtf = failure;
    }
    failCallback = name == "loaderCallback" || name == "normalizeCallback";
    nullCallback = name == "loaderNull" || name == "normalizeNull";
    invalidModule = name == "invalidModule";

    JNINativeInterface_ table{};
    table.GetStringUTFChars = getUtf;
    table.ReleaseStringUTFChars = releaseUtf;
    table.GetStringUTFLength = utfLength;
    table.NewStringUTF = newString;
    table.DeleteLocalRef = deleteLocal;
    table.ExceptionCheck = exceptionCheck;
    table.GetLongField = getLong;
    table.GetIntField = getInt;
    table.GetDoubleField = getDouble;
    table.IsInstanceOf = instanceOf;
    table.CallStaticObjectMethodV = staticObject;
    environment.functions = &table;
    JNIInvokeInterface_ vmTable{};
    vmTable.GetEnv = getEnv;
    JavaVM vm{&vmTable};
    jvm = &vm;
    createJSValueMethodID = reinterpret_cast<jmethodID>(1);
    getModuleScriptMethodID = reinterpret_cast<jmethodID>(2);
    convertModuleNameMethodID = reinterpret_cast<jmethodID>(3);
    stringCls = reinterpret_cast<jclass>(1);
    js_value_tag_id = reinterpret_cast<jfieldID>(1);

    JSRuntime *rt = JS_NewRuntime();
    JSContext *ctx = JS_NewContext(rt);
    const auto ptr = reinterpret_cast<jlong>(ctx);
    JNIEnv *env = &environment;
    JavaValue object(JS_NewObject(ctx));
    JavaString key("key"), function("fn"), source("null"), file("script.js"), value("text");
    JS_SetPropertyStr(ctx, object.value, "key", JS_NULL);
    JS_SetPropertyStr(ctx, object.value, "fn", JS_NewCFunction(ctx, nullFunction, "fn", 0));
    jobject returned = nullptr;
    bool wrapped = false;
    const bool failure = failUtf || failNew || failCallback || nullCallback || invalidModule;

    if (name == "get") {
        returned = Java_com_quickjs_QuickJSNativeImpl__1get(env, nullptr, ptr, TYPE_NULL, &object, &key);
    } else if (name == "getValue") {
        returned = Java_com_quickjs_QuickJSNativeImpl__1getValue(env, nullptr, ptr, &object, &key);
        wrapped = true;
        require(failure ? returned == nullptr : returned != nullptr, "getValue result changed");
    } else if (name == "contains") {
        bool contains = Java_com_quickjs_QuickJSNativeImpl__1contains(env, nullptr, ptr, &object, &key);
        require(contains == !failure, "contains result changed");
    } else if (name == "executeFunction") {
        returned = Java_com_quickjs_QuickJSNativeImpl__1executeFunction(
            env, nullptr, ptr, TYPE_NULL, &object, &function, nullptr);
    } else if (name == "registerJavaMethod") {
        returned = Java_com_quickjs_QuickJSNativeImpl__1registerJavaMethod(
            env, nullptr, ptr, &object, &key, 7, false);
        wrapped = true;
        require(failure ? returned == nullptr : returned != nullptr, "register result changed");
    } else if (name == "set" || name == "setString") {
        Java_com_quickjs_QuickJSNativeImpl__1set(
            env, nullptr, ptr, &object, &key, name == "setString" ? &value : nullptr);
        JSValue current = JS_GetPropertyStr(ctx, object.value, "key");
        require(JS_IsNull(current), "failed string acquisition changed property");
        JS_FreeValue(ctx, current);
    } else if (name == "arrayString") {
        JavaValue array(JS_NewArray(ctx));
        Java_com_quickjs_QuickJSNativeImpl__1arrayAdd(env, nullptr, ptr, &array, &value);
        require(GetArrayLength(ctx, array.value) == 0, "failed string acquisition appended array");
        JS_FreeValue(ctx, array.value);
    } else if (name == "executeScript" || name == "scriptNoFilename") {
        returned = Java_com_quickjs_QuickJSNativeImpl__1executeScript(
            env, nullptr, ptr, TYPE_NULL, &source, name == "scriptNoFilename" ? nullptr : &file,
            JS_EVAL_TYPE_GLOBAL);
    } else if (name == "stringValue") {
        JSValue result = JobjectToJSValue(env, ctx, &value);
        require(failure ? JS_IsException(result) : JS_IsString(result), "string conversion result changed");
        JS_FreeValue(ctx, result);
    } else if (name.rfind("loader", 0) == 0 || invalidModule) {
        JSModuleDef *module = _JSModuleLoaderFunc(ctx, "module.js", nullptr);
        require(failure ? module == nullptr : module != nullptr, "module loader result changed");
    } else if (name.rfind("normalize", 0) == 0) {
        char *normalized = _JSModuleNormalizeFunc(ctx, "main.js", "module.js", nullptr);
        require(failure ? normalized == nullptr : normalized != nullptr, "normalize result changed");
        require(normalized == nullptr || utf.count(normalized) == 0,
                "normalize returned a JNI-owned pointer to QuickJS allocator");
        if (normalized) {
            require(std::strcmp(normalized, moduleName) == 0, "normalized name changed");
            js_free(ctx, normalized);
        }
    } else if (name == "moduleImport" || name == "unicode") {
        initES6Module(rt);
        const char *script = "import { answer } from 'module.js'; globalThis.result = answer;";
        if (name == "unicode") {
            moduleName = "模块.js";
            moduleScript = "export const answer = '乐屿';";
            script = "import { answer } from '模块.js'; globalThis.result = answer;";
        }
        JSValue result = JS_Eval(ctx, script, std::strlen(script), "main.js", JS_EVAL_TYPE_MODULE);
        require(!JS_IsException(result), "module import failed");
        JS_FreeValue(ctx, result);
        JSValue global = JS_GetGlobalObject(ctx);
        result = JS_GetPropertyStr(ctx, global, "result");
        const char *text = JS_ToCString(ctx, result);
        require(text != nullptr && std::strcmp(text, name == "unicode" ? "乐屿" : "42") == 0,
                "module result changed");
        JS_FreeCString(ctx, text);
        JS_FreeValue(ctx, result);
        JS_FreeValue(ctx, global);
    } else {
        require(false, "unknown case");
    }

    require(utf.empty(), "unreleased GetStringUTFChars allocation");
    require(locals.empty(), "unreleased module callback local jstring");
    require(releaseCalls == utfCalls - (failUtf ? 1 : 0), "UTF acquire/release mismatch");
    require(pending == bool(failUtf || failNew || failCallback), "Java exception not preserved");
    if (failNew) require(callbackCalls == 0, "callback invoked after NewStringUTF failure");
    if (wrapped) freeWrapper(ctx, returned);
    JS_FreeValue(ctx, object.value);
    JS_FreeValue(ctx, JS_GetException(ctx));
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return 0;
}
