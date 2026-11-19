#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
base=https://github.com/CYQawa/yunx_gopeed_build/releases/download/1
while read -r abi digest; do
  archive="$tmp/libgopeed-$abi.aar"
  curl --fail --location --retry 3 --max-time 180 "$base/libgopeed-$abi.aar" -o "$archive"
  printf '%s  %s\n' "$digest" "$archive" | sha256sum --check --status
  mkdir -p "build/gopeed/jni/$abi"
  unzip -p "$archive" "jni/$abi/libgojni.so" > "build/gopeed/jni/$abi/libgojni.so"
  test -s "build/gopeed/jni/$abi/libgojni.so"
done <<'DIGESTS'
arm64-v8a 02273a5970602e3e803a2cd5d6c17f610c29f337e968799a9e26df618d661925
armeabi-v7a 499935a4a08b4d3a0518d42306baf0624ae1ddbea4811aa37ca919b808df64fc
x86 ba846179a75902b732e4147dd09fd4cd4a69df8237750c85e41366f1ff416f9a
x86_64 e910df9faef60541371182e6b7c92ca2c2ce63cadf076a2bf6d4928a546566d4
DIGESTS
