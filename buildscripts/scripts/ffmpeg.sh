#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf _build$ndk_suffix
	exit 0
else
	exit 255
fi

# FFmpeg n8.0.1 rejects malformed VPS extensions emitted by older Apple
# VideoToolbox HEVC-alpha encoders. Backport upstream commit eedf8f0165fe6523
# so the already parsed alpha-layer description is kept when the unsupported
# tail of the extension is encountered.
#
# Keep this idempotent because the same source tree can be built for multiple
# Android ABIs, and non-CI builds may already use an FFmpeg revision containing
# the upstream fix.
if ! grep -q 'Broken VPS extension, treating as alpha video' libavcodec/hevc/ps.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/libavcodec/hevc/ps.c b/libavcodec/hevc/ps.c
index 46b38564..10c9a361 100644
--- a/libavcodec/hevc/ps.c
+++ b/libavcodec/hevc/ps.c
@@ -613,2 +613,5 @@ static int decode_vps_ext(GetBitContext *gb, AVCodecContext *avctx, HEVCVPS *vps
-    if (vps->num_output_layer_sets != 2)
-        return AVERROR_INVALIDDATA;
+    if (vps->num_output_layer_sets != 2) {
+        av_log(avctx, AV_LOG_WARNING,
+               "Unsupported num_output_layer_sets: %d\n", vps->num_output_layer_sets);
+        return AVERROR_PATCHWELCOME;
+    }
@@ -680 +683 @@ static int decode_vps_ext(GetBitContext *gb, AVCodecContext *avctx, HEVCVPS *vps
-        return AVERROR_INVALIDDATA;
+        return AVERROR_PATCHWELCOME;
@@ -898,2 +901,17 @@ int ff_hevc_decode_nal_vps(GetBitContext *gb, AVCodecContext *avctx,
-            vps->nb_layers = 1;
-            av_log(avctx, AV_LOG_WARNING, "Ignoring unsupported VPS extension\n");
+            /* If alpha layer info was already parsed, preserve it for alpha decoding */
+            if (!(avctx->err_recognition & (AV_EF_BITSTREAM | AV_EF_COMPLIANT)) &&
+                vps->nb_layers == 2 &&
+                vps->layer_id_in_nuh[1] &&
+                (vps->scalability_mask_flag & HEVC_SCALABILITY_AUXILIARY)) {
+                av_log(avctx, AV_LOG_WARNING,
+                       "Broken VPS extension, treating as alpha video\n");
+                /* If alpha layer has no direct dependency on base layer,
+                 * assume poc_lsb_not_present for the alpha layer, so that
+                 * IDR slices on that layer won't read pic_order_cnt_lsb.
+                 * This matches the behavior of Apple VideoToolbox encoders. */
+                if (!vps->num_direct_ref_layers[1])
+                    vps->poc_lsb_not_present |= 1 << 1;
+            } else {
+                vps->nb_layers = 1;
+                av_log(avctx, AV_LOG_WARNING, "Ignoring unsupported VPS extension\n");
+            }
PATCH
fi

mkdir -p _build$ndk_suffix
cd _build$ndk_suffix

cpu=armv7-a
[[ "$ndk_triple" == "aarch64"* ]] && cpu=armv8-a
[[ "$ndk_triple" == "x86_64"* ]] && cpu=generic
[[ "$ndk_triple" == "i686"* ]] && cpu="i686 --disable-asm"

cpuflags=
[[ "$ndk_triple" == "arm"* ]] && cpuflags="$cpuflags -mfpu=neon -mcpu=cortex-a8"

args=(
	--target-os=android --enable-cross-compile
	--cross-prefix=$ndk_triple- --cc=$CC --pkg-config=pkg-config --nm=llvm-nm
	--arch=${ndk_triple%%-*} --cpu=$cpu
	--extra-cflags="-I$prefix_dir/include $cpuflags" --extra-ldflags="-L$prefix_dir/lib"

	--enable-{jni,mediacodec,mbedtls,libdav1d,libxml2} --disable-vulkan
	--disable-static --enable-shared --enable-{gpl,version3}

	# disable unneeded parts
	--disable-{stripping,doc,programs}
	# to keep the build lean we disable some feature quite aggressively:
	# - muxers, encoders: mpv-android does not have any way to use these
	# - devices: no practical use on Android
	--disable-{muxers,encoders,devices}
	# useful to taking screenshots
	--enable-encoder=mjpeg,png
	# useful for the `dump-cache` command
	--enable-muxer=mov,matroska,mpegts
)
../configure "${args[@]}"

make -j$cores
make DESTDIR="$prefix_dir" install
