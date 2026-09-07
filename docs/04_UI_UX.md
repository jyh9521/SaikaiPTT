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

在线设备显示：

- 用户名
- 状态图标
- 状态文本

例如：

```text
🟢 山田      オンライン
🟢 倉庫      オンライン
🟠 保安      通話中
```

## 11.1 离线设备

离线设备**默认不显示**，超时后从列表移除。

若提供「最近设备」分区，必须与在线列表明显分区，并使用灰色 + 文字标注。

## 11.2 忙线状态的来源与滞后

「通話中」来自对端心跳中的 `peerState`（`03_Protocol §12.2`），是**事前提示**，存在最长数百毫秒的滞后。

因此：

- 忙线设备**仍然可以点选**并按下 PTT（最终判定权在被叫方）。
- 若结果是 BUSY，按 §21 提示。
- 不得在本地依据缓存状态直接禁用 PTT 按钮。

## 11.3 非颜色提示

状态图标与状态文本必须同时存在，颜色不得作为唯一区分手段。

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

当用户按下 PTT，先做**本地前置检查**：

1. Active User 是否存在
2. 是否已选择 Target
3. Target 是否 Online
4. 本机当前是否已有其它发送/接收会话
5. 麦克风权限是否已授予
6. **应用界面是否可见**（§14.2）

检查失败：不启动 AudioRecord，给出对应的本地化提示。

检查通过：进入 `REQUESTING`，发送 VOICE_START，**同时立即开始采集并本地缓冲**。

## 14.1 不做本地忙线拒绝

忙线的最终判定权在被叫方。

即使设备列表显示对方「通話中」，也必须实际发出请求并等待应答（§11.2、`03_Protocol §31.1`）。

## 14.2 界面可见的要求

Android 14+ 禁止从后台提升为麦克风类型的前台服务，因此发送要求应用界面可见。

用户感知上不受影响：从悬浮窗或通知点击时先拉起界面，再按 PTT。

接收不受此限制。

## 14.3 按下即录

采集在发送请求的同时开始，缓冲上限 500ms。

收到 VOICE_ACCEPT 后先补发缓冲帧，再转入实时发送。

目的：不丢失第一个音节。用户不应被要求「等一下再说话」。

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

当 App 在前台收到 VOICE_START：

立即播放，并显示发送者名称与计时。

不需要 Accept，不需要 Answer。

## 17.1 自动跳转的边界

自动切换到通信界面**仅在**当前位于以下页面时发生：

- Home
- PTT 页面
- 通信记录列表 / 详情

位于以下页面时**不得强制跳转**，改为在页面顶部显示可点击的通信状态条：

- 用户名创建 / 编辑（存在未保存输入，见 §46）
- 设置页的任何输入型子页面
- 语言选择页

语音在以上所有情况下都照常自动播放。跳转与否只影响画面，不影响接收。

理由：

用户正在输入用户名时被强制跳转会丢失输入，与 §46 的未保存状态保护直接冲突。

# 18. Incoming PTT Background

当 App 在后台，不得依赖 Activity。

Foreground Service 接收并自动播放。

## 18.1 已授予悬浮窗权限

Overlay 从绿色变为红色，显示远程 User Name：

```text
🔴 山田
```

点击：打开 App。

## 18.2 未授予悬浮窗权限（降级）

**不得没有任何可见反馈。**

此时更新 Foreground Service 的持续通知：

```text
SaikaiPTT — 受信中：山田
```

通信结束后恢复为：

```text
SaikaiPTT が動作中です
```

通知更新频率受 §39 与 `01_PRD §22` 约束：每次会话最多更新 2 次（开始、结束），不得逐秒刷新。

## 18.3 回话需要先打开应用

后台收到通信后若要回话，需先打开应用。

Android 14+ 禁止从后台提升为麦克风类型的前台服务，因此**发送要求界面可见**（`01_PRD §10.5`）。

悬浮窗与通知的点击动作都必须能直接拉起对应界面，用户感知上仍是「点一下就能回话」。

接收始终不受此限制。

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

## 21.1 事前提示

设备列表中忙线设备显示：

```text
🟠 山田      通話中
```

该设备**仍可点选**，PTT 按钮**不禁用**（§11.2）。

## 21.2 事后结果

按下 PTT 后若收到 BUSY：

- 提示：`相手は通話中です。`（本地化）
- 不得播放任何本地录音
- 不得进入发送状态
- 不生成历史记录
- PTT 按钮恢复常态，用户可稍后重试

## 21.3 无应答

500ms 内无任何应答：

- 提示：`相手が応答しません。`
- 同样不生成历史记录

# 22. Force Interrupt UI

## 22.1 这是接收方设置

设置项位于「対讲」分组，措辞表达的是**允许别人打断我**：

```text
割り込みを許可
他の端末が通話中の自分に割り込むことを許可します
                                        [ OFF ]
```

