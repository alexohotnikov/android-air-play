#!/usr/bin/env bash
# Static libcrypto for the TV's 32-bit ARM userspace. Output: third_party/openssl-android/<abi>/{lib,include}.
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/27.2.12479018}"
API=26
SRC=third_party/openssl-3.0.15
for ABI in armeabi-v7a; do
  case $ABI in armeabi-v7a) TARGET=android-arm ;; arm64-v8a) TARGET=android-arm64 ;; esac
  OUT="$PWD/third_party/openssl-android/$ABI"
  [ -f "$OUT/lib/libcrypto.a" ] && { echo "$ABI: already built"; continue; }
  export ANDROID_NDK_ROOT="$NDK"
  export PATH="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
  (cd "$SRC" && make distclean >/dev/null 2>&1 || true
   ./Configure "$TARGET" -D__ANDROID_API__=$API no-shared no-tests no-ui-console no-engine no-dso --prefix="$OUT" >/dev/null
   make -j"$(sysctl -n hw.ncpu)" build_libs >/dev/null
   mkdir -p "$OUT/lib" && cp libcrypto.a "$OUT/lib/" && rm -rf "$OUT/include" && cp -R include "$OUT/include")
  echo "$ABI: $(stat -f %z "$OUT/lib/libcrypto.a") bytes"
done
