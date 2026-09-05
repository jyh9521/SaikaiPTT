# SaikaiPTT UI/UX Specification

## 1. Purpose

本文档定义 SaikaiPTT 的用户界面、页面结构、交互逻辑、状态展示、导航、首次启动流程、PTT 操作、后台行为、悬浮窗、通信记录和设置界面。

目标：

> 让 SaikaiPTT 像真正的数字对讲机一样简单、快速、直观。

UI 不应发展成聊天软件风格。

---

# 2. UI Design Principles

核心原则：

1. 简单
2. 清晰
3. 快速
4. 低操作成本
5. 高可读性
6. 低视觉负担

用户应该能够在极少操作下完成：

选择人 → 按住 → 讲话。

---

# 3. Visual Identity

主色调：

绿色。

主要状态：

绿色 = 在线 / 正常

红色 = 收到 PTT / 正在接收 / 警示

灰色 = 离线 / 不可用

忙线：

使用与绿色、红色明显区分的颜色。

颜色不能成为唯一状态提示方式。

必须同时使用：

- 图标
- 文本
- 状态变化

保证色觉异常用户也可以理解状态。

---

# 4. Design Language

推荐：

Material 3。

但不要过度使用 Material 组件默认的复杂视觉效果。

重点：

- 大按钮
- 清晰排版
- 少动画
- 清晰状态
- 少页面层级

PTT 是第一核心操作。

---

# 5. Navigation Structure

建议一级结构：

```text
Home
History
Settings
```

主要导航：

```text
Home
├── Peer List
├── Current User
└── PTT Screen

History
├── History List
└── History Detail

Settings
├── User Names
├── Language
├── PTT Settings
├── History Settings
└── Background / Permission Settings
```

---

# 6. First Launch Flow

首次运行：

```text
App Launch
↓
Initialization
↓
Generate Device ID
↓
Permission Check
↓
Create User Name
↓
Background Setup Guidance
↓
Home
```

其中：

Device ID：

后台自动完成。

用户无需看到。

用户名：

必须由用户完成。

如果没有用户名：

不能开始 PTT。

---

# 7. Username Creation Screen

首次运行显示：

标题：

> ようこそ

说明：

> 使用する名前を作成してください。

输入框：

用户名。

按钮：

> 作成

要求：

- 不允许为空
- 建议限制合理长度
- 去除首尾无意义空白
- 不允许只包含空白字符

创建成功：

设置为：

Active User。

然后进入 Home。

---

# 8. Username Management

Settings：

用户名管理。

列表：

```text
✓ 田中
  山田
  倉庫
```

支持：

- 新增
- 编辑
- 删除
- 切换

当前使用名称：

显示明显标记。

如果只剩一个名称：

不能删除最后一个名称。

---

# 9. Home Screen

主页核心内容：

```text
┌─────────────────────┐
│ 西海PTT        设置 │
│                     │
│ 使用中：田中         │
│                     │
│ 在线设备             │
│                     │
│ 🟢 山田             │
│ 🟢 倉庫             │
│ 🟠 保安（通話中）    │
│                     │
│                     │
│    对讲对象：山田     │
│                     │
│       [ PTT ]       │
│                     │
└─────────────────────┘
```

具体布局由实现阶段结合屏幕尺寸优化。

---

# 10. Current User

Home 顶部或显著区域显示：

当前用户名。

例如：

> 使用中：田中

点击：

进入用户名切换界面。

---

# 11. Peer List

在线设备：

显示：

- 用户名
- 状态
- 可选状态图标

例如：

```text
🟢 山田
🟢 倉庫
🟠 保安
```

离线设备默认不显示。

如果需要显示最近设备：

必须与在线列表明显区分。

---

# 12. Selecting Target

用户点击 Peer：

设置：

Current Target。

例如：

```text
目标：

山田
```

被选择的设备：

必须有明显选中状态。

例如：

- 边框
- 背景
- 选中图标

不应只依赖颜色。

---

# 13. PTT Button

PTT 是主页最重要的控件。

要求：

- 大
- 明显
- 容易按住
- 手指滑动时不容易误释放

正常：

绿色。

按下：

显示：

正在发送。

例如：

```text
● 松开结束
```

按下时：

不要需要再次点击确认。

---

# 14. PTT Start

当用户按下 PTT：

先检查：

1. Active User 是否存在。
2. Target 是否存在。
3. Target 是否 Online。
4. Target 是否 Busy。
5. 当前设备是否正在发送/接收其它 Session。

检查通过：

开始 PTT。

检查失败：

不开始 AudioRecord。

---

# 15. PTT Sending UI

发送时：

```text
正在与

山田

讲话

[ 松开结束 ]
```

可以显示：

发送计时。

例如：

```text
00:04
```

但不要持续刷新过于频繁。

---

# 16. PTT Receiving UI

收到别人讲话：

```text
山田

正在讲话...

00:03
```

自动播放。

不需要：

Accept。

不需要：

Answer。

---

# 17. Incoming PTT Foreground

当 App 在前台：

收到 VOICE_START：

立即显示：

