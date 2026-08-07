#!/bin/bash -e

# go to buildscripts root folder
cd "$( dirname "${BASH_SOURCE[0]}" )/.."

. ./include/depinfo.sh

ci_arch=${CI_ARCH:-armv7l}
case "$ci_arch" in
	armv7l|arm64|x86|x86_64) ;;
	*)
		echo "Unsupported CI_ARCH: $ci_arch" >&2
		exit 1
		;;
esac

if [ -n "${CI_ARCH:-}" ]; then
	ci_cache_identifier="${ci_tarball%.tgz}-${ci_arch}.tgz"
else
	ci_cache_identifier="$ci_tarball"
fi

msg() {
	printf '==> %s\n' "$1"
}

fetch_prefix() {
	if [[ "$CACHE_MODE" == folder ]]; then
		local text=
		if [ -f "$CACHE_FOLDER/id.txt" ]; then
			text=$(cat "$CACHE_FOLDER/id.txt")
		else
			echo "Cache seems to be empty"
		fi
		printf 'Expecting "%s",\nfound     "%s".\n' "$ci_cache_identifier" "$text"
		if [[ "$text" == "$ci_cache_identifier" ]]; then
			tar -xzf "$CACHE_FOLDER/data.tgz" -C prefix && return 0
		fi
	fi
	return 1
}

build_prefix() {
	msg "Building the prefix ($ci_tarball)..."

	msg "Fetching deps"
	IN_CI=1 ./include/download-deps.sh

	msg "Compiling"
	./buildall.sh --arch "$ci_arch" --only-deps mpv

	if [[ "$CACHE_MODE" == folder && -w "$CACHE_FOLDER" ]]; then
		msg "Compressing the prefix"
		tar -cvzf "$CACHE_FOLDER/data.tgz" -C prefix .
		echo "$ci_cache_identifier" >"$CACHE_FOLDER/id.txt"
	fi
}

export WGET="wget --progress=bar:force"

if [ "$1" = "export" ]; then
	# export variable with unique cache identifier
	echo "CACHE_IDENTIFIER=$ci_cache_identifier"
	exit 0
elif [ "$1" = "install" ]; then
	# install deps
	if [[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME" ]]; then
		msg "Linking existing SDK"
		mkdir -p sdk
		ln -sv "$ANDROID_HOME" sdk/android-sdk-linux
	fi

	msg "Fetching SDK + NDK"
	IN_CI=1 ./include/download-sdk.sh

	msg "Fetching pinned mpv revision $v_ci_mpv"
	mkdir -p deps
	if [ -e deps/mpv ]; then
		if [ ! -d deps/mpv/.git ]; then
			echo "deps/mpv exists but is not a git checkout" >&2
			exit 1
		fi
		actual_mpv_revision=$(git -C deps/mpv rev-parse HEAD)
		if [ "$actual_mpv_revision" != "$v_ci_mpv" ]; then
			echo "deps/mpv is at $actual_mpv_revision, expected $v_ci_mpv" >&2
			exit 1
		fi
	else
		git init -q deps/mpv
		git -C deps/mpv remote add origin https://github.com/mpv-player/mpv
		git -C deps/mpv fetch --depth=1 origin "$v_ci_mpv"
		git -C deps/mpv checkout -q --detach FETCH_HEAD
	fi

	msg "Trying to fetch existing prefix"
	mkdir -p prefix
	fetch_prefix || build_prefix
	exit 0
elif [ "$1" = "build" ]; then
	# run build
	:
else
	exit 1
fi

msg "Building mpv"
./buildall.sh --arch "$ci_arch" -n mpv || {
	# show logfile if configure failed
	[ ! -f deps/mpv/_build/config.h ] && cat deps/mpv/_build/meson-logs/meson-log.txt
	exit 1
}

msg "Building mpv-android"
./buildall.sh --arch "$ci_arch" -n

exit 0
