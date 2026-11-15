FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_AVD_HOME=/opt/android-avd \
    ANDROID_USER_HOME=/opt/android-user \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:${PATH}

# Runtime dependencies required by Google Android Emulator (QEMU + SwiftShader + headless Qt) and adb
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    unzip \
    procps \
    openjdk-17-jre-headless \
    python3 \
    iproute2 \
    netcat-openbsd \
    libgl1 \
    libglib2.0-0 \
    libnss3 \
    libnspr4 \
    libfontconfig1 \
    libdbus-1-3 \
    libexpat1 \
    libx11-6 \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libxcomposite1 \
    libxcursor1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libxcb1 \
    libxcb-cursor0 \
    libxcb-icccm4 \
    libxcb-image0 \
    libxcb-keysyms1 \
    libxcb-randr0 \
    libxcb-render-util0 \
    libxcb-shape0 \
    libxcb-xfixes0 \
    libxcb-xinerama0 \
    libxcb-glx0 \
    libxkbcommon0 \
    libxkbcommon-x11-0 \
    libasound2 \
    libpulse0 \
    libbsd0 \
    libdrm2 \
    libgbm1 \
    libstdc++6 \
    zlib1g \
    && rm -rf /var/lib/apt/lists/*

# Install Android SDK Command-Line Tools cleanly inside container
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" "${ANDROID_AVD_HOME}" "${ANDROID_USER_HOME}" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-extract \
    && mv /tmp/cmdline-extract/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-extract

# Accept licenses and install platform-tools + emulator
RUN yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
        "platform-tools" \
        "emulator"

# Install Android 6.0 (API 23 — Onyx Boox Darwin 5) and Android 9.0 (API 28 — Onyx Boox Darwin 7)
RUN sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
        "platforms;android-23" \
        "system-images;android-23;default;x86_64" \
        "platforms;android-28" \
        "system-images;android-28;default;x86_64" \
    && rm -rf "${ANDROID_SDK_ROOT}/.temp"

# Pre-create Onyx Boox E-Ink AVD profiles inside the image (zero host clutter)
RUN echo "no" | avdmanager create avd -n onyx_darwin5_api23 \
        -k "system-images;android-23;default;x86_64" -b "default/x86_64" --force \
    && mkdir -p "${ANDROID_AVD_HOME}/onyx_darwin5_api23.avd" \
    && "${ANDROID_SDK_ROOT}/emulator/mksdcard" -l SDCARD 512M "${ANDROID_AVD_HOME}/onyx_darwin5_api23.avd/sdcard.img" \
    && printf '\n%s\n' \
        'hw.lcd.width=758' \
        'hw.lcd.height=1024' \
        'hw.lcd.density=212' \
        'hw.ramSize=1536' \
        'hw.cpu.ncore=4' \
        'hw.keyboard=yes' \
        'hw.sdCard=yes' \
        "sdcard.path=${ANDROID_AVD_HOME}/onyx_darwin5_api23.avd/sdcard.img" \
        'sdcard.size=512M' \
        'disk.dataPartition.size=2048M' \
        'hw.gpu.enabled=yes' \
        'hw.gpu.mode=swiftshader_indirect' \
        'fastboot.forceColdBoot=yes' \
        >> "${ANDROID_AVD_HOME}/onyx_darwin5_api23.avd/config.ini" \
    && echo "no" | avdmanager create avd -n onyx_darwin7_api28 \
        -k "system-images;android-28;default;x86_64" -b "default/x86_64" --force \
    && mkdir -p "${ANDROID_AVD_HOME}/onyx_darwin7_api28.avd" \
    && "${ANDROID_SDK_ROOT}/emulator/mksdcard" -l SDCARD 512M "${ANDROID_AVD_HOME}/onyx_darwin7_api28.avd/sdcard.img" \
    && printf '\n%s\n' \
        'hw.lcd.width=1072' \
        'hw.lcd.height=1448' \
        'hw.lcd.density=300' \
        'hw.ramSize=1536' \
        'hw.cpu.ncore=4' \
        'hw.keyboard=yes' \
        'hw.sdCard=yes' \
        "sdcard.path=${ANDROID_AVD_HOME}/onyx_darwin7_api28.avd/sdcard.img" \
        'sdcard.size=512M' \
        'disk.dataPartition.size=2048M' \
        'hw.gpu.enabled=yes' \
        'hw.gpu.mode=swiftshader_indirect' \
        'fastboot.forceColdBoot=yes' \
        >> "${ANDROID_AVD_HOME}/onyx_darwin7_api28.avd/config.ini"

# Install legacy target Android OS images matching primary Onyx Boox e-readers:
# - Android 4.2.2 Jelly Bean (API 17 — Darwin 1)
# - Android 4.4.4 KitKat (API 19 — Darwin 3 / 5 / 6)
RUN sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
        "platforms;android-17" \
        "system-images;android-17;default;x86" \
        "platforms;android-19" \
        "system-images;android-19;default;x86" \
    && rm -rf "${ANDROID_SDK_ROOT}/.temp" \
    && echo "no" | avdmanager create avd -n onyx_darwin1_api17 \
        -k "system-images;android-17;default;x86" -b "default/x86" --force \
    && mkdir -p "${ANDROID_AVD_HOME}/onyx_darwin1_api17.avd" \
    && "${ANDROID_SDK_ROOT}/emulator/mksdcard" -l SDCARD 512M "${ANDROID_AVD_HOME}/onyx_darwin1_api17.avd/sdcard.img" \
    && printf '\n%s\n' \
        'hw.lcd.width=758' \
        'hw.lcd.height=1024' \
        'hw.lcd.density=212' \
        'hw.ramSize=1024' \
        'hw.cpu.ncore=2' \
        'hw.keyboard=yes' \
        'hw.sdCard=yes' \
        "sdcard.path=${ANDROID_AVD_HOME}/onyx_darwin1_api17.avd/sdcard.img" \
        'sdcard.size=512M' \
        'disk.dataPartition.size=1024M' \
        'hw.gpu.enabled=yes' \
        'hw.gpu.mode=swiftshader_indirect' \
        'fastboot.forceColdBoot=yes' \
        >> "${ANDROID_AVD_HOME}/onyx_darwin1_api17.avd/config.ini" \
    && echo "no" | avdmanager create avd -n onyx_darwin5_api19 \
        -k "system-images;android-19;default;x86" -b "default/x86" --force \
    && mkdir -p "${ANDROID_AVD_HOME}/onyx_darwin5_api19.avd" \
    && "${ANDROID_SDK_ROOT}/emulator/mksdcard" -l SDCARD 512M "${ANDROID_AVD_HOME}/onyx_darwin5_api19.avd/sdcard.img" \
    && printf '\n%s\n' \
        'hw.lcd.width=758' \
        'hw.lcd.height=1024' \
        'hw.lcd.density=212' \
        'hw.ramSize=1024' \
        'hw.cpu.ncore=2' \
        'hw.keyboard=yes' \
        'hw.sdCard=yes' \
        "sdcard.path=${ANDROID_AVD_HOME}/onyx_darwin5_api19.avd/sdcard.img" \
        'sdcard.size=512M' \
        'disk.dataPartition.size=1024M' \
        'hw.gpu.enabled=yes' \
        'hw.gpu.mode=swiftshader_indirect' \
        'fastboot.forceColdBoot=yes' \
        >> "${ANDROID_AVD_HOME}/onyx_darwin5_api19.avd/config.ini" \
    && echo "no" | avdmanager create avd -n onyx_darwin6_api19 \
        -k "system-images;android-19;default;x86" -b "default/x86" --force \
    && mkdir -p "${ANDROID_AVD_HOME}/onyx_darwin6_api19.avd" \
    && "${ANDROID_SDK_ROOT}/emulator/mksdcard" -l SDCARD 512M "${ANDROID_AVD_HOME}/onyx_darwin6_api19.avd/sdcard.img" \
    && printf '\n%s\n' \
        'hw.lcd.width=1072' \
        'hw.lcd.height=1448' \
        'hw.lcd.density=300' \
        'hw.ramSize=1024' \
        'hw.cpu.ncore=2' \
        'hw.keyboard=yes' \
        'hw.sdCard=yes' \
        "sdcard.path=${ANDROID_AVD_HOME}/onyx_darwin6_api19.avd/sdcard.img" \
        'sdcard.size=512M' \
        'disk.dataPartition.size=1024M' \
        'hw.gpu.enabled=yes' \
        'hw.gpu.mode=swiftshader_indirect' \
        'fastboot.forceColdBoot=yes' \
        >> "${ANDROID_AVD_HOME}/onyx_darwin6_api19.avd/config.ini"

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 5555

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