Incoming PTT UI。

如果当前页面不是 PTT 页面：

切换到通信界面。

同时：

立即播放。

---

# 18. Incoming PTT Background

当 App 在后台：

不得依赖 Activity。

Foreground Service：

接收。

Overlay：

从：

绿色

变为：

红色。

显示：

远程 User Name。

例如：

```text
🔴 山田
```

点击：

打开 App。

---

# 19. Overlay Interaction

Overlay：

正常：

绿色。

收到通信：

红色。

可以：

- 闪烁
- 显示名字

但动画必须节制。

建议：

最大闪烁持续时间有限。

通信结束后：

恢复绿色。

如果收到下一条通信：

重新进入红色状态。

---

# 20. Overlay Permission

用户没有 Overlay 权限：

不能假定 Overlay 可以工作。

Settings：

显示：

Overlay：

未开启

并提供：

打开设置

按钮。

---

# 21. Busy UI

对方忙线：

显示：

```text
山田

通话中
```

尝试 PTT：

提示：

> 对方正在通话中。

不得：

播放本地录音。

不得：

进入发送状态。

---

# 22. Force Interrupt UI

如果启用：

强制插入。

用户选择忙线设备后：

可以开始发送。

但 UI 应明确：

> 強制割り込み

或者对应语言。

这是高级设置。

默认关闭。

---

# 23. Network Offline UI

WiFi 未连接：

主页顶部显示：

```text
⚠ WiFi 未连接
```

设备列表：

空。

PTT：

不可用。

恢复 WiFi：

自动更新。

---

# 24. No Peers UI

没有在线设备：

显示：

```text
現在オンラインのデバイスはありません。
```

以及：

```text
デバイスを検索しています…
```

不要显示：

Error。

---

# 25. Peer Connection State

建议 UI 使用：

```text
Online
Busy
Offline
```

内部状态可能更加复杂。

内部状态不需要全部暴露给普通用户。

---

# 26. History Screen

History 页面按：

时间倒序。

示例：

```text
今日

11:40
← 山田
▶
おはようございます

11:35
→ 倉庫
▶
了解しました

昨日

18:20
← 保安
▶
確認しました
```

---

# 27. History Item

每条记录至少显示：

- Direction
- Remote User
- Time
- Duration
- Transcript preview
- Unread state
- Favorite state

---

# 28. Unread UI

未读：

显示：

- 圆点
- 粗体
- 图标

不能只改变颜色。

打开记录：

自动标记：

Read。

---

# 29. History Detail

点击：

进入：

History Detail。

显示：

```text
山田

2026/09/05 11:40

00:04

▶ 播放

おはようございます
```

如果：

Transcript Processing：

显示：

> 正在识别……

如果：

Failed：

显示：

> 语音识别失败

但播放按钮始终可用。

---

# 30. Audio Playback UI

播放：

显示进度。

例如：

```text
▶ ━━━━━━━○── 00:02 / 00:04
```

支持：

- Play
- Pause
- Resume
- Stop

不需要：

复杂音频编辑功能。

---

# 31. History Search

顶部：

Search。

支持：

- 用户名
- 字幕

例如：

```text
搜索：山田
```

显示相关记录。

搜索：

```text
おはよう
```

显示 transcript 匹配项。

---

# 32. Favorite

History Detail：

提供：

☆ / ★

收藏后：

显示：

已收藏。

收藏记录：

不参与普通自动删除。

---

# 33. History Cleanup

Settings：

历史记录保存时间。

选项：

- 1 day
- 3 days
- 7 days
- 30 days
- Forever

默认：

7 days。

---

# 34. Settings Screen

设置建议：

```text
设置

用户
├── 当前用户名
└── 用户名管理

对讲
├── 忙线模式 / 强插模式
└── 音频设置

通信记录
├── 保存时间
└── 自动清理

后台运行
├── 通知
├── 悬浮窗
└── 电池优化

语言
└── App Language

关于
└── Version
```

---

# 35. Language Screen

显示：

```text
语言

日本語
简体中文
English
မြန်မာ
বাংলা
```

当前语言：

明显标记。

语言切换：

尽量立即生效。

如果实现复杂：

允许 Activity 重建。

---

# 36. Permission Status Screen

建议提供：

系统权限状态。

例如：

```text
麦克风          ✓
通知            ✓
悬浮窗          ✓
后台运行        ✓
电池优化        ⚠
```

点击：

进入对应系统设置。

系统厂商可能不同。

不得硬编码某个厂商设置页面路径。

---

# 37. Developer Settings

仅在 Debug Build：

显示：

- Device ID
- Current IP
- Protocol Version
- Discovery State
- Heartbeat State
- Session State
- Logging
- Network port
- ASR state

Release：

默认隐藏。

---

# 38. Error UI

错误提示应该：

短。

清晰。

可操作。

例如：

网络断开：

> WiFi接続が失われました。

目标离线：

> 相手はオフラインです。

忙线：

> 相手は通話中です。

麦克风：

> マイクを使用できません。

不要显示：

长 Exception Stack Trace。

---

