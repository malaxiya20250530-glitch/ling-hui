---
name: linghui-deploy
description: 构建灵绘 APK 并安装到 Android 设备。一键编译+签名+adb安装。当用户说部署灵绘、安装到手机、构建并装APK时使用。
---

# 灵绘 部署到设备

## 一键部署

```bash
cd ~/ling-hui && bash scripts/deploy.sh release --install
```

步骤：编译 → 签名 → adb 检测设备 → 安装。

## 前提

- USB 调试已开启
- `adb devices` 能看到设备
- 悬浮窗权限已授予（首次安装后手动开）

## 仅构建不安装

```bash
bash scripts/deploy.sh release
# 产物: ~/ling-hui/ling-hui.apk
```
