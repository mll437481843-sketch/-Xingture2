#!/usr/bin/env bash
# 本地构建（需要 JDK 17 + Android SDK + Gradle 8.5+）
#   ANDROID_HOME=/path/to/android-sdk ./build-local.sh
# 产物：app/build/outputs/apk/release/app-release.apk
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "找不到 gradle。装 Gradle 8.5+，或用 Android Studio 直接打开本目录。" >&2
  exit 1
fi

if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  echo "未设置 ANDROID_HOME / ANDROID_SDK_ROOT，Gradle 会找不到 Android SDK。" >&2
  exit 1
fi

echo "==> 开始编译（release 用 debug 签名，可直接安装）"
gradle --no-daemon --stacktrace assembleRelease

echo
echo "==> 产出："
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