# 39. SnackBar / Toast Policy

普通状态：

优先：

UI inline state。

短暂提示：

使用 SnackBar。

不要大量使用 Toast。

后台收到通信：

不要依赖 Toast。

---

# 40. Loading

网络发现：

可以显示：

轻量 loading。

ASR：

显示：

正在识别。

避免：

全屏 Loading 阻塞用户使用。

---

# 41. Accessibility

支持：

- TalkBack
- 内容描述
- 合理触控区域
- 文本可读性
- 非颜色唯一状态

PTT 按钮：

需要清晰 accessibility label。

例如：

> Push To Talk，目标：山田

---

# 42. Screen Rotation

手机旋转：

不能导致：

- PTT 状态丢失
- 网络服务停止
- 当前用户消失

UI 可以重建。

核心 Service 状态不能依赖 Activity。

---

# 43. Background / Foreground Transition

App：

Foreground → Background

必须：

保持通信。

Background → Foreground

必须：

同步最新：

- Peer list
- Session state
- unread state
- service state

---

# 44. PTT Interruptions

如果系统出现：

- Phone call
- another audio app
- microphone unavailable
- audio focus change

必须安全处理。

PTT 不应：

导致 App Crash。

具体 AudioFocus 行为由 Audio Architecture 决定。

---

# 45. Navigation Rules

返回：

不能意外停止：

Foreground Service。

从 PTT 页面返回：

只退出 UI。

不要因此停止整个网络服务。

---

# 46. Unsaved State

用户名编辑：

如果未保存：

返回时应提示。

普通 PTT：

不需要“保存”。

---

# 47. User Feedback

任何耗时操作：

应提供明确状态。

例如：

ASR：

> 認識しています…

Network：

> デバイスを検索しています…

Recovery：

> 接続を復元しています…

---

# 48. Animation Policy

尽量少用动画。

允许：

- PTT 按压反馈
- Overlay 状态变化
- 在线状态变化
- 简单进度

禁止：

持续复杂动画。

禁止：

后台运行时高频动画。

---

# 49. Localization Rules

UI 文本全部来自资源。

不要：

在 Composable 中写死中文/日文。

Plural：

使用 Android plural resources。

Date/time：

根据 Locale 格式化。

数字：

根据 Locale 处理。

---

# 50. UI State Model

建议：

```text
HomeUiState
- currentUser
- peers
- selectedPeer
- networkState
- serviceState
- sessionState

HistoryUiState
- records
- searchQuery
- filter
- loading

SettingsUiState
- language
- userNames
- retention
- interruptMode
- permissions
```

UI 只渲染 state。

---

# 51. Navigation State

推荐：

单向数据流。

```text
User Action
↓
ViewModel
↓
UseCase
↓
Domain State
↓
UiState
↓
UI
```

UI 不直接改变 Repository。

---

# 52. PTT Safety UX

PTT 开始前：

必须让用户能够清楚知道：

当前目标是谁。

例如：

> 对讲对象：山田

不要：

让用户按下按钮后才发现目标错误。

---

# 53. Target Change Safety

当正在 PTT：

禁止：

直接切换 Target。

必须：

先结束当前 Session。

再允许切换目标。

---

# 54. Username Change Safety

当前正在 PTT：

不允许：

修改 Active User。

可以：

提示：

> 通话结束后才能切换用户名。

确保当前 Session identity 一致。

---

# 55. History During Active PTT

通话进行中：

不要阻塞历史记录页面。

但如果打开历史：

当前正在进行的 Session 可以显示：

> 当前正在通话

具体行为由 Navigation 实现决定。

---

# 56. Home Screen Performance

主页不能频繁重组。

Peer heartbeat 更新：

使用精确 StateFlow。

不要每一个 heartbeat packet 都导致整个页面重建。

---

# 57. Overlay Performance

Overlay：

尽量使用：

单一轻量 View / ComposeView（根据实际实现选择）。

不要创建多个 Window。

不要持续动画。

---

# 58. Low-End Device UX

低端设备：

避免：

- 大图
- 视频背景
- 复杂模糊
- 大量阴影
- 复杂 Lottie 动画

UI 应保持简单。

---

# 59. UI Definition of Done

UI 阶段完成必须满足：

- 首次启动流程完整
- 用户名创建完整
- 用户名切换完整
- Home 完整
- Peer List 完整
- Target selection 完整
- PTT UI 完整
- Incoming PTT 完整
- Busy UI 完整
- Force Interrupt UI 完整
- Background overlay 完整
- History 完整
- Playback 完整
- Transcript UI 完整
- Search 完整
- Favorite 完整
- Unread 完整
- Settings 完整
- Language switch 完整
- Permission guidance 完整
- Debug information 完整

---

# 60. Final UX Principle

SaikaiPTT 的 UI 应让用户产生这样的感觉：

> “这就是一台对讲机。”

而不是：

> “这是一个聊天软件。”

用户最重要的动作永远只有：

```text
选择一个人
↓
按住 PTT
↓
讲话
↓
松开
```

其它功能都不应喧宾夺主。
