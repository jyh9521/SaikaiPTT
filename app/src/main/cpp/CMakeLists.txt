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

# --- Upstream defect: libopus v1.6.1 + CMake + ARM + fixed point never links ---
#
# The failure is `undefined symbol: celt_pitch_xcorr_neon`, on both arm64-v8a and
# armeabi-v7a. The chain, all line numbers in the pinned v1.6.1 tree:
#
#   * celt/arm/pitch_arm.h declares celt_pitch_xcorr_neon inside
#     `#if defined(FIXED_POINT)` / `#if defined(OPUS_ARM_MAY_HAVE_NEON)`, and
#     celt/arm/arm_celt_map.c puts MAY_HAVE_NEON(celt_pitch_xcorr) into the
#     runtime-dispatch table under the same macro.
#   * The only definition of that symbol is hand-written assembly, in
#     celt/arm/celt_pitch_xcorr_arm.s.
#   * cmake/OpusSources.cmake reads that file into CELT_SOURCES_ARM_ASM, and
#     nothing in the CMake build ever uses that variable. The autotools build
#     runs it through arm2gnu.pl and assembles it; the CMake build does not.
#   * CMakeLists.txt nevertheless defines OPUS_ARM_MAY_HAVE_NEON, and
#     cmake/OpusConfig.cmake force-sets OPUS_PRESUME_NEON ON for aarch64 with a
#     plain set(), so turning the OPUS_MAY_HAVE_NEON option off does not help:
#     pitch_arm.h's PRESUME branch recreates the same reference.
#
# So the reference exists and the definition cannot. This is only reachable with
# OPUS_FIXED_POINT=ON -- the float build routes celt_pitch_xcorr through NEON
# *intrinsics* instead -- and fixed point is a deliberate choice here (ADR-007).
#
# The fix strips exactly the two assembly-flavoured macros off the opus target and
# leaves the _INTR spellings alone. That is what keeps this cheap: every NEON
# intrinsics path stays compiled in -- xcorr_kernel_neon_fixed, celt_inner_prod,
# dual_inner_prod, and the SILK NSQ / NSQ_del_dec / biquad / LPC_inv_pred_gain /
# warped-autocorrelation kernels -- and runtime CPU detection in celt/arm/armcpu.c
# stays on, because every guard there also tests OPUS_ARM_MAY_HAVE_NEON_INTR.
# Only celt_pitch_xcorr itself drops to its C implementation, which upstream keeps
# bit-identical to the assembly by contract.
#
# Rejected: OPUS_DISABLE_INTRINSICS=ON, which links but gives up all NEON, and on
# the MTK P22 reference device that is not a rounding error; and switching to the
# float build, which contradicts ADR-004 and ADR-007.
#
# Revisit when the submodule is bumped: if upstream ever assembles the .s file
# under CMake, delete this block and the NEON assembly comes back for free.
get_target_property(SAIKAI_OPUS_DEFINITIONS opus COMPILE_DEFINITIONS)
if(SAIKAI_OPUS_DEFINITIONS)
    # REMOVE_ITEM is exact-match, so OPUS_ARM_MAY_HAVE_NEON_INTR and
    # OPUS_ARM_PRESUME_NEON_INTR survive. That is the whole point.
    list(REMOVE_ITEM SAIKAI_OPUS_DEFINITIONS
         OPUS_ARM_MAY_HAVE_NEON
         OPUS_ARM_PRESUME_NEON)
    set_property(TARGET opus PROPERTY COMPILE_DEFINITIONS ${SAIKAI_OPUS_DEFINITIONS})
    message(STATUS "saikai: opus compile definitions -> ${SAIKAI_OPUS_DEFINITIONS}")
endif()

# --- Optimise libopus even in a debug build ------------------------------------
#
# opus_decoder.c raises `#warning You appear to be compiling without optimization,
# if so opus will be very slow.` in every debug build, and it means it. The debug
# APK is what gets installed on the test phones, and an unoptimised fixed-point
# encoder on a low-end device can miss the 20 ms frame budget outright -- which
# would show up as stuttering audio and get diagnosed as a network or a capture
# bug rather than as a build setting. Only the codec is affected; opus_jni.c and
# the rest of the app keep the build type's own flags, so breakpoints still work
# everywhere we actually debug.
set_property(TARGET opus APPEND PROPERTY COMPILE_OPTIONS $<$<CONFIG:Debug>:-O2>)

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
