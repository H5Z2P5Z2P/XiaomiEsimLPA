#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$MODULE_DIR/src/main"
BUILD_DIR="$MODULE_DIR/build"
STUBS_DIR="$MODULE_DIR/stubs/src"
KEYSTORE_DIR="$MODULE_DIR/.signing"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Volumes/JZ/Android/sdk}}"

pick_build_tools() {
  local version
  for version in 37.0.0 36.1.0 36.0.0 33.0.1 28.0.3; do
    if [[ -x "$SDK_ROOT/build-tools/$version/aapt2" && -x "$SDK_ROOT/build-tools/$version/d8" ]]; then
      printf '%s' "$version"
      return 0
    fi
  done
  return 1
}

if [[ ! -d "$SDK_ROOT/build-tools" ]]; then
  printf 'Android build-tools directory not found: %s\n' "$SDK_ROOT" >&2
  exit 1
fi

BUILD_TOOLS_VERSION="$(pick_build_tools)"
AAPT2="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/aapt2"
D8="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/d8"
ZIPALIGN="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign"
APKSIGNER="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"
ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"
UNSIGNED_APK="$BUILD_DIR/apk/unsigned.apk"
ALIGNED_APK="$BUILD_DIR/apk/aligned.apk"
SIGNED_APK="$BUILD_DIR/miui-esim-slot-hook-debug.apk"
KEYSTORE="$KEYSTORE_DIR/debug.keystore"

if [[ ! -f "$ANDROID_JAR" ]]; then
  printf 'Android platform jar not found: %s\n' "$ANDROID_JAR" >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/stub-classes" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/apk"
mkdir -p "$KEYSTORE_DIR"

javac -encoding UTF-8 -source 8 -target 8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/stub-classes" \
  $(find "$STUBS_DIR" -name '*.java' | sort)

javac -encoding UTF-8 -source 8 -target 8 \
  -classpath "$ANDROID_JAR:$BUILD_DIR/stub-classes" \
  -d "$BUILD_DIR/classes" \
  $(find "$SRC_DIR/java" -name '*.java' | sort)

"$D8" --lib "$ANDROID_JAR" --min-api 33 --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name '*.class' | sort)

"$AAPT2" link \
  --manifest "$SRC_DIR/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  -A "$SRC_DIR/assets" \
  -o "$UNSIGNED_APK"

zip -qj "$UNSIGNED_APK" "$BUILD_DIR/dex/classes.dex"

"$ZIPALIGN" -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 >/dev/null 2>&1
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"

printf 'Built APK: %s\n' "$SIGNED_APK"
