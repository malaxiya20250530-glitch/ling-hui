---
name: linghui-build
description: 编译灵绘 Android APK。支持 debug/release 模式、自动签名。当用户说要编译灵绘、构建APK、打包灵绘时使用。
---

# 灵绘 APK 构建

## 构建命令

```bash
cd ~/ling-hui && bash scripts/deploy.sh release
```

## 模式

| 命令 | 产物 |
|------|------|
| `bash scripts/deploy.sh debug` | `android_app/app/build/outputs/apk/debug/app-debug.apk` |
| `bash scripts/deploy.sh release` | `ling-hui.apk`（已签名） |

## 环境

- `ANDROID_HOME` = `~/android-sdk`
- Gradle wrapper: `android_app/gradlew`
- JDK: OpenJDK 21 (CI) / Termux 内置
- aapt2: 本地用 native aapt2，CI 用 AGP 自带

## CI

推送后 GitHub Actions 自动云编译。工作流: `.github/workflows/build-android-release.yml`

双模式自动检测：有 `unity_client/unityLibrary/build.gradle` → 编译 Unity 3D 版，无则小球版。
