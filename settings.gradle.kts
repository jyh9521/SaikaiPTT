pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx's Android AAR is published as a GitHub release asset, not
        // to Maven Central (ADR-012 section 4). Its jitpack.yml installs that
        // same prebuilt AAR rather than rebuilding it, so what resolves here is
        // the binary whose 16 KB alignment ADR-011 verified.
        //
        // Narrowed to that one group: a build that can reach JitPack for
        // anything is a build whose dependency set nobody is watching.
        //
        // AndSubgroups, not includeGroup. includeGroup is an exact match, and
        // JitPack publishes a multi-module project's real artifacts under a
        // *subgroup* named after the repository -- com.github.k2-fsa.sherpa-onnx,
        // with a dot -- keeping com.github.k2-fsa:sherpa-onnx for an aggregate
        // POM that carries no classes. The app depends on the former, so an
        // exact-match filter excludes JitPack from the only coordinate that
        // needs it: the first build failed with eight "could not find" errors
        // whose searched locations listed google and Maven Central and nothing
        // else.
        maven("https://jitpack.io") {
            content { includeGroupAndSubgroups("com.github.k2-fsa") }
        }
    }
}

rootProject.name = "SaikaiPTT"

// Only two Gradle modules, by design (docs/02_Architecture.md 5.1).
// Every other boundary is a package. A module is added only when it earns one.
include(":app")
include(":core")
