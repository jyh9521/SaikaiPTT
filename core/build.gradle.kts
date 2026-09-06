import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `core` is a pure Kotlin/JVM module on purpose (docs/02_Architecture.md 5.1).
//
// It holds the parts worth testing hardest -- packet encoding and validation,
// the PTT session state machine, peer presence rules, the domain model -- and
// keeping it off the Android plugin means those tests run on the JVM in
// milliseconds rather than needing a device or Robolectric.
//
// It also turns one of the mandatory architectural constraints from a rule into
// a fact: core *cannot* reference android.* or androidx.*, because no such types
// are on its compile classpath.

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        // Must match the Java target above. Gradle fails the build on an
        // inconsistent JVM-target pair, and the :app module compiles at 11.
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
