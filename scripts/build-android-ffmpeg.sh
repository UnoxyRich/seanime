#!/usr/bin/env bash
set -euo pipefail

FFMPEG_VERSION="8.1.3"
X264_COMMIT="b35605ace3ddf7c1a5d67a2eb553f034aef41d55"
FFMPEG_SIGNING_FINGERPRINT="FCF986EA15E6E293A5644F10B4322F04D67658D8"
ANDROID_API="23"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_CONFIG_CHECKSUM="$(cksum "$REPO_ROOT/scripts/build-android-ffmpeg.sh" | awk '{print $1}')"
JNI_ROOT="$REPO_ROOT/androidtv/app/src/main/jniLibs"
METADATA_ROOT="$REPO_ROOT/androidtv/app/src/main/assets/ffmpeg"
NDK_ROOT="$(printenv ANDROID_NDK_HOME || true)"

if [[ -z "$NDK_ROOT" ]]; then
    SDK_ROOT="$(printenv ANDROID_HOME || printenv ANDROID_SDK_ROOT || true)"
    NDK_ROOT="$SDK_ROOT/ndk/27.2.12479018"
fi
if [[ ! -d "$NDK_ROOT/toolchains/llvm/prebuilt" ]]; then
    echo "Android NDK not found; set ANDROID_NDK_HOME to NDK 27.2.12479018." >&2
    exit 1
fi

for required in curl gpg gpgv git make nasm patch pkg-config tar; do
    command -v "$required" >/dev/null || {
        echo "Missing build tool: $required" >&2
        exit 1
    }
done

case "$(uname -s)" in
    Darwin) NDK_HOST="darwin-x86_64" ;;
    Linux) NDK_HOST="linux-x86_64" ;;
    *) echo "Unsupported NDK host: $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "NDK toolchain not found: $TOOLCHAIN" >&2
    exit 1
fi

JOBS="$(printenv SEANIME_ANDROID_BUILD_JOBS || echo 4)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/seanime-android-ffmpeg.XXXXXX")"
GNUPGHOME="$WORK_DIR/gnupg"
mkdir -m 700 "$GNUPGHOME"
export GNUPGHOME
trap 'find "$WORK_DIR" -depth -delete' EXIT

FFMPEG_ARCHIVE="$WORK_DIR/ffmpeg-$FFMPEG_VERSION.tar.xz"
FFMPEG_SIGNATURE="$FFMPEG_ARCHIVE.asc"
curl -fsSL --retry 3 "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" -o "$FFMPEG_ARCHIVE"
curl -fsSL --retry 3 "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz.asc" -o "$FFMPEG_SIGNATURE"
curl -fsSL --retry 3 https://ffmpeg.org/ffmpeg-devel.asc -o "$WORK_DIR/ffmpeg-devel.asc"
# Verification is public-key-only. Avoid importing into a key database: that
# consults gpg-agent even though no signing key is needed and fails on sandboxed
# build hosts without agent sockets. gpgv validates against this isolated keyring;
# the exact pinned primary-key fingerprint remains mandatory below.
FFMPEG_KEYRING="$WORK_DIR/ffmpeg-devel.gpg"
if ! gpg --batch --yes --dearmor --output "$FFMPEG_KEYRING" "$WORK_DIR/ffmpeg-devel.asc" >"$WORK_DIR/key-import.txt" 2>&1; then
    cat "$WORK_DIR/key-import.txt" >&2
    echo "Could not decode the FFmpeg release signing key." >&2
    exit 1
fi
if ! gpgv --keyring "$FFMPEG_KEYRING" --status-fd 1 "$FFMPEG_SIGNATURE" "$FFMPEG_ARCHIVE" >"$WORK_DIR/signature-status.txt" 2>&1; then
    cat "$WORK_DIR/signature-status.txt" >&2
    echo "FFmpeg source signature verification failed." >&2
    exit 1
fi
if ! awk -v fingerprint="$FFMPEG_SIGNING_FINGERPRINT" '$2 == "VALIDSIG" && $3 == fingerprint { verified = 1 } END { exit !verified }' "$WORK_DIR/signature-status.txt"; then
    echo "FFmpeg source was signed by an unexpected key." >&2
    exit 1
fi
echo "Verified FFmpeg $FFMPEG_VERSION source signature."

X264_REPOSITORY="$WORK_DIR/x264-repository"
echo "Fetching pinned x264 source."
git init --quiet "$X264_REPOSITORY"
git -C "$X264_REPOSITORY" remote add origin https://code.videolan.org/videolan/x264.git
git -C "$X264_REPOSITORY" fetch --quiet --depth=1 origin "$X264_COMMIT"
git -C "$X264_REPOSITORY" checkout --quiet --detach FETCH_HEAD

