#!/bin/bash
# 게임부스터 APK 빌드 (Gradle 없이 Android SDK 도구로 직접 빌드)
# 사용: VERSION_CODE=101 VERSION_NAME=1.2 ./build.sh
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android}}"
BT_VER="${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
BT="$SDK/build-tools/$BT_VER"
PLAT="$SDK/platforms/android-34/android.jar"
[ -f "$PLAT" ] || PLAT="$(ls -d "$SDK"/platforms/android-3* | sort -V | tail -1)/android.jar"

VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1}"
KEYSTORE="${KEYSTORE:-gb.keystore}"
KS_PASS="${KEYSTORE_PASSWORD:-gamebooster}"
OUT="${OUT:-GameBooster.apk}"

echo "Build v$VERSION_NAME ($VERSION_CODE) with build-tools $BT_VER"
rm -rf build && mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res/
"$BT/aapt2" link -I "$PLAT" --manifest AndroidManifest.xml -A assets -o build/base.apk \
  --java build/gen build/res/*.flat --min-sdk-version 26 --target-sdk-version 33 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
javac --release 8 -nowarn -encoding UTF-8 -classpath "$PLAT:$(ls libs/*.jar | tr '\n' ':')" -d build/classes \
  build/gen/com/thkim/gamebooster/R.java src/com/thkim/gamebooster/*.java
"$BT/d8" --min-api 26 --lib "$PLAT" --output build/dex $(find build/classes -name '*.class') libs/*.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$OUT" build/aligned.apk
"$BT/apksigner" verify "$OUT"
echo "OK -> $OUT"
