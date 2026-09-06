// SaikaiPTT — top-level build file.
//
// Module layout is defined in docs/02_Architecture.md section 5:
// only :app and :core are separate Gradle modules; every other boundary
// is expressed as a package. :core is created in Task04.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
