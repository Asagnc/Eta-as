plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "io.github.asagnc.sta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.asagnc.sta"
        // 只跑 Android 16（API 36）及以上：不维护低版本兼容分支，也不适配低端设备。
        minSdk = 36
        targetSdk = 36
        // versionCode 规则：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增。
        // versionName 后缀是本 fork 的构建序号，与上游版本号区分：
        // CI 用 GitHub run number（前缀默认 as），本地备用出包用 etaBuildPrefix=ac 的独立序号，
        // 两类版本名不会互相顶替（见 tools/build-release-local.sh）。
        val buildNumber = providers.gradleProperty("etaBuildNumber").orNull?.takeIf { it.isNotBlank() }
        val buildPrefix = providers.gradleProperty("etaBuildPrefix").orNull
            ?.takeIf { it.matches(Regex("[a-z]{1,4}")) }
            ?: "as"
        versionCode = 2026091202
        versionName = "3.0.4-$buildPrefix${buildNumber ?: 1}"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // 签名方案不在这里配置：AGP 9 只会产出单一方案（实测给 V3），
                // 最终由 CI 的 apksigner 重签步骤统一固定为 V2+V3，
                // 见 .github/workflows/android-release.yml。
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libproot_exec.so",
                "**/libproot_loader.so",
                "**/libeta_pty.so",
                // ripgrep 是已 stripped 的静态二进制，别让 AGP 再动它。
                "**/librg.so",
            )
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}

/**
 * 需要 Robolectric 的测试类：直接扫描测试源码里是否出现 RobolectricTestRunner。
 *
 * Robolectric 的 native runtime 官方只提供 x86_64 Linux 与 macOS arm64 构建
 * （DefaultNativeRuntimeLoader.isSupported 是硬编码白名单，没有开关可绕过），
 * 本机（chroot aarch64）跑这批测试会在 AndroidTestEnvironment.setUpApplicationState
 * 里无条件加载 native runtime 时直接报
 * "The Robolectric native runtime is not supported on Linux (aarch64)"，
 * 与 graphics/sqlite 模式无关，因此只能整体跳过；完整测试集留给 CI（x86_64）跑。
 *
 * 原先手工维护类名清单，已经因为拼写和误删两次静默失效（名单里的类照旧执行、
 * 或者该跳过的没跳过），改成扫描后不会再漏。
 */
val robolectricTestClasses = fileTree("src/test") { include("**/*.kt") }
    .files
    .filter { it.readText().contains("RobolectricTestRunner") }
    .map { it.nameWithoutExtension }
    .sorted()

val knownFailingTestClasses = emptyList<String>()

tasks.withType<Test>().configureEach {
    // 失败时把断言消息与栈打全：只报 "AssertionError at X.kt:71" 的话，
    // 在本地跑不了 Robolectric（aarch64 无 native runtime）时只能靠 CI 日志定位。
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showExceptions = true
        showCauses = true
    }
    filter {
        if (System.getProperty("os.arch") == "aarch64") {
            robolectricTestClasses.forEach { excludeTestsMatching("*$it") }
        }
        knownFailingTestClasses.forEach { excludeTestsMatching("*$it") }
    }
}