默认：

关闭。

修订说明：

原文档把强插描述为呼叫方的能力（「用户选择忙线设备后可以开始发送」），与 `03_Protocol §33` 的接收方描述冲突。现统一为**接收方开关**：任何设备都不能单方面获得打断全网通话的能力。

## 22.2 呼叫方看到什么

呼叫方**没有**任何强插相关的界面或开关。

呼叫方按下 PTT 后只有三种结果：接通、BUSY、无应答（§21）。

## 22.3 被打断方看到什么

正在通话的一方收到 `SESSION_TERMINATE` 时：

- 立即结束当前会话
- 提示：`通話が中断されました。`
- 已采集的音频保存为 `INTERRUPTED` 状态的历史记录

## 22.4 风险提示

开启该设置时，在开关下方显示说明文字，让用户明确知道后果。

这是高级设置，不放在显眼位置。

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

点击进入 History Detail：

```text
山田

2026/09/05 11:40

00:04

▶ ━━━━━━━○── 00:02 / 00:04

おはようございます

☆ お気に入り        🗑 削除
```

## 29.1 字幕状态

| transcriptStatus | 显示 |
|---|---|
| `NOT_REQUESTED` | 不显示字幕区域 |
| `PENDING` | `認識待ち…` |
| `PROCESSING` | `認識しています…` |
| `COMPLETED` | 字幕文本 |
| `FAILED` | `音声認識に失敗しました` + `再認識` 按钮 |

**播放按钮在任何状态下都必须可用。**

## 29.2 音频缺失

若 `audioPath` 为 null（保存失败）或文件不存在：

- 播放按钮禁用并说明原因
- 记录其余信息照常显示
- 不得 Crash

## 29.3 未读

进入详情即标记为已读（§28）。

## 29.4 操作

- 收藏 / 取消收藏
- 删除（二次确认，同步删除音频）
- 重新识别（仅当录音存在且状态为 FAILED / NOT_REQUESTED 且 ASR 已启用）

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

# 33. History Cleanup and Deletion

## 33.1 自动清理

Settings → 通信履歴 → 保存期間：

- 1 day
- 3 days
- 7 days
- 30 days
- Forever

默认：

7 days。

收藏记录不参与自动清理。

## 33.2 手动删除单条记录

History Detail 与列表长按均提供删除。

删除时同步删除对应音频文件。

删除需要二次确认（不可撤销）。

## 33.3 批量删除

Settings → 通信履歴 → 履歴を削除。

必须明确确认。

收藏记录默认**不删除**；若用户选择连同收藏一起删除，需要单独的二次确认：

```text
お気に入りの記録も削除しますか？
```

不得默认无提示清空收藏内容（`05_DataModel §39`）。

## 33.4 存储使用量

同一分组显示当前通信记录占用的存储空间，打开该分组时计算一次即可。

# 34. Settings Screen

```text
設定

ユーザー
├── 現在のユーザー名
└── ユーザー名の管理

通話
├── 割り込みを許可（受信側ポリシー、既定 OFF）
└── 音声設定

通信履歴
├── 保存期間（1/3/7/30日・無期限、既定 7 日）
├── 履歴を検索
├── ストレージ使用量
└── 履歴を削除

字幕（音声認識）
├── 日本語オフライン字幕（既定 OFF）
├── モデルの状態 / ダウンロード
└── 未認識の履歴を再認識

バックグラウンド
├── 通知
├── オーバーレイ
├── バッテリー最適化
└── 権限の状態

言語
└── App Language

情報
├── バージョン
└── 既知の制限
```

## 34.1 ASR 开关的特殊处理

首次开启时必须先弹出确认对话框：

```text
初回のみ約 50 MB のダウンロードが必要です。
ダウンロード後は完全にオフラインで動作します。

              [ キャンセル ]  [ ダウンロード ]
```

下载过程显示进度，可取消。

下载失败或取消：开关保持 OFF，不影响任何其它功能。

模型已就绪时，开关直接生效，不再提示。

## 34.2 存储使用量

打开「通信履歴」分组时计算一次即可，不需要实时刷新（`05_DataModel §45`）。

## 34.3 Debug 信息

见 §37。Release Build 默认隐藏。

# 35. Language Screen

```text
言語

日本語          ✓
简体中文
English
မြန်မာ
বাংলা
```

当前语言明显标记。

## 35.1 实现机制

**不使用 AppCompat**，改用平台原生 API 分两条路径（Task10 落地）：

| Android 版本 | 机制 |
|---|---|
| 13+（API 33+） | `LocaleManager.applicationLocales`。系统托管，用户可在系统设置 → 应用 → 语言中查看和修改，资源解析由系统完成（含 Service 内的通知与悬浮窗） |
| 11~12（API 30~32） | 自行包装 `Configuration`，在每个 Activity 的 `attachBaseContext` 中生效 |

