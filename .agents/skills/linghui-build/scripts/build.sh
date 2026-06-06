#!/bin/bash
# 灵绘 APK 构建 — 被 SKILL.md 调用
set -e
cd ~/ling-hui
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BUILD_TYPE="${1:-release}"
bash scripts/deploy.sh "$BUILD_TYPE"
