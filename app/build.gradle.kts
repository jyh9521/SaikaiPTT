plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.saikai.ptt"

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "com.saikai.ptt"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Task23 (Opus) and Task41 (Vosk) add native libraries. Both must ship
        // 16 KB page-aligned .so files, required by Android 15+.
        // See docs/ADR/ADR-004 and ADR-006.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // In AGP 9 this single switch covers code shrinking AND resource
            // optimisation; there is no separate shrinkResources property.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Store native libraries uncompressed and page-aligned in the APK.
            // This is what lets the loader map .so files directly, and is a
            // precondition for the 16 KB alignment requirement (ADR-004).
            // Set explicitly rather than relying on the default, because it is
            // load-bearing for Task23 and Task41.
            useLegacyPackaging = false
        }
    }

    lint {
        // A lint error must break the build. docs/08_ReleaseChecklist.md
        // section 5 forbids shipping with blocker-severity findings, and a
        // warning nobody reads is not a control.
        abortOnError = true
        checkReleaseBuilds = true
        explainIssues = true

        // Not warningsAsErrors: at this size it turns unrelated advisories into
        // build failures and trains people to disable lint. Specific issues are
        // promoted individually below instead.
        warningsAsErrors = false

        // Third-party findings are not actionable here and would drown ours.
        checkDependencies = false

        // Hard-coded user-facing strings must never reach a build.
        // docs/01_PRD.md section 26 and docs/04_UI_UX.md section 49 both forbid
        // them, and i18n is mandatory from the start rather than retrofitted.
        error += "HardcodedText"

        // MissingTranslation stays a warning until Task35 lands all five
        // locales; promoting it now would block every task in between.
        warning += "MissingTranslation"

        // ChromeOsAbiSupport: ABI 只保留 arm64-v8a 与 armeabi-v7a。ChromeOS 需要
        // x86_64，但本产品依赖麦克风与局域网 UDP，不是 ChromeOS 目标场景，
        // 为它多打一个 ABI 只会增大安装包。
        disable += "ChromeOsAbiSupport"

        // OldTargetApi: targetSdk 36 是经过评估的选择（API 37 平台在开发机上
        // 并未安装，且本产品的约束是低端 Android 11 稳定性）。targetSdk 的复核
        // 属于发布检查项，见 docs/08_ReleaseChecklist.md 第 3 节，不需要每次
        // 构建都提醒。
        disable += "OldTargetApi"

        htmlReport = true
        xmlReport = true
        textReport = false
    }

    testOptions {
        unitTests {
            // Let JVM unit tests read resources and stubbed Android APIs rather
            // than throwing. Protocol, session-state and storage logic lives in
            // plain Kotlin (docs/02_Architecture.md section 5.1) precisely so it
            // can be tested without a device, but tests that touch resources
            // need this.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // UI. The app is a walkie-talkie: big buttons, clear state, no decoration
    // (docs/04_UI_UX.md section 4). Nothing beyond Compose and Material 3.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Test
    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug-only tooling. Must never reach a release build
    // (docs/08_ReleaseChecklist.md section 43).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
