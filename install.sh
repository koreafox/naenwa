#!/bin/bash
# 클린 빌드 & 설치 스크립트

echo "🧹 Clean build..."
./gradlew clean

echo "🔨 Building APK..."
./gradlew assembleDebug

echo "📱 Installing to device..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "✅ Done!"
