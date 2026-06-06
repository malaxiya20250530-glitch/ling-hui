#!/bin/bash
# ═══════════════════════════════════════════
# 灵绘 APK 一键部署脚本
# 用法: bash scripts/deploy.sh [debug|release] [--install]
# ═══════════════════════════════════════════

set -euo pipefail

# ── 颜色输出 ──
RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'
CYAN='\033[36m'; BOLD='\033[1m'; RESET='\033[0m'

info()  { echo -e "${CYAN}[灵绘]${RESET} $1"; }
ok()    { echo -e "${GREEN}✅${RESET} $1"; }
warn()  { echo -e "${YELLOW}⚠️${RESET} $1"; }
err()   { echo -e "${RED}❌${RESET} $1"; }

# ── 参数解析 ──
BUILD_TYPE="${1:-release}"
DO_INSTALL=false
if [[ "${2:-}" == "--install" ]]; then
    DO_INSTALL=true
fi

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    err "构建类型必须是 debug 或 release，当前: $BUILD_TYPE"
    exit 1
fi

# ── 定位项目根目录 ──
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ANDROID_DIR="$PROJECT_DIR/android_app"

cd "$PROJECT_DIR"

# ── 环境检查 ──
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ ! -d "$ANDROID_HOME" ]]; then
    err "ANDROID_HOME 不存在: $ANDROID_HOME"
    echo "   请安装: pkg install android-sdk"
    exit 1
fi

info "ANDROID_HOME = $ANDROID_HOME"
info "构建类型     = $BUILD_TYPE"

# ── 检查 gradlew ──
if [[ ! -f "$ANDROID_DIR/gradlew" ]]; then
    err "gradlew 不存在: $ANDROID_DIR/gradlew"
    exit 1
fi
chmod +x "$ANDROID_DIR/gradlew"

# ── 清理旧构建 ──
info "清理旧构建产物..."
rm -rf "$ANDROID_DIR/app/build/outputs/apk"

# ── 编译 ──
info "开始编译 $BUILD_TYPE APK..."
cd "$ANDROID_DIR"

if [[ "$BUILD_TYPE" == "release" ]]; then
    ./gradlew assembleRelease --no-daemon 2>&1 | tail -20
    UNSIGNED_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
else
    ./gradlew assembleDebug --no-daemon 2>&1 | tail -20
    UNSIGNED_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

cd "$PROJECT_DIR"

# ── 检查产物 ──
if [[ ! -f "$UNSIGNED_APK" ]]; then
    err "构建失败，APK 未生成: $UNSIGNED_APK"
    exit 1
fi

APK_SIZE=$(du -h "$UNSIGNED_APK" | cut -f1)
ok "编译成功 ($APK_SIZE)"

# ── 签名（release 模式）──
OUTPUT_APK="$UNSIGNED_APK"
if [[ "$BUILD_TYPE" == "release" ]]; then
    KEYSTORE="$PROJECT_DIR/linghui.jks"
    if [[ ! -f "$KEYSTORE" ]]; then
        warn "签名密钥不存在: $KEYSTORE，跳过签名"
    else
        OUTPUT_APK="$PROJECT_DIR/ling-hui.apk"
        info "签名 Release APK..."
        "$ANDROID_HOME/build-tools/34.0.0/apksigner" sign \
            --ks "$KEYSTORE" \
            --ks-key-alias linghui \
            --ks-pass pass:linghui2025 \
            --key-pass pass:linghui2025 \
            --out "$OUTPUT_APK" \
            "$UNSIGNED_APK" 2>&1

        if [[ -f "$OUTPUT_APK" ]]; then
            SIGNED_SIZE=$(du -h "$OUTPUT_APK" | cut -f1)
            ok "签名完成: $OUTPUT_APK ($SIGNED_SIZE)"
        else
            err "签名失败"
            exit 1
        fi
    fi
fi

# ── 安装到设备 ──
if $DO_INSTALL; then
    if command -v adb &>/dev/null; then
        info "检测 ADB 设备..."
        DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
        if [[ "$DEVICES" -eq 0 ]]; then
            warn "未检测到 ADB 设备，跳过安装"
        else
            info "正在安装到设备..."
            adb install -r "$OUTPUT_APK" 2>&1
            ok "安装完成！"
        fi
    else
        warn "adb 不可用，跳过安装。可手动安装: $OUTPUT_APK"
    fi
fi

# ── 完成 ──
echo ""
echo -e "${BOLD}════════════════════════════════${RESET}"
echo -e "  ${GREEN}灵绘 APK 构建完成！${RESET}"
echo -e "  📦 $OUTPUT_APK"
echo ""
if ! $DO_INSTALL; then
    echo -e "  💡 提示: 加 --install 自动安装到手机"
fi
echo -e "${BOLD}════════════════════════════════${RESET}"
