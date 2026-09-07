# The one native library this app ships.
#
# libopus is built as a STATIC library and linked into libsaikaiopus.so, so the
# APK contains exactly one .so. That is not a size optimisation, it is the 16 KB
# alignment strategy (docs/ADR/ADR-007): every shared object in the APK has to be
# 16 KB aligned before February 2027, and the only ones whose link flags we
# control are the ones we link ourselves. ANDROID_STL=none keeps libc++_shared
# out for the same reason.

cmake_minimum_required(VERSION 3.22.1)
project(saikaiopus C)

if(NOT DEFINED OPUS_SOURCE_DIR)
    message(FATAL_ERROR "OPUS_SOURCE_DIR was not passed in; see app/build.gradle.kts")
endif()

if(NOT EXISTS "${OPUS_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "libopus source is missing from ${OPUS_SOURCE_DIR}.\n"
        "It is a git submodule pinned to v1.6.1. Run this once, in the repository root:\n"
        "    git submodule update --init --recursive\n"
        "See docs/ADR/ADR-007-Opus-Integration.md.")
endif()

# Everything the product does not use. DRED and OSCE in particular pull in
# neural-network code and model data that would dwarf the codec itself.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)

# Fixed point, and no float API at all. The reference device is an MTK P22, the
# app only ever calls the int16 entry points, and a build without the float path
# is both smaller and identical on every device.
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API OFF CACHE BOOL "" FORCE)

add_subdirectory("${OPUS_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/opus" EXCLUDE_FROM_ALL)

add_library(saikaiopus SHARED opus_jni.c)

target_link_libraries(saikaiopus PRIVATE opus)

target_compile_options(saikaiopus PRIVATE
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(saikaiopus PRIVATE
    # NDK r28 and later do this by default. Stated anyway so that the build is
    # correct on r27 too, and so that the requirement is visible in the file
    # that has to satisfy it rather than only in a toolchain version.
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    # Only the JNI entry points are exported, and only the parts of libopus they
    # reach are kept.
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
)
