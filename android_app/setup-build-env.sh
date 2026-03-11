#!/usr/bin/env bash
# Sets up a minimal Android build environment in WSL (no Android Studio needed)
# Run once from the android_app directory: bash setup-build-env.sh

set -e

ANDROID_SDK_ROOT="$HOME/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> Installing JDK 17..."
sudo pacman -Sy --noconfirm jdk17-openjdk unzip wget

echo "==> Downloading Android command-line tools..."
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted
mv /tmp/cmdline-tools-extracted/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
rm /tmp/cmdline-tools.zip

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

echo "==> Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "==> Installing SDK components..."
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

echo "==> Installing Gradle (to generate wrapper)..."
sudo pacman -S --noconfirm gradle
gradle wrapper --gradle-version 8.6 --distribution-type bin

echo ""
echo "==> Setup complete. Add these lines to your ~/.bashrc:"
echo "    export ANDROID_HOME=\"\$HOME/android-sdk\""
echo "    export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
echo ""
echo "==> Then build with:"
echo "    ./gradlew assembleDebug"
echo "    # APK -> app/build/outputs/apk/debug/app-debug.apk"
