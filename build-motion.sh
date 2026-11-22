#!/usr/bin/env bash
# Builds and signs the optional GeoshiftMotion companion Xposed module.
set -euo pipefail
cd "$(dirname "$0")"
WORKSPACE="$(cd .. && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$WORKSPACE/android/sdk}"
BT="$ANDROID_HOME/build-tools/37.0.0"
KEYS="${PORTAL_KEYS:-$WORKSPACE/signing}"
VERSION=$(grep -oP 'versionName = "\K[^"]+' motion/build.gradle.kts)
OUT="GeoshiftMotion-$VERSION.apk"

./gradlew --no-daemon -q :motion:assembleRelease
TMP=$(mktemp -d)
cp motion/build/outputs/apk/release/motion-release-unsigned.apk "$TMP/unsigned.apk"
"$BT/zipalign" -c -p 4 "$TMP/unsigned.apk"
sudo env PW="$(sudo cat "$KEYS/password.txt")" "$BT/apksigner" sign \
  --ks "$KEYS/portal-independent.p12" --ks-key-alias portal-independent \
  --ks-pass env:PW --key-pass env:PW --out "$TMP/$OUT" "$TMP/unsigned.apk"
sudo chown "$(id -u):$(id -g)" "$TMP/$OUT"
mkdir -p "$WORKSPACE/releases"
OUT_PATH="$WORKSPACE/releases/$OUT"
mv "$TMP/$OUT" "$OUT_PATH"
rm -rf "$TMP"
"$BT/apksigner" verify --print-certs "$OUT_PATH" | head -2
sha256sum "$OUT_PATH"
