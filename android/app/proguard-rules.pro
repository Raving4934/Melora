# JNI 入口按 Java 类名/方法名查找；保留 native 方法的外部符号名称。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
