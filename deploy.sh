#!/usr/bin/env bash
# 新仓库 deploy.sh 模板：一键构建 APK + 部署到本机 Android 模拟器
# 用法：
#   1. cp 本文件到目标仓库根目录，命名 deploy.sh
#   2. 修改下面三个变量（PKG / APK / BUILD）
#   3. chmod +x deploy.sh && ./deploy.sh
#
# 前提：emulator up 已启动模拟器（或插了 USB 真机）
set -euo pipefail
cd "$(dirname "$0")"

# ====== 按仓库修改 ======
PKG="com.literacy.app"                 # applicationId（android-app 的 defaultConfig）
APK="android-app/app/build/outputs/apk/debug/app-debug.apk"   # 构建产物路径
# 构建命令：容器构建示例（gradle in docker）；无容器规则可直接 ./gradlew assembleDebug
BUILD=(
    docker run --rm \
        -v gradle-cache:/home/gradle/.gradle \
        -v "$PWD/android-app:/workspace" \
        -v "$PWD/agent-core:/agent-core" \
        -w /workspace \
        literacy-android \
        gradle assembleDebug --no-daemon
)
# =========================

if [ "${1:-}" != "--install-only" ]; then
    echo "=== 1/2 构建 APK ==="
    "${BUILD[@]}"
    [ -f "$APK" ] || { echo "✗ 构建未产出 APK（检查 APK 路径变量）" >&2; exit 1; }
    # 容器 root 构建产物属主修正
    sudo chown "$(id -u):$(id -g)" "$APK" 2>/dev/null || true
fi

echo "=== 2/2 部署到设备 ==="
apk-deploy "$APK" -p "$PKG"
