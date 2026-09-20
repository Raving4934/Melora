// AGP 内置 Kotlin 仍默认旧编译器；显式对齐 Compose 插件与依赖的 Kotlin 元数据版本。
buildscript {
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
