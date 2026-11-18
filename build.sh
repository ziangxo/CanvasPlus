#!/usr/bin/env bash
#
# 依赖：Android SDK（build-tools + platform 36）、JDK 17 以上。
# 路径默认取 ANDROID_HOME / JAVA_HOME，可以用环境变量覆盖：
#   ANDROID_HOME=/path/to/sdk  JAVA_HOME=/path/to/jdk  bash build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
JDK="${JAVA_HOME:-$(dirname "$(dirname "$(command -v javac)")")}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)}"
PLATFORM="$SDK/platforms/android-36/android.jar"

if [ ! -f "$PLATFORM" ]; then
    echo "找不到 $PLATFORM" >&2
    echo "请安装 Android SDK Platform 36，或用 ANDROID_HOME 指定 SDK 位置" >&2
    exit 1
fi

# Windows（git bash / msys）下工具带 .exe 或 .bat，classpath 也用分号
pick() {
    for c in "$1" "$1.exe" "$1.bat"; do
        [ -f "$c" ] && { echo "$c"; return; }
    done
    echo "$1"
}
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
    *) SEP=":" ;;
esac

AAPT2=$(pick "$BT/aapt2")
D8=$(pick "$BT/d8")
ZIPALIGN=$(pick "$BT/zipalign")
APKSIGNER=$(pick "$BT/apksigner")
JAVAC=$(pick "$JDK/bin/javac")
JAR=$(pick "$JDK/bin/jar")
KEYTOOL=$(pick "$JDK/bin/keytool")

KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KS" ]; then
    echo "== 生成 debug keystore =="
    mkdir -p "$(dirname "$KS")"
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

rm -rf build dist
mkdir -p build/classes build/dex dist

echo "== aapt2 link =="
"$AAPT2" link \
    -o build/base.apk \
    -I "$PLATFORM" \
    --manifest app/src/main/AndroidManifest.xml \
    -A app/src/main/assets \
    --min-sdk-version 35 \
    --target-sdk-version 36

echo "== javac =="
find app/src/main/java -name "*.java" > build/sources.txt
"$JAVAC" -encoding UTF-8 -nowarn --release 17 \
    -classpath "$PLATFORM${SEP}libs/xposed-api-82.jar" \
    -d build/classes @"build/sources.txt"

echo "== d8 =="
"$D8" --release --min-api 35 --lib "$PLATFORM" \
    --output build/dex $(find build/classes -name "*.class")

echo "== package =="
cp build/base.apk build/unsigned.apk
(cd build && "$JAR" uf unsigned.apk -C dex classes.dex)
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk

echo "== sign =="
"$APKSIGNER" sign \
    --ks "$KS" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out dist/CanvasPlus.apk \
    build/aligned.apk

ls -lh dist/CanvasPlus.apk
echo "done"