ANDROID_ABIS="$(printenv SEANIME_ANDROID_ABIS || echo 'arm64-v8a x86_64')"
read -r -a ABIS <<< "$ANDROID_ABIS"
for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a)
            TRIPLE="aarch64-linux-android"
            X264_HOST="aarch64-linux-android"
            FFMPEG_ARCH="aarch64"
            FFMPEG_CPU="armv8-a"
            ;;
        x86_64)
            TRIPLE="x86_64-linux-android"
            X264_HOST="x86_64-linux-android"
            FFMPEG_ARCH="x86_64"
            FFMPEG_CPU="x86-64"
            ;;
        *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;;
    esac

    PREFIX="$WORK_DIR/$ABI/prefix"
    X264_SOURCE="$WORK_DIR/$ABI/x264-source"
    FFMPEG_SOURCE="$WORK_DIR/$ABI/ffmpeg-source"
    OUTPUT="$JNI_ROOT/$ABI"
    ABI_METADATA="$METADATA_ROOT/$ABI"
    BIN="$TOOLCHAIN/bin"
    SYSROOT="$TOOLCHAIN/sysroot"
    CC="$BIN/$TRIPLE$ANDROID_API-clang"
    AR="$BIN/llvm-ar"
    RANLIB="$BIN/llvm-ranlib"
    STRIP="$BIN/llvm-strip"
    NM="$BIN/llvm-nm"
    if [[ ! -x "$CC" ]]; then
        echo "Android compiler not found: $CC" >&2
        exit 1
    fi

    mkdir -p "$PREFIX" "$X264_SOURCE" "$FFMPEG_SOURCE"
    git -C "$X264_REPOSITORY" archive "$X264_COMMIT" | tar -xf - -C "$X264_SOURCE"
    (
        cd "$X264_SOURCE"
        # Some Android emulator kernels advertise SVE2 without usable SVE.
        # SVE2 instructions require the base SVE registers and kernel support;
        # retain all acceleration on hosts that expose both capability flags.
        patch --batch --fuzz=0 -p1 <<'PATCH'
--- a/common/cpu.c
+++ b/common/cpu.c
@@ -486,7 +486,7 @@
         flags |= X264_CPU_I8MM;
     if ( hwcap & HWCAP_AARCH64_SVE )
         flags |= X264_CPU_SVE;
-    if ( hwcap2 & HWCAP2_AARCH64_SVE2 )
+    if ( (hwcap & HWCAP_AARCH64_SVE) && (hwcap2 & HWCAP2_AARCH64_SVE2) )
         flags |= X264_CPU_SVE2;
 
     return flags;
PATCH
        CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="--sysroot=$SYSROOT -fPIC" \
            ./configure \
                --prefix="$PREFIX" \
                --host="$X264_HOST" \
                --enable-static \
                --enable-pic \
                --disable-cli \
                --disable-opencl
        make -s -j"$JOBS"
        make -s install
    )

    tar -xJf "$FFMPEG_ARCHIVE" --strip-components=1 -C "$FFMPEG_SOURCE"
    (
        cd "$FFMPEG_SOURCE"
        patch --batch --fuzz=0 -p1 <<'PATCH'
--- a/libavutil/aarch64/cpu.c
+++ b/libavutil/aarch64/cpu.c
@@ -47,7 +47,7 @@
         flags |= AV_CPU_FLAG_DOTPROD;
     if (hwcap & HWCAP_AARCH64_SVE)
         flags |= AV_CPU_FLAG_SVE;
-    if (hwcap2 & HWCAP2_AARCH64_SVE2)
+    if ((hwcap & HWCAP_AARCH64_SVE) && (hwcap2 & HWCAP2_AARCH64_SVE2))
         flags |= AV_CPU_FLAG_SVE2;
     if (hwcap2 & HWCAP2_AARCH64_I8MM)
         flags |= AV_CPU_FLAG_I8MM;
PATCH
        export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
        export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
        if ! ./configure \
                --prefix="$PREFIX/ffmpeg" \
                --target-os=android \
                --arch="$FFMPEG_ARCH" \
                --cpu="$FFMPEG_CPU" \
                --enable-cross-compile \
                --sysroot="$SYSROOT" \
                --cc="$CC" \
                --ar="$AR" \
                --ranlib="$RANLIB" \
                --nm="$NM" \
                --strip="$STRIP" \
                --pkg-config-flags=--static \
                --extra-cflags="-O2 -fPIC -I$PREFIX/include" \
                --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
                --enable-static \
                --disable-shared \
                --enable-gpl \
                --enable-libx264 \
                --enable-jni \
                --enable-mediacodec \
                --disable-autodetect \
                --disable-avdevice \
                --disable-doc \
                --disable-debug \
                --disable-ffplay; then
            if [[ -f ffbuild/config.log ]]; then
                cat ffbuild/config.log >&2
            fi
            exit 1
        fi
        make -s -j"$JOBS" ffmpeg ffprobe
    )

    mkdir -p "$OUTPUT" "$ABI_METADATA"
    install -m 0755 "$FFMPEG_SOURCE/ffmpeg" "$OUTPUT/libffmpeg.so"
    install -m 0755 "$FFMPEG_SOURCE/ffprobe" "$OUTPUT/libffprobe.so"
    "$STRIP" --strip-unneeded "$OUTPUT/libffmpeg.so" "$OUTPUT/libffprobe.so"
    printf 'FFmpeg %s\nx264 %s\nAndroid ABI %s\nBuild config %s\n' \
        "$FFMPEG_VERSION" "$X264_COMMIT" "$ABI" "$BUILD_CONFIG_CHECKSUM" > "$ABI_METADATA/version"
    echo "Built Android FFmpeg tools for $ABI"
    # Each ABI is installed before the next build starts. Release its temporary
    # object files so a two-ABI build needs only one ABI's scratch space.
    find "$WORK_DIR/$ABI" -depth -delete
done

NOTICE="$METADATA_ROOT/NOTICE.txt"
mkdir -p "$METADATA_ROOT"
cat > "$NOTICE" <<EOF
Seanime TV includes FFmpeg $FFMPEG_VERSION, built from the official FFmpeg source
release with GPL support and x264 commit $X264_COMMIT. FFmpeg source and release
signatures are available from https://ffmpeg.org/download.html. x264 source is
available from https://code.videolan.org/videolan/x264. The app and these tools
are distributed under their applicable GPL-compatible terms.

Both sources include the SVE prerequisite check for SVE2 detection shown in
scripts/build-android-ffmpeg.sh in the Seanime TV source distribution.
EOF
