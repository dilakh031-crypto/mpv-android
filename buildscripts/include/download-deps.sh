#!/bin/bash -e

. ./include/depinfo.sh

[ -z "$IN_CI" ] && IN_CI=0
[ -z "$WGET" ] && WGET=wget

mkdir -p deps && cd deps

fetch_git_revision() {
	local repository=$1
	local directory=$2
	local revision=$3
	local recursive=${4:-0}

	if [ -e "$directory" ]; then
		if [ ! -d "$directory/.git" ]; then
			echo "Dependency $directory exists but is not a git checkout" >&2
			return 1
		fi
		local current
		current=$(git -C "$directory" rev-parse HEAD)
		if [ "$current" != "$revision" ]; then
			echo "Dependency $directory is at $current, expected $revision" >&2
			return 1
		fi
	else
		git init -q "$directory"
		git -C "$directory" remote add origin "$repository"
		git -C "$directory" fetch --depth=1 origin "$revision"
		git -C "$directory" checkout -q --detach FETCH_HEAD
	fi

	if [ "$recursive" -eq 1 ]; then
		git -C "$directory" submodule sync --recursive
		git -C "$directory" submodule update --init --recursive --depth=1
	fi
}

download_lua_archive() {
	local destination=$1
	local filename="lua-$v_lua.tar.gz"
	local expected_sha256=b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b
	local url

	# lua.org can be unreachable from GitHub-hosted runners. Bazel's mirror
	# contains the byte-identical upstream release; keep lua.org as fallback.
	for url in \
		"https://mirror.bazel.build/www.lua.org/ftp/$filename" \
		"https://www.lua.org/ftp/$filename"
	do
		if $WGET --tries=3 --timeout=30 "$url" -O "$destination" && \
			printf '%s  %s\n' "$expected_sha256" "$destination" | sha256sum -c -
		then
			return 0
		fi
		echo "Failed to download a valid $filename from $url" >&2
	done

	return 1
}

# mbedtls
if [ ! -d mbedtls ]; then
	mkdir mbedtls
	$WGET https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$v_mbedtls/mbedtls-$v_mbedtls.tar.bz2 -O - | \
		tar -xj -C mbedtls --strip-components=1
fi

# dav1d
fetch_git_revision https://github.com/videolan/dav1d dav1d "$v_ci_dav1d"

# ffmpeg
fetch_git_revision https://github.com/FFmpeg/FFmpeg ffmpeg "$v_ci_ffmpeg"

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

# libass
fetch_git_revision https://github.com/libass/libass libass "$v_ci_libass"

# lua
if [ ! -d lua ]; then
	lua_archive=$(mktemp "lua-$v_lua.XXXXXX")
	trap 'rm -f "$lua_archive"' EXIT
	download_lua_archive "$lua_archive"
	mkdir lua
	tar -xzf "$lua_archive" -C lua --strip-components=1
	rm -f "$lua_archive"
	trap - EXIT
fi

# libplacebo
fetch_git_revision https://github.com/haasn/libplacebo libplacebo "$v_ci_libplacebo" 1

# mpv
fetch_git_revision https://github.com/mpv-player/mpv mpv "$v_ci_mpv"

cd ..
