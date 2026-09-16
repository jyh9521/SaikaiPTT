// At the top so `Properties` resolves to java.util.Properties. Inside a build
// script `java` is Gradle's own project extension, so `java.util.Properties`
// parses as `project.java`.`util` and does not compile.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor. Only :app has annotations to process.
    alias(libs.plugins.ksp)
}

/**
 * Where Room writes the schema of every database version.
 *
 * Committed to the repository, which is the point: it is the record of what
 * version 1 actually was, and without it a migration written later has nothing
 * to migrate from. docs/05_DataModel.md requires the export, and the database
 * deliberately has no destructive fallback, so a missing migration is a crash
 * in development rather than a silent wipe in the field.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Release signing, read from a file that is never committed.
 *
 * `keystore.properties` is in `.gitignore` along with `*.jks` and `*.keystore`
 * (`.claude/CLAUDE.md` section 43.1 forbids committing either). See
 * `keystore.properties.example` for the four keys.
 *
 * When the file is absent -- every developer machine that has not been set up
 * to sign, and CI -- `assembleRelease` still succeeds and produces an
 * **unsigned** APK. That is deliberate: a build that fails for want of a
 * keystore stops people running Lint and the release-only shrinking rules,
 * which are the two things a release build is most useful for before there is
 * anything to publish. The build says so once at configuration time, so an
 * unsigned APK is never a silent surprise.
 */
val keystoreProperties: Properties? =
    rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        // Written out rather than with `apply`: in a build script that name is
        // Gradle's plugin-applying `apply`, which returns Unit.
        val loaded = Properties()
        file.inputStream().use { stream -> loaded.load(stream) }
        loaded
    }

if (keystoreProperties == null) {
    logger.lifecycle(
        "SaikaiPTT: no keystore.properties -- release builds will be UNSIGNED. " +
            "See keystore.properties.example."
    )
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

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    // Passed in rather than derived inside CMakeLists, so the
                    // build file does not have to count "../" levels up out of
                    // src/main/cpp and get it wrong when the layout changes.
                    // invariantSeparatorsPath because CMake wants forward
                    // slashes even on Windows.
                    "-DOPUS_SOURCE_DIR=" +
                        rootProject.layout.projectDirectory.dir("third_party/opus")
                            .asFile.invariantSeparatorsPath,
                    // The codec is C. Without this AGP would package
                    // libc++_shared.so as well -- a second shared object whose
                    // 16 KB alignment is somebody else's problem to guarantee
                    // (docs/ADR/ADR-007).
                    "-DANDROID_STL=none",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // libopus requires 3.16; this is the version the SDK ships and
            // AGP will install on demand.
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Declared only when the properties file is there. An empty
        // signingConfig with null paths is worse than none: the build fails
        // deep inside apksigner with a message about a missing file rather
        // than at configuration time with a message about a missing setup.
        keystoreProperties?.let { properties ->
            create("release") {
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                // v1 is required by Android 11; v2 and v3 are what anything
                // newer verifies with, and v3 is what allows a key rotation
                // later without breaking updates.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = keystoreProperties?.let { signingConfigs.getByName("release") }
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

        // AGP 9 起 lint 报告始终生成，htmlReport / xmlReport / textReport
        // 三个开关已废弃，因此不再设置。
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
    // Business and domain logic. The dependency points app -> core and never
    // back: core knows nothing about Android, Compose or this module.
    implementation(project(":core"))

    // Settings storage. DataStore replaces SharedPreferences and is the only
    // persistence for small structured configuration; communication history is
    // Room (docs/05_DataModel.md section 2).
    implementation(libs.androidx.datastore.preferences)

    // Communication history. room-ktx is what makes a DAO return a Flow and a
    // suspend function; without it every query would be blocking and would have
    // to be wrapped by hand at each call site.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Main-thread dispatcher. coroutines-core arrives transitively through :core.
    implementation(libs.kotlinx.coroutines.android)

    // UI. The app is a walkie-talkie: big buttons, clear state, no decoration
    // (docs/04_UI_UX.md section 4). Nothing beyond Compose and Material 3.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    // A ViewModel that survives a rotation. The screen state is derived from
    // flows and would rebuild itself, but the selected target is a choice the
    // user made and losing it when the phone turns is losing their input.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The DAO and its indices are only real on a device: Room's generated code
    // needs SQLite, and an in-memory database is still the platform's.
    androidTestImplementation(libs.androidx.room.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug-only tooling. Must never reach a release build
    // (docs/08_ReleaseChecklist.md section 43).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
