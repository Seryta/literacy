#!/usr/bin/env bash
# 一键构建 APK + 部署到本机 Android 模拟器
# 用法：
#   scripts/deploy.sh                # 构建 + 部署
#   scripts/deploy.sh --install-only # 跳过构建，只重新部署
#
# 前提：emulator up 已启动模拟器（或插了 USB 真机）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ====== 按仓库修改 ======
PKG="com.literacy.app"                 # applicationId（android-app 的 defaultConfig）
APK="android-app/app/build/outputs/apk/debug/app-debug.apk"   # 相对 PROJECT_ROOT 的构建产物
# 构建命令：容器构建（gradle in docker）
BUILD=(
    docker run --rm \
        -v "$PROJECT_ROOT/gradle-cache:/home/gradle/.gradle" \
        -v "$PROJECT_ROOT/android-app:/workspace" \
        -v "$PROJECT_ROOT/agent-core:/agent-core" \
        -w /workspace \
        literacy-android \
        gradle assembleDebug --no-daemon
)
# =========================

if [ "${1:-}" != "--install-only" ]; then
    echo "=== 1/2 构建 APK ==="
    "${BUILD[@]}"
    [ -f "$PROJECT_ROOT/$APK" ] || { echo "✗ 构建未产出 APK（检查 APK 路径变量）" >&2; exit 1; }
    # 容器 root 构建产物属主修正
    sudo chown "$(id -u):$(id -g)" "$PROJECT_ROOT/$APK" 2>/dev/null || true
fi

echo "=== 2/2 部署到设备 ==="
apk-deploy "$PROJECT_ROOT/$APK" -p "$PKG"
