#!/usr/bin/env bash
# 不用 Gradle：aapt2 → javac → d8 → zipalign → apksigner 直接产出可安装的 release APK。
# 依赖 ./setup.sh 装好的 toolchain/。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="$ROOT/toolchain"
export JAVA_HOME="$TC/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

SDK="$TC/android-sdk"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
BUILD="$ROOT/build/apk"
DIST="$ROOT/dist"
APP="$ROOT/app/src/main"

VERSION_CODE=8
VERSION_NAME="1.2.4"
APK_NAME="china-bt-map-${VERSION_NAME}.apk"

KEYSTORE="$ROOT/keystore/release.jks"
KS_PASS="chinamap123"
KEY_ALIAS="chinamap"

for f in "$BUILD_TOOLS/aapt2" "$PLATFORM" "$JAVA_HOME/bin/javac"; do
  [ -e "$f" ] || { echo "缺少 $f，请先运行 ./setup.sh" >&2; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "==> aapt2 link"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

echo "==> javac"
find "$APP/java" -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name 'R.java' >> "$BUILD/sources.txt"
javac --release 11 -Xlint:-options -encoding UTF-8 \
  -cp "$PLATFORM" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> d8"
find "$BUILD/classes" -name '*.class' -print0 \
  | xargs -0 "$BUILD_TOOLS/d8" --release --min-api 24 --lib "$PLATFORM" --output "$BUILD/dex"

echo "==> 打包 dex"
(cd "$BUILD/dex" && zip -q "$BUILD/base.apk" classes.dex)

echo "==> zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> 生成本地签名证书（仅用于自签名安装）"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=ChinaBtMap, OU=Local, O=Local, L=Beijing, ST=Beijing, C=CN" \
    >/dev/null
fi

echo "==> apksigner"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$DIST/$APK_NAME" "$BUILD/aligned.apk"

echo "==> 校验"
"$BUILD_TOOLS/apksigner" verify --print-certs "$DIST/$APK_NAME" > "$BUILD/verify.txt"
head -6 "$BUILD/verify.txt"
"$BUILD_TOOLS/aapt2" dump badging "$DIST/$APK_NAME" | grep -E "^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity|uses-permission)" || true
unzip -l "$DIST/$APK_NAME" | grep -E "classes.dex|china_map.bin|resources.arsc|AndroidManifest" || true

echo
echo "APK: $DIST/$APK_NAME"
ls -lh "$DIST/$APK_NAME"
echo "sha256: $(shasum -a 256 "$DIST/$APK_NAME" | awk '{print $1}')"
