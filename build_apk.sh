#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION="${1:-$(cat "$PROJECT_DIR/VERSION" 2>/dev/null || echo "1.5.8")}"
TARGET_APK="yandex-books-lite-v${VERSION}.apk"

echo "=== Сборка Яндекс Книги APK (Версия: $VERSION) ==="

if [ -d "/opt/android-sdk" ]; then
    SDK_ROOT="/opt/android-sdk"
elif [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
else
    SDK_ROOT="${ANDROID_HOME:-/opt/android-sdk}"
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    elif [ -d "/opt/homebrew/opt/openjdk@17" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
    fi
fi
if [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME/bin" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
BUILD_TOOLS="$SDK_ROOT/build-tools/30.0.3"
PLATFORM="$SDK_ROOT/platforms/android-19/android.jar"

cd "$PROJECT_DIR"

rm -rf build/classes build/lib build/classes.dex
mkdir -p build/classes build/lib
rm -f "$PROJECT_DIR"/build/*.apk*

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

echo "[2.5/6] Нормализация байткода Java 21 для совместимости с D8..."
python3 -c "
import os
for root, dirs, files in os.walk('build/classes'):
    for f in files:
        if f.endswith('.class'):
            p = os.path.join(root, f)
            with open(p, 'rb') as fp:
                d = fp.read()
            if b'MethodParameters' in d:
                d = d.replace(b'MethodParameters', b'XethodParameters')
                with open(p, 'wb') as fp:
                    fp.write(d)
"

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
  -A app/src/main/assets \
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

echo "[6/6] Подпись нового APK (v1 + v2 схемы)..."
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

# Очистка промежуточных APK файлов из build/
rm -f build/unaligned.apk build/aligned.apk

echo "=== Проверка цифровой подписи ==="
"$BUILD_TOOLS/apksigner" verify --verbose "$TARGET_APK"

echo "🎉 Готово! Собрано: $PROJECT_DIR/$TARGET_APK"
ls -lh "$TARGET_APK"
