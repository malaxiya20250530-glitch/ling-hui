# 灵绘 (Ling Hui)

> AI 虚拟桌面精灵 | Unity 3D + Android 悬浮窗 + LLM + 手机自动化

## 架构

```
用户语音 → ASR识别 → LLM理解意图 → TTS语音回复
                ↓
         DroidWright 执行手机操作
                ↓
         Unity 3D 虚拟形象表情/动作联动
```

## 技术栈

| 层 | 技术 |
|---|------|
| 渲染 | Unity 2022 LTS + VRM 3D |
| 悬浮窗 | Android WindowManager |
| AI | ASR + LLM + TTS 管线 |
| 自动化 | DroidWright + gemini-android |
| CI/CD | GitHub Actions 云编译 |

## 项目结构

- `Unity/` — Unity 工程源文件
- `unity_client/` — Unity 导出 Android 项目
- `android_app/` — 原生宿主 (悬浮窗/AI/自动化)
- `.github/workflows/` — 云编译流水线

## 路线图

1. 地基 — 悬浮窗 + 3D 模型渲染
2. 大脑 — ASR+LLM+TTS 接入
3. 手脚 — DroidWright 手机自动化
4. 灵魂 — 角色人格定义
