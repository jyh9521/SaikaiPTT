# SaikaiPTT R8 / ProGuard rules.
#
# Release builds enable shrinking via `optimization { enable = true }`, which in
# AGP 9 covers both code shrinking and resource optimisation (there is no
# separate shrinkResources property).
#
# Keep this file minimal. Every rule here is code that R8 may not remove, so a
# blanket "-keep class com.saikai.**" would defeat the point. Add narrow rules
# only when a real failure proves one is needed.

# Line numbers in crash reports, without exposing original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Rules that later tasks are expected to need. Do not add them pre-emptively;
# add each one when the task lands and a build or runtime failure justifies it.
#
# Task37 (Room): Room generates code and uses annotations. Its artifact ships
#   consumer rules, so usually nothing is needed here.
# Task07 (DataStore): if a serializer uses reflection, its model must be kept.
# ---------------------------------------------------------------------------

# JNI: methods called from native code cannot be renamed. Harmless before any
# native library exists, and required from Task23 (Opus) onward.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# sherpa-onnx (Task45, ADR-012). The rule above is not enough for this one.
#
# Its entry point is `newFromFile(config)`, and the native side reads that
# config object's fields **by name** -- and the fields of the config objects
# nested inside it, several levels down. R8 renames a field it can prove
# nothing in Kotlin reads, which is exactly what those fields look like from
# here, and the failure lands at runtime in a Release build only: the model
# loads with empty paths and the recogniser refuses to construct.
#
# So this is broader than the file's own advice allows, and it is here without
# a failure to point at: read from the library's Kotlin API at v1.13.8, not
# observed. `includedescriptorclasses` above keeps the class *names* reachable
# from the native signatures; nothing keeps their members.
#
# Narrow it once a Release build has actually run
# (docs/11_ReleaseSignOff.md section 8) -- the package is small, and the cost of
# keeping it whole is a few kilobytes against a crash nobody sees in Debug.
-keep class com.k2fsa.sherpa.onnx.** { *; }
