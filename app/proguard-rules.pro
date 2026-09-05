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
# Task23 (Opus) / Task41 (Vosk): JNI entry points are reached from native code
#   and are invisible to R8. Native-called classes and methods must be kept,
#   e.g. -keepclasseswithmembernames class * { native <methods>; }
# ---------------------------------------------------------------------------

# JNI: methods called from native code cannot be renamed. Harmless before any
# native library exists, and required from Task23 onward.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
