# QuickJS JNI 使用固定类名，乐屿还会反射读取 runtimePtr/contextPtr。
-keep class com.quickjs.** { *; }
