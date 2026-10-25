#!/usr/bin/env bash
# Builds the Dat Boi APK without Gradle, using the Android SDK command line tools.
#
# Works with either a regular Android SDK (ANDROID_HOME, build-tools + platform) or
# the Ubuntu/Debian packages:
#   apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 openjdk-17-jdk
#
# Output: build/DatBoi.apk (signed with $KEYSTORE, or a generated debug key).
# ADMIN=1 builds the private admin APK instead: build/admin/DatBoi-admin.apk, package
# com.rektstudios.daboi.admin (installs next to the game), with Flavor.ADMIN on.
#
# The game is pure Java + OpenGL ES 2.0 with no native libraries, so the APK runs as
# a 64-bit process on arm64-v8a / x86_64 devices (including 64-bit-only phones).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
ADMIN="${ADMIN:-0}"
NAME=DatBoi
[[ "$ADMIN" == 1 ]] && { OUT="$ROOT/build/admin"; NAME=DatBoi-admin; }

find_tool() {
  local name="$1"
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    local hit
    hit="$(ls -d "$ANDROID_HOME"/build-tools/*/"$name" 2>/dev/null | sort -V | tail -n1 || true)"
    [[ -n "$hit" ]] && { echo "$hit"; return; }
  fi
  command -v "$name" || true
}

ANDROID_JAR="${ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" ]]; then
  for c in "${ANDROID_HOME:-/nonexistent}"/platforms/android-*/android.jar /usr/lib/android-sdk/platforms/android-*/android.jar; do
    [[ -f "$c" ]] && ANDROID_JAR="$c"
  done
fi
[[ -f "$ANDROID_JAR" ]] || { echo "android.jar not found (set ANDROID_JAR)"; exit 1; }

AAPT="$(find_tool aapt)"
ZIPALIGN="$(find_tool zipalign)"
APKSIGNER="$(find_tool apksigner)"
D8="$(find_tool d8)"
DX="$(command -v dalvik-exchange || find_tool dx)"
for t in AAPT ZIPALIGN APKSIGNER; do
  [[ -n "${!t}" ]] || { echo "missing tool: $t"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

RES="$APP/res"
JAVA="$APP/java"
EXTRA=()
if [[ "$ADMIN" == 1 ]]; then
  cp -r "$APP/res" "$OUT/res"
  sed -i 's|<string name="app_name">.*</string>|<string name="app_name">Dat Boi ADMIN</string>|' "$OUT/res/values/strings.xml"
  cp -r "$APP/java" "$OUT/java"
  sed -i 's/static final boolean ADMIN = false;/static final boolean ADMIN = true;/' "$OUT/java/com/rektstudios/daboi/Flavor.java"
  grep -q 'ADMIN = true' "$OUT/java/com/rektstudios/daboi/Flavor.java"
  RES="$OUT/res"
  JAVA="$OUT/java"
  EXTRA=(--rename-manifest-package com.rektstudios.daboi.admin)
fi

echo "== resources"
"$AAPT" package -f -m \
  -M "$APP/AndroidManifest.xml" \
  -S "$RES" \
  -A "$APP/assets" \
  -I "$ANDROID_JAR" \
  -J "$OUT/gen" \
  -0 ogg -0 bin -0 mp4 "${EXTRA[@]}" \
  -F "$OUT/unaligned.apk"

echo "== java"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find "$OUT/gen" "$JAVA" -name '*.java')

echo "== dex"
if [[ -n "$D8" ]]; then
  "$D8" --release --min-api 21 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
else
  "$DX" --dex --min-sdk-version=21 --output="$OUT/dex/classes.dex" "$OUT/classes"
fi
(cd "$OUT/dex" && "$AAPT" add "$OUT/unaligned.apk" classes.dex >/dev/null)

echo "== align + sign"
"$ZIPALIGN" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
# a fixed debug key, so new builds install as updates of older ones
KEYSTORE="${KEYSTORE:-$ROOT/tools/debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-datboi}"
KEY_PASS="${KEY_PASS:-android}"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Dat Boi debug" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --min-sdk-version 21 \
  --out "$OUT/$NAME.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/$NAME.apk"
rm -f "$OUT/unaligned.apk" "$OUT/aligned.apk"
echo "built $OUT/$NAME.apk"
