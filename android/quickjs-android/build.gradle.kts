plugins {
    alias(libs.plugins.android.library)
}

val meloraDebugAbi = providers.gradleProperty("melora.debugAbi").orNull
require(meloraDebugAbi == null || meloraDebugAbi == "x86_64") {
    "melora.debugAbi currently supports only x86_64."
}

android {
    namespace = "com.quickjs"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    // defaultConfig 与 buildType 的 ABI 会合并；在同一层选择，避免模拟器包混入 ARM 库。
    buildTypes.configureEach {
        ndk.abiFilters += if (name == "debug") meloraDebugAbi ?: "arm64-v8a" else "arm64-v8a"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