修订说明：原方案为 `AppCompatDelegate.setApplicationLocales()`。放弃的理由是
`androidx.appcompat` 会一并带入 AppCompat 主题体系，与本项目使用的平台主题冲突，
而本产品只需要「设定语言」这一件事——为一个设置引入整套主题体系不划算
（`.claude/CLAUDE.md` §32）。分路径实现的代价是两条代码路径，收益是零新增依赖。

切换生效方式：13+ 由平台重建 Activity；13 以下需要显式 `recreate()`，因为包装
只在 Activity attach 时发生。

## 35.2 三处存储必须一致

- **DataStore `app_language`**：权威值，应用其余部分读它
- **SharedPreferences 缓存**：仅为在第一个 Activity attach 之前**同步**读到语言。
  DataStore 是异步的，而 `attachBaseContext` 不能等待
- **平台（13+）**：让选择出现在系统设置里

三者由 `LocaleController` 在同一处写入。存了没生效、或生效了没存，都是只在重启后
才暴露的 bug。

## 35.3 字体

缅甸语与孟加拉语在部分低端 Android 11 ROM 上缺少系统字体或使用 Zawgyi 编码导致显示错乱。

必须在低端参考设备上实测。若确认缺字，内置对应 Noto 字体子集并在这两种语言下显式指定字体族。

## 35.4 资源限定符

| 语言 | 目录 |
|---|---|
| 日本語（默认） | `values/` |
| 简体中文 | `values-zh-rCN/` |
| English | `values-en/` |
| မြန်မာ | `values-my/` |
| বাংলা | `values-bn/` |

# 36. Permission Status Screen

```text
権限の状態

マイク              ✓
通知                ✓
オーバーレイ         ⚠  未許可
バックグラウンド実行  ✓
バッテリー最適化     ⚠  未除外
自動起動            ⚠  未確認
```

点击任一项：跳转对应系统设置。

## 36.1 规则

- 不得硬编码某个厂商的设置页路径。跳转失败时降级为文字指引。
- 每一项都要说明「不授予会失去什么」：
  - 麦克风：不能发送，仍可接收
  - 通知：看不到运行状态，更易被系统回收
  - 悬浮窗：后台提示降级为通知（§18.2）
  - 电池优化：后台可能被回收
- 权限被拒绝后不得反复弹窗骚扰，只在此页提供再次跳转入口。

## 36.2 首启引导

首次启动的分步引导见 `01_PRD §24`。

允许用户跳过任意一项，跳过后应用必须仍可运行，能力相应降级。

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

系统层面的中断由 `02_Architecture §17.7` 定义的 AudioFocus 策略统一处理。

摘要：

| 事件 | 行为 |
|---|---|
| 来电接通（`AUDIOFOCUS_LOSS`） | **立即终止当前会话**。发送方停采集并发 VOICE_END；接收方停播放并标记 INTERRUPTED |
| `LOSS_TRANSIENT` | 同上。对讲是实时的，「暂停后恢复」没有意义 |
| `LOSS_TRANSIENT_CAN_DUCK` | 不降低音量，按 `LOSS_TRANSIENT` 处理（语音可懂度优先） |
| 麦克风被其它应用占用 | 提示 `マイクを使用できません。`，不进入发送状态 |
| 耳机插拔 / 蓝牙连接 | 跟随系统路由，不中断会话 |

UI 要求：

- 中断必须有明确的本地化提示，不能静默结束。
- 中断不得导致 App Crash。
- 中断后 PTT 按钮恢复可用状态。

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
- **权限分步引导完整**（§36.2、`01_PRD §24`）
- 用户名创建 / 切换 / 管理完整
- Home 完整
- Peer List 完整（含忙线事前提示与非颜色状态表达）
- Target selection 完整
- PTT UI 完整（含按下即录、界面可见要求）
- Incoming PTT 前台完整（含自动跳转边界，§17.1）
- Incoming PTT 后台完整（Overlay 与**通知降级**两条路径，§18）
- Busy UI 完整（事前提示 + 事后结果，§21）
- 允许被打断（接收方设置）UI 完整（§22）
- 中断处理提示完整（§44）
- Background overlay 完整
- History 列表 / 详情完整
- Playback 完整（含文件缺失处理）
- Transcript 五态 UI 完整
- Search 完整
- Favorite 完整
- Unread 完整
- **手动删除与批量删除完整**（§33.2、§33.3）
- 存储使用量显示完整
- Settings 完整（含 ASR 模型下载确认流程，§34.1）
- Language switch 完整（五语言，含缅甸语/孟加拉语字体实测）
- Permission guidance 完整
- Debug information 完整且仅在 Debug Build 出现
- 无障碍：所有状态非颜色唯一，PTT 按钮有明确 label

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
