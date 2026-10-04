#!/usr/bin/env bash
set -e

VERSION="${1:-1.3.0}"
TARGET_APK="yandex-books-lite-v${VERSION}.apk"

echo "=== Сборка Яндекс Книги Lite APK (Версия: $VERSION) ==="

export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
BUILD_TOOLS="$SDK_ROOT/build-tools/30.0.3"
PLATFORM="$SDK_ROOT/platforms/android-19/android.jar"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

mkdir -p build/classes build/lib

echo "[1/6] Генерация R.java через AAPT..."
"$BUILD_TOOLS/aapt" package -m \
  -J app/src/main/java \
  -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res \
  -I "$PLATFORM"

echo "[2/6] Компиляция Java исходников..."
"$JAVA_HOME/bin/javac" \
  -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -cp "libs/okhttp-3.12.13.jar:libs/okio-1.15.0.jar:libs/zxing-core-3.4.1.jar:libs/conscrypt.jar" \
  -d build/classes \
  $(find app/src/main/java -name "*.java")

echo "[3/6] Конвертация в Dalvik DEX (D8)..."
"$BUILD_TOOLS/d8" \
  --min-api 17 \
  --lib "$PLATFORM" \
  --output build/ \
  $(find build/classes -name "*.class") libs/*.jar

echo "[4/6] Упаковка ресурсов и манифеста в APK..."
"$BUILD_TOOLS/aapt" package -f \
  -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res \
  -I "$PLATFORM" \
  -F build/unaligned.apk

cd build
"$BUILD_TOOLS/aapt" add unaligned.apk classes.dex
mkdir -p lib
cp -r ../libs/jni/* lib/
"$BUILD_TOOLS/aapt" add unaligned.apk lib/armeabi-v7a/libconscrypt_jni.so lib/x86/libconscrypt_jni.so
cd ..

echo "[5/6] Выравнивание байтов (zipalign)..."
"$BUILD_TOOLS/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk

echo "[6/6] Удаление старых версий и подпись нового APK (v1 + v2 схемы)..."
rm -f "$PROJECT_DIR"/yandex-books-lite*.apk*

if [ ! -f debug.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkey -v -keystore debug.keystore \
    -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$TARGET_APK" \
  build/aligned.apk

echo "=== Проверка цифровой подписи ==="
"$BUILD_TOOLS/apksigner" verify --verbose "$TARGET_APK"

echo "🎉 Готово! Собрано: $PROJECT_DIR/$TARGET_APK"
ls -lh "$TARGET_APK"
