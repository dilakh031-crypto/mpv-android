#!/bin/bash -e

## Dependency versions
# Make sure to keep v_ndk and v_ndk_n in sync, both are listed on the NDK download page

v_sdk=11076708_latest
v_ndk=r29
v_ndk_n=29.0.14206865
v_sdk_platform=35
v_sdk_build_tools=35.0.0

v_lua=5.2.4
v_unibreak=7.0
v_harfbuzz=14.2.0
v_fribidi=1.0.16
v_freetype=2.14.3
v_mbedtls=3.6.6
v_libxml2=2.15.3
v_fontconfig=2.17.1


## Dependency tree

dep_mbedtls=()
dep_dav1d=()
dep_libxml2=()
dep_ffmpeg=(mbedtls dav1d libxml2)
dep_freetype2=()
dep_fontconfig=(libxml2 freetype2)
dep_fribidi=()
dep_harfbuzz=()
dep_unibreak=()
dep_libass=(freetype2 fontconfig fribidi harfbuzz unibreak)
dep_lua=()
dep_libplacebo=()
dep_mpv=(ffmpeg libass lua libplacebo)
dep_mpv_android=(mpv)


## for CI workflow

# Pinned revisions from the official mpv-android 2026-04-25 release.
# Keeping all git dependencies detached at these commits makes local and CI builds reproducible.
v_git_dav1d=c0f2fe3135e2f193e31089ff013f628b01aa8d21
v_git_ffmpeg=fc4960b155aa33b9a08cf26c5e0a0530f0545f24
v_git_libass=fadc390583f24eb5cf98f16925fd3adee50bca88
v_git_libplacebo=82224764a98164ce9d2d9a10e4fefca934e475fb
v_git_mpv=9ce79bcaa0132660a2e45b6bfc1fb0c199665277
v_ci_ffmpeg=$v_git_ffmpeg

# filename used to uniquely identify a build prefix
ci_tarball="prefix-ndk-${v_ndk}-lua-${v_lua}-unibreak-${v_unibreak}-harfbuzz-${v_harfbuzz}-fribidi-${v_fribidi}-freetype-${v_freetype}-libxml2-${v_libxml2}-fontconfig-${v_fontconfig}-mbedtls-${v_mbedtls}-dav1d-${v_git_dav1d}-ffmpeg-${v_git_ffmpeg}-libass-${v_git_libass}-libplacebo-${v_git_libplacebo}-mpv-${v_git_mpv}.tgz"
