import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.leyu.melora"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.leyu.melora"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.1.1"
        testInstrumentationRunner = "com.leyu.melora.IsolatedTestRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 仅显式提供正式密钥时签名，绝不退回 debug key。CI 发布前校验四项环境变量。
    val releaseKeystore = providers.environmentVariable("MELORA_KEYSTORE_PATH").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("MELORA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MELORA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MELORA_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // debug 独立包名：与正式包共存互不影响，各自独立数据
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Dependency AARs may carry colliding generic META-INF license files. The complete,
    // versioned license/notice archive is packaged as assets/licenses/manifest.json instead;
    // About page and lightweight coverage checks both read that single source.
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Baseline Profile插件先从release复制构建类型，须在DSL最终阶段设置隔离包名，防止initWith覆盖。
androidComponents.finalizeDsl { extension ->
    extension.buildTypes.matching { it.name in setOf("benchmarkRelease", "nonMinifiedRelease") }.forEach {
        it.applicationIdSuffix = ".benchmark"
        it.signingConfig = extension.signingConfigs.getByName("debug")
    }
    // 插件复用release的sourceSet；离线测试目录提供器仅显式加入benchmark变体。
    extension.sourceSets.getByName("benchmarkRelease").apply {
        java.srcDir("src/benchmarkRelease/java")
        manifest.srcFile("src/benchmarkRelease/AndroidManifest.xml")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(project(":quickjs-android"))
    implementation(libs.okhttp)
    implementation(libs.haze)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.tooling)
    baselineProfile(project(":baselineprofile"))
}

// 实际解析图每次构建都检查；不能依赖Agent本地日志或只检查手写implementation列表。
val verifyBundledLicenses = tasks.register("verifyBundledLicenses") {
    group = "verification"
    description = "Verify offline notices cover resolved Android runtime dependencies"
    val manifest = layout.projectDirectory.file("src/main/assets/licenses/manifest.json")
    inputs.file(manifest)
    doLast {
        val root = JsonSlurper().parse(manifest.asFile) as Map<*, *>
        val entries = root["entries"] as List<*>
        val patterns = entries.flatMap { (it as Map<*, *>)["artifactPatterns"] as? List<*> ?: emptyList<Any>() }
            .map { pattern -> Regex("^" + pattern.toString().split('*').joinToString(".*", transform = Regex::escape) + "$") }
        val modules = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").flatMap { name ->
            configurations.getByName(name).incoming.resolutionResult.allComponents.mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
            }
        }.distinct().sorted()
        val reviewed = (root["resolvedModules"] as List<*>).map { it.toString() }.toSet()
        check(modules.toSet() == reviewed) {
            "Runtime dependencies changed; review/update licenses/manifest.json. Added: ${modules.toSet() - reviewed}; removed: ${reviewed - modules.toSet()}"
        }
        val missing = modules.filter { module -> patterns.none { it.matches(module.substringBeforeLast(':')) } }
        check(missing.isEmpty()) { "Missing bundled third-party notices: ${missing.joinToString()}" }
        val report = layout.buildDirectory.file("reports/licenses/runtime-dependencies.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(modules.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Bundled notices cover ${modules.size} resolved Android runtime modules")
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledLicenses) }
