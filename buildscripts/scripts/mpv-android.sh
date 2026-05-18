#!/bin/bash -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD="$DIR/.."
MPV_ANDROID="$DIR/../.."

. $BUILD/include/path.sh
. $BUILD/include/depinfo.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $MPV_ANDROID/{app,.}/build $MPV_ANDROID/app/src/main/{libs,obj}
	exit 0
else
	exit 255
fi

[ -n "$ANDROID_SIGNING_KEY" ] && BUNDLE=1

ci_default_arm64_release="${MPV_ANDROID_GHA_DEFAULT_ARM64_RELEASE:-}"

nativeprefix () {
	if [ -f $BUILD/prefix/$1/lib/libmpv.so ]; then
		echo $BUILD/prefix/$1
	else
		echo >&2 "Warning: libmpv.so not found in native prefix for $1, support will be omitted"
	fi
}

if [ -n "$ci_default_arm64_release" ]; then
	prefix32=
	prefix64=$(nativeprefix "arm64")
	prefix_x64=
	prefix_x86=
else
	prefix32=$(nativeprefix "armv7l")
	prefix64=$(nativeprefix "arm64")
	prefix_x64=$(nativeprefix "x86_64")
	prefix_x86=$(nativeprefix "x86")
fi

if [[ -z "$prefix32" && -z "$prefix64" && -z "$prefix_x64" && -z "$prefix_x86" ]]; then
	echo >&2 "Error: no mpv library detected."
	exit 255
fi

PREFIX32=$prefix32 PREFIX64=$prefix64 PREFIX_X64=$prefix_x64 PREFIX_X86=$prefix_x86 \
ndk-build -C app/src/main -j$cores

if [ -n "$ci_default_arm64_release" ]; then
	targets=(assembleDefaultRelease)
	gradle_args=(-PciOnlyAbi=arm64-v8a)
else
	targets=(assembleDebug)
	gradle_args=()
	if [ -z "$DONT_BUILD_RELEASE" ]; then
		targets+=(assembleRelease)
		[ -n "$BUNDLE" ] && targets+=(bundleRelease)
	fi
fi
./gradlew "${gradle_args[@]}" "${targets[@]}"

sign_apk () {
	local in_apk=$1
	local out_apk=$2
	local args=(--ks "${ANDROID_SIGNING_KEY}")
	[ -n "$ANDROID_SIGNING_ALIAS" ] && args+=(--ks-key-alias "$ANDROID_SIGNING_ALIAS")
	[ -n "$ANDROID_SIGNING_STORE_PASSWORD" ] && args+=(--ks-pass "pass:${ANDROID_SIGNING_STORE_PASSWORD}")
	[ -n "$ANDROID_SIGNING_KEY_PASSWORD" ] && args+=(--key-pass "pass:${ANDROID_SIGNING_KEY_PASSWORD}")
	"$apksigner" sign "${args[@]}" --in "$in_apk" --out "$out_apk"
}

if [ -n "$ANDROID_SIGNING_KEY" ]; then
	cd "${MPV_ANDROID}/app/build/outputs/apk"
	apksigner=${ANDROID_HOME}/build-tools/${v_sdk_build_tools}/apksigner
	if [ -n "$ci_default_arm64_release" ]; then
		release_apk=default/release/app-default-arm64-v8a-release-unsigned.apk
		signed_release_apk=${release_apk/-unsigned/-signed}
		sign_apk "$release_apk" "$signed_release_apk"
		rm -f "$release_apk"
	else
		for v in default api29; do
			pushd $v
			# sign the universal debug APK
			sign_apk debug/app-$v-universal-debug.apk debug/app-$v-universal-debug-signed.apk
			# but all of the release APKs
			for apk in release/*-unsigned.apk; do
				sign_apk "$apk" "${apk/-unsigned/-signed}"
			done
			popd
		done
		# and the bundle
		cd ../bundle
		if [ -n "$BUNDLE" ]; then
			if [ -z "$ANDROID_SIGNING_ALIAS" ]; then
				echo >&2 "Error: ANDROID_SIGNING_ALIAS must be set to use jarsigner"
				exit 1
			fi
			pushd defaultRelease
			jarsigner -keystore "${ANDROID_SIGNING_KEY}" -signedjar \
				app-default-release-signed.aab app-default-release.aab \
				"${ANDROID_SIGNING_ALIAS}"
			popd
		fi
	fi
fi
