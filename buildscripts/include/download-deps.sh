#!/bin/bash -e

. ./include/depinfo.sh

[ -z "$IN_CI" ] && IN_CI=0
[ -z "$WGET" ] && WGET=wget

checkout_pinned_git() {
    local directory="$1"
    local repository="$2"
    local revision="$3"
    local recursive="${4:-0}"

    if [ ! -d "$directory/.git" ]; then
        # Old CI paths may have unpacked a source tarball here. Replace it with a
        # real git checkout so the requested revision can be verified exactly.
        rm -rf "$directory"
        if [ "$recursive" -eq 1 ]; then
            git clone --recursive "$repository" "$directory"
        else
            git clone "$repository" "$directory"
        fi
    fi

    # Fetch the exact object even when an old dependency directory was restored
    # from cache, then detach HEAD so branches cannot drift between builds.
    git -C "$directory" fetch --depth=1 origin "$revision"
    git -C "$directory" checkout --detach FETCH_HEAD
    if [ "$recursive" -eq 1 ]; then
        git -C "$directory" submodule sync --recursive
        git -C "$directory" submodule update --init --recursive --depth=1
    fi
}

mkdir -p deps && cd deps

# mbedtls
if [ ! -d mbedtls ]; then
    mkdir mbedtls
    $WGET https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$v_mbedtls/mbedtls-$v_mbedtls.tar.bz2 -O - | \
        tar -xj -C mbedtls --strip-components=1
fi

# git dependencies pinned to the official 2026-04-25 release revisions
checkout_pinned_git dav1d https://github.com/videolan/dav1d "$v_git_dav1d"
checkout_pinned_git ffmpeg https://github.com/FFmpeg/FFmpeg "$v_git_ffmpeg"

# freetype2
[ ! -d freetype2 ] && git clone --recurse-submodules https://gitlab.freedesktop.org/freetype/freetype.git freetype2 -b VER-${v_freetype//./-}

# fribidi
if [ ! -d fribidi ]; then
    mkdir fribidi
    $WGET https://github.com/fribidi/fribidi/releases/download/v$v_fribidi/fribidi-$v_fribidi.tar.xz -O - | \
        tar -xJ -C fribidi --strip-components=1
fi

# harfbuzz
if [ ! -d harfbuzz ]; then
    mkdir harfbuzz
    $WGET https://github.com/harfbuzz/harfbuzz/releases/download/$v_harfbuzz/harfbuzz-$v_harfbuzz.tar.xz -O - | \
        tar -xJ -C harfbuzz --strip-components=1
fi

# unibreak
if [ ! -d unibreak ]; then
    mkdir unibreak
    $WGET https://github.com/adah1972/libunibreak/releases/download/libunibreak_${v_unibreak//./_}/libunibreak-${v_unibreak}.tar.gz -O - | \
        tar -xz -C unibreak --strip-components=1
fi

# libxml2
if [ ! -d libxml2 ]; then
    mkdir libxml2
    $WGET https://gitlab.gnome.org/GNOME/libxml2/-/archive/v${v_libxml2}/libxml2-v${v_libxml2}.tar.gz -O - | \
        tar -xz -C libxml2 --strip-components=1
fi

# fontconfig
if [ ! -d fontconfig ]; then
    mkdir fontconfig
    $WGET https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/${v_fontconfig}/fontconfig-${v_fontconfig}.tar.gz -O - | \
        tar -xz -C fontconfig --strip-components=1
fi

checkout_pinned_git libass https://github.com/libass/libass "$v_git_libass"

# lua
if [ ! -d lua ]; then
    mkdir lua
    $WGET https://www.lua.org/ftp/lua-$v_lua.tar.gz -O - | \
        tar -xz -C lua --strip-components=1
fi

checkout_pinned_git libplacebo https://github.com/haasn/libplacebo "$v_git_libplacebo" 1
checkout_pinned_git mpv https://github.com/mpv-player/mpv "$v_git_mpv"

cd ..
