# 🎮 灵绘 Unity 3D 集成指南

> 在桌面 Unity Editor 中打开 `Unity/` 工程，按此指南补全 3D 资产。

## 前置条件

- Unity 2022 LTS 或更高
- Node.js 18+（MCP 工具依赖）
- Git

## 工具清单

| 方向 | 工具 | 本地路径 | GitHub |
|---|---|---|---|
| Prefab/场景 | unity-editor-mcp | `unity_tools/unity-editor-mcp/` | [ozankasikci/unity-editor-mcp](https://github.com/ozankasikci/unity-editor-mcp) |
| 动画控制 | Unity-AI-Animation | `unity_tools/Unity-AI-Animation/` | [IvanMurzak/Unity-AI-Animation](https://github.com/IvanMurzak/Unity-AI-Animation) |
| MCP 扩展 | Unity-MCP-Extensions | `unity_tools/Unity-MCP-Extensions/` | [meta-quest/Unity-MCP-Extensions](https://github.com/meta-quest/Unity-MCP-Extensions) |
| 场景生成 | UniGenBench | `unity_tools/UniGenBench/` | [CodeGoat24/UniGenBench](https://github.com/CodeGoat24/UniGenBench) |

---

## 第一步：搭建 MCP 环境

### 1.1 安装 unity-editor-mcp

```bash
cd unity_tools/unity-editor-mcp
npm install
npm run build
```

在 Codex / Claude / Cursor 的 MCP 配置中添加：

```json
{
  "mcpServers": {
    "unity-editor-mcp": {
      "command": "node",
      "args": ["unity_tools/unity-editor-mcp/dist/index.js"]
    }
  }
}
```

### 1.2 安装 Unity-AI-Animation

在 Unity Package Manager 中通过 OpenUPM 安装：

```
Name: com.ivanmurzak.unity.mcp.animation
URL: https://package.openupm.com
```

或从本地路径导入 `unity_tools/Unity-AI-Animation/`

---

## 第二步：场景 & Prefab 生成

用 AI 对话直接生成灵绘的 3D 场景：

### 对话示例

```
> 创建一个名为 LingHui_Scene 的新场景
> 添加一个 Directional Light（暖色调，模拟桌面氛围）
> 创建一个 Plane 作为桌面底座，材质设为半透明玻璃
> 创建一个 Sphere 作为灵绘本体，挂在名为 LingHui_Root 的空 GameObject 下

> 给 Sphere 添加 ToonCharacter.shader 材质
> 设置 Sphere 的 scale 为 (0.15, 0.15, 0.15)
> 在 LingHui_Root 上挂载 LingHuiCharacter.cs 脚本

> 把 LingHui_Root 保存为 Prefab：Assets/Prefabs/LingHui.prefab
```

unity-editor-mcp 的 62 个工具覆盖：GameObject 管理、组件操作、Prefab 工作流、场景分析。

---

## 第三步：动画控制器

用 Unity-AI-Animation 通过自然语言创建动画：

### 对话示例

```
> 创建一个 AnimatorController 命名为 LingHui_AnimController
> 添加默认状态 Idle（循环播放轻微呼吸缩放动画，幅度 3%，周期 2 秒）
> 添加状态 Talking（循环播放上下弹跳 + 缩放脉冲）
> 添加状态 Happy（快速旋转 + 颜色渐变）
> 添加状态 Surprised（突然放大 + 震动效果）

> 从 Idle → Talking：Trigger 参数 "onTalk"
> 从 Talking → Idle：Trigger 参数 "onIdle"
> 从任意状态 → Happy：Trigger 参数 "onHappy"
> 从任意状态 → Surprised：Trigger 参数 "onSurprised"

> 所有过渡时间设为 0.25 秒，使用平滑插值
```

---

## 第四步：VRM 3D 模型

灵绘的 `VRMModelLoader.cs` 已写好 VRM 加载逻辑。需要一个 VRM 模型文件。

### 方案 A：用 AI 工具生成

推荐工具（需在桌面环境使用）：
- **VRoid Studio**（免费）— 捏人导出 VRM
- **Stunning Modeler** — 文本/图像 → 3D 模型 → 导出 VRM
- **Blender + VRM Addon** — 自由建模后导出

### 方案 B：使用现成 VRM 模型

1. 从 [VRoid Hub](https://hub.vroid.com/) 下载免费模型
2. 放到 `Unity/Assets/Models/LingHui.vrm`
3. Unity 中导入 [UniVRM](https://github.com/vrm-c/UniVRM) 包
4. `VRMModelLoader.cs` 会自动加载

---

## 第五步：与现有代码对接

灵绘已有 7 个 C# 脚本，AI 工具生成的资产需要与它们对接：

| 脚本 | 对接方式 |
|---|---|
| `LingHuiCharacter.cs` | 挂载到角色 Prefab，控制动画参数切换 |
| `AiBridge.cs` | 接收 Python 管线的情绪/状态消息，触发动画 |
| `LingHuiBridge.cs` | Android ↔ Unity 通信适配 |
| `ModelManager.cs` | 管理 VRM 模型加载/卸载 |
| `VRMModelLoader.cs` | 解析 VRM 文件，提取骨骼和 BlendShape |
| `TransparentCamera.cs` | 透明背景渲染（悬浮窗用） |
| `ToonCharacter.shader` | 卡通渲染 Shader |

### AiBridge.cs 对接示例

当 Python 管线推送情绪更新时：

```csharp
// AiBridge.cs 中处理来自 AiCommHandler 的消息
void OnMoodChanged(string mood) {
    var anim = GetComponent<Animator>();
    switch (mood) {
        case "happy":    anim.SetTrigger("onHappy"); break;
        case "curious":  anim.SetTrigger("onSurprised"); break;
        case "talking":  anim.SetTrigger("onTalk"); break;
        default:         anim.SetTrigger("onIdle"); break;
    }
}
```

---

## 验证清单

- [ ] Unity Editor 能打开 `Unity/` 工程无报错
- [ ] unity-editor-mcp 连接成功，终端可见 `Unity Editor MCP server running`
- [ ] 场景中有 LingHui.prefab
- [ ] AnimatorController 有 Idle/Talking/Happy/Surprised 四个状态
- [ ] VRM 模型加载后能在 Game View 中渲染
- [ ] 透明背景在悬浮窗模式下正常
- [ ] `python3 demo/run_demo.py` 与 Unity Editor 联动正常

---

## 🎯 哪吒 GLB 模型导入指南（当前使用）

### 已生成模型

| 文件 | 大小 | 格式 | 来源 |
|------|------|------|------|
| `nezha.glb` | 2.4 MB | GLTF 2.0 | TripoSR（HuggingFace Space） |

### 导入步骤

1. **拷贝模型到 Unity 工程**
   ```bash
   cp nezha.glb Unity/Assets/Models/
   ```

2. **Unity 中打开工程** → `Assets/Models/nezha.glb` 会自动导入

3. **拖入场景** → 把 `nezha` 预制体拖到 Hierarchy 窗口

4. **配置 Humanoid Rig**（无 Mixamo 时手动绑骨）
   - 选中模型 → Inspector → **Rig** 标签
   - **Animation Type** → `Humanoid`
   - **Avatar Definition** → `Create From This Model`
   - 点 **Configure** 进入骨骼映射界面
   - 绿色圆点 = 自动匹配成功，红色需手动拖拽：
     - `Head` → 拖到模型头顶
     - `Left Hand` → 拖到左手腕
     - `Right Hand` → 拖到右手腕
     - `Left Foot` → 拖到左脚踝
     - `Right Foot` → 拖到右脚踝
     - （如果 TripoSR 模型没手指，Chest/Upper Leg 等设为 None 即可）
   - 点 **Apply** → **Done**

5. **挂载脚本** — 选中 Hierarchy 中的 nezha 对象：
   - `Add Component` → 搜 `ModelManager` → 把 `nezha` 预制体拖到 `Model Prefab` 槽
   - `Add Component` → 搜 `LingHuiCharacter`
   - `Add Component` → 搜 `AiBridge`

6. **材质修复**（TripoSR 无材质，用顶点色）
   - 选中 `nezha` → Inspector → **Materials** 标签
   - **Extract Materials** → 选 `Assets/Materials/`
   - 将提取的材质 Shader 改为 `LingHui/ToonCharacter`

### Animator Controller 配置

创建 `Assets/Animations/LingHui_Anim.controller`，四个状态：

| 状态 | 动画 | 触发参数 | 说明 |
|------|------|----------|------|
| Idle | 呼吸缩放 (0, 0, 0) | 默认 | 轻微上下浮动 |
| Talking | 弹跳 + 缩放脉冲 | `onTalk` (Trigger) | AI 说话时 |
| Happy | 快速旋转 | `onHappy` (Trigger) | 开心回应 |
| Surprised | 突然放大 | `onSurprised` (Trigger) | 好奇/惊讶 |

所有过渡时间：**0.25 秒**，取消 `Has Exit Time`，`Transition Duration` = 0.25。

