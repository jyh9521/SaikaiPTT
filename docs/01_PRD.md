# SaikaiPTT Product Requirements Document

## 1. 文档信息

产品名称：西海PTT

Project name：SaikaiPTT

Product type：Android LAN Push-To-Talk Application

Minimum Android version：Android 11 / API 30

Target：Latest stable Android SDK

Default language：Japanese

Supported languages：
- Japanese (ja)
- Simplified Chinese (zh-CN)
- English (en)
- Burmese (my)
- Bengali (bn)

---

# 2. 产品概述

SaikaiPTT 是一款运行在 Android 平台上的轻量级局域网数字对讲机应用。

主要用途是在多个设备连接到同一个 WiFi 网络的情况下，不依赖互联网、不依赖云服务器、不需要注册或登录，实现低延迟的一对一语音 PTT 通信。

产品必须尽可能接近真实对讲机的操作方式：

1. 选择使用者名称
2. 自动发现附近在线设备
3. 选择一个目标设备
4. 按住 PTT
5. 开始讲话
6. 松开 PTT
7. 结束讲话

核心原则：

> 简单、直接、快速、可靠。

---

# 3. 产品目标

## 3.1 核心目标

SaikaiPTT 必须提供：

- 自动发现局域网设备（UDP 广播，见 ADR-001）
- 一对一点对点对讲
- 低延迟语音传输
- 后台持续接收
- 悬浮窗提醒
- 自动网络恢复
- 本地通信记录
- 本地录音回放
- 本地日语语音识别
- 多语言 UI

## 3.2 非目标

SaikaiPTT 不是：

- 即时通讯软件
- 社交软件
- 群聊应用
- 云通信应用
- 手机电话应用

初始版本不要加入：

- 登录
- 注册
- 好友
- 群聊
- 文件
- 图片
- 视频
- GPS
- 地图
- 蓝牙通信
- 云同步
- 广告
- 内购

---

# 4. 核心用户流程

## 4.1 首次启动

用户第一次打开 App：

1. 初始化本地数据。
2. 生成唯一 Device ID。
3. 检查权限。
4. 引导用户创建第一个本地用户名。
5. 用户完成名称创建后进入主界面。
6. App 开始局域网设备发现。
7. App 根据 Android 版本和用户授权情况启动后台通信服务。

如果用户没有创建有效名称：

不允许开始 PTT。

---

# 5. 用户身份

## 5.1 Device ID

每台安装实例必须生成一个 UUID v4。

Device ID：

- 首次初始化时生成
- 保存在本地
- 正常情况下永久不变
- 与 IP 地址分离

Device ID 不能使用：

- MAC 地址
- IMEI
- Serial Number

## 5.2 User Name

用户可以在本地创建多个名字。

示例：

- 张三
- 李四
- 仓库
- 保安
- 经理

当前只能有一个 Active User。

用户可以随时切换。

切换名称不会改变 Device ID。

---

# 6. 主界面

主界面必须以对讲功能为核心。

建议布局：

顶部：

当前用户名。

中部：

在线设备列表。

底部：

主要 PTT 操作区域。

附加入口：

- 通信记录
- 设置

---

# 7. 在线设备列表

设备列表默认只显示当前局域网内可通信的**在线**设备。

每个设备至少显示：

- 用户名
- 在线状态
- 忙线状态

示例：

```text
🟢 张三
🟢 李四
🟠 仓库（忙线）
```

## 7.1 离线设备

离线设备**默认不显示**。

超时后（`03_Protocol §13`，默认 16 秒）从列表中移除。

如果最终 UX 决定提供「最近设备」区域展示离线设备，必须与在线列表明显分区，并使用灰色 + 文字标注（`04_UI_UX §11`）。

`§52` 中的灰色定义仅适用于该可选分区。

## 7.2 忙线状态的来源

忙线标记来自对端 HEARTBEAT 中的 `peerState` 字段（`03_Protocol §12.2`）。

这是**事前提示**，存在最长数百毫秒的滞后。

忙线的最终判定权在被叫方：即使列表显示对方空闲，仍可能在按下 PTT 后收到 BUSY。

## 7.3 Device ID

Device ID 不需要直接展示给普通用户。

Debug Build 的开发者信息中可以查看。

# 8. 目标选择

用户必须在开始 PTT 前选择一个目标设备。

未选择目标：

PTT 按钮不可用。

或明确提示：

> 请先选择对讲对象。

选择：

张三。

之后：

PTT 只能向张三发送。

如果用户想联系李四：

必须重新选择李四。

---

# 9. 点对点 PTT

一次 PTT Session：

只有一个：

Sender

和一个：

Receiver。

语音数据必须只发送给选定目标。

严禁：

- 语音广播
- 多设备同时接收
- 自动发送给全部在线设备

设备发现可以使用广播/组播。

语音不能使用广播。

---

# 10. PTT 交互

## 10.1 开始讲话

用户按住 PTT。

系统：

1. 本地前置检查：Active User 存在、已选择目标、目标在线、本机当前无其它会话、麦克风权限已授予、**应用界面可见**（见 §10.4）。
2. 生成新的 Session ID，进入 `REQUESTING`。
3. 发送 `VOICE_START`。
4. **立即开始采集麦克风数据并本地缓冲**（上限 500ms），此时不发送语音数据。
5. 收到 `VOICE_ACCEPT` → 进入 `TRANSMITTING`，先补发缓冲帧，再转入实时发送 `VOICE_DATA`。

不在本地依据缓存的忙线状态直接拒绝发送。忙线由被叫方判定。

## 10.2 持续讲话

用户继续按住：

持续发送音频。

## 10.3 结束讲话

用户松开 PTT：

1. 停止采集。
2. 发送 `VOICE_END`。
3. 完成本地录音。
4. 创建通信记录。
5. 如果启用离线识别，则进入识别队列。

## 10.4 请求失败

| 情况 | 结果 |
|---|---|
| 收到 `BUSY` | 提示「对方正在通话中」，**不生成历史记录** |
| 500ms 内无任何应答 | 提示「对方无响应」，**不生成历史记录** |
| 麦克风不可用 | 提示「无法使用麦克风」，不进入发送状态 |

## 10.5 平台约束：发送要求界面可见

Android 14 及以上禁止从后台启动或提升为麦克风类型的前台服务。

因此：

> **按住 PTT 讲话要求 SaikaiPTT 的界面处于可见状态。**

从悬浮窗点击时：先打开应用，再允许回话。

**接收始终不受此限制**：后台、熄屏、锁屏状态下均可正常接收并播放。

详见 `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`。

# 11. 接收 PTT

收到有效 VOICE_START：

立即进入接收状态。

不需要用户确认。

不需要点击接听。

不需要同意。

系统立即开始：

- 接收语音
- 播放语音
- 更新 UI 状态
- 更新悬浮窗状态（后台时）

---

# 12. 前台接收行为

当 App 当前处于前台，收到对讲：

显示：

```text
张三
正在讲话...
```

同时自动播放语音。

无需：

- 接听
- 点击允许
- 确认

## 12.1 自动跳转的边界

自动切换到通信界面**仅在**当前位于以下页面时发生：

- Home
- PTT 页面
- 通信记录列表 / 详情

当用户位于以下页面时，**不得强制跳转**，改为在页面顶部显示可点击的通信状态条：

- 用户名创建 / 编辑（存在未保存输入，`04_UI_UX §46`）
- 设置页的任何输入型子页面
- 语言选择页

语音在上述所有情况下都**照常自动播放**。跳转与否只影响画面，不影响接收。

# 13. 后台接收行为

当 App 不在前台，只要通信服务仍在运行：

必须能够收到 PTT，并自动播放。

## 13.1 悬浮窗提示

已授予悬浮窗权限时：

悬浮窗从绿色切换为红色，并显示远程用户名。

例如：

```text
🔴 张三
```

用户点击悬浮窗：

打开 SaikaiPTT，进入对应 PTT 状态。

## 13.2 未授予悬浮窗权限时的降级

如果用户没有授予悬浮窗权限，**不得没有任何可见反馈**。

此时：

- 更新 Foreground Service 的持续通知内容为「正在接收：张三」
- 通信结束后恢复为常规的「SaikaiPTT 正在运行」
- 通知更新频率受 §22 约束：每次会话最多更新 2 次（开始、结束），不得逐秒刷新

## 13.3 回话

后台收到通信后若要回话，需先打开应用（§10.5）。

悬浮窗与通知的点击动作都必须能直接拉起对应界面。

# 14. 强制接收原则

SaikaiPTT 的 PTT 接收模式不是电话模式。

不能要求：

- 接听
- 拒绝
- 点击确认

收到有效 PTT 后：

直接播放。

这是产品核心行为之一。

---

# 15. Busy Mode

默认：

Busy Mode。

当目标正在与其他设备进行通信时：

新请求不得打断当前通信。

例如：

`A → B` 正在进行。

`C → B` 请求：

B 返回 `BUSY`。

C 显示：

> 对方正在通话中。

A 与 B 当前通信继续。

C 不生成历史记录。

判定方：

**被叫方 B**。呼叫方不做本地忙线拒绝（`03_Protocol §31.1`）。

# 16. Force Interrupt Mode

## 16.1 归属方

**Force Interrupt 是接收方开关。**

设置项含义：

> 允许其他设备打断我正在进行的通话。

默认：

关闭。

发送方不需要、也无法感知对方的策略。发送方永远只发起普通请求，由被叫方决定接受或返回 BUSY。

被否决的方案：

发送方开关。任何一台设备单方面开启即可打断全网任意通话，被叫方无法拒绝。

## 16.2 关闭时

完全采用 Busy Mode。

## 16.3 开启时

被叫方 B 正在通话时收到新请求：

1. B 原子地把会话所有权转移给新请求。
2. B 向原发送方 A 发送 `SESSION_TERMINATE`。
3. A 结束当前发送，已采集音频保存为 `INTERRUPTED`，UI 提示通话被中断。
4. B 向新请求方 C 返回 `VOICE_ACCEPT`，建立新 Session。

## 16.4 竞态

如果多个请求同时到达：

由被叫方作为唯一仲裁者，通过单一原子操作保证最终只有一个有效通信会话。

其余请求一律收到 `BUSY`。

详见 `03_Protocol §33`、`§34`。

# 17. Peer State

设备状态至少包含：

```text
DISCOVERED
ONLINE
OFFLINE
BUSY
COMMUNICATING
```

状态变化必须通过明确的状态机处理。

不要通过多个 Boolean 随意组合设备状态。

---

# 18. Discovery

App 应自动发现局域网内的其它 SaikaiPTT 设备。

用户无需输入：

- IP
- 端口
- 主机名

发现设备后：

加入 Peer List。

设备停止响应后：

从 Online 状态转为 Offline。

---

# 19. Heartbeat

Heartbeat 用于判断设备是否仍然在线。

Heartbeat 与 Discovery 分离。

Discovery：

发现设备。

Heartbeat：

维持在线状态。

Voice：

处理实时语音。

Session：

处理当前通信状态。

这些职责不得混合。

---

# 20. 网络恢复

当 WiFi 断开：

- 停止当前网络通信
- 标记 Peer Offline
- 保留本地用户状态
- 保留历史记录
- 不崩溃

当 WiFi 恢复：

自动：

1. 重新初始化网络
2. 重新 Discovery
3. 重新 Heartbeat
4. 更新 Peer List
5. 恢复后台服务状态

无需用户重新启动 App。

---

# 21. 后台运行

App 支持长期后台运行。

核心通信能力必须依赖：

Foreground Service。

当后台服务正常运行时：

即使 App UI 不在前台：

仍能：

- 接收 PTT
- 播放 PTT
- 保存记录
- 更新悬浮窗

---

# 22. 通知

Foreground Service 使用持续通知。

通知应清晰表达：

> SaikaiPTT 正在运行

通知不能显示无意义的频繁消息。

正常情况下：

不要为每一秒的网络状态产生通知。

---

# 23. 悬浮窗

系统允许用户开启 Overlay 后：

App 可以显示轻量级悬浮控件。

正常：

绿色。

收到 PTT：

红色。

可以显示：

发送者名字。

点击：

打开应用。

悬浮窗不能持续占用大量 CPU。

---

# 24. 权限引导

第一次运行时，根据 Android 版本检查所需权限，并按需引导用户。

## 24.1 权限清单

| 权限 | 生效版本 | 用途 | 缺失时的影响 |
|---|---|---|---|
| `RECORD_AUDIO` | 全部 | 采集语音 | 不能发送，仍可接收 |
| `POST_NOTIFICATIONS` | Android 13+ 运行时权限 | 前台服务通知 | 服务仍可运行，但用户看不到状态；部分 ROM 上更易被回收 |
| `SYSTEM_ALERT_WINDOW` | 全部（需跳系统设置） | 悬浮窗 | 后台提示降级为通知（§13.2） |
| 电池优化白名单 | 全部（需跳系统设置） | 长期后台存活 | 后台可能被系统回收 |
| 自启动 / 后台弹出（厂商项） | 视 ROM | 开机恢复、悬浮窗拉起 | 视 ROM 而定 |

网络相关权限为普通权限，安装时授予，无需引导。

## 24.2 引导原则

- 分步引导，一次只解释一个权限，说明「不授予会失去什么」。
- 允许用户跳过任意一项，跳过后应用必须仍可运行（能力相应降级）。
- 权限不存在时，**不得假定权限存在**。
- 权限被拒绝时，必须给出可理解、可操作的本地化提示。
- 设置页提供权限状态总览与再次跳转入口（`04_UI_UX §36`）。
- 不得硬编码某个厂商的设置页路径；跳转失败时降级为文字指引。

# 25. 用户名管理

设置页面提供：

用户名管理。

功能：

- 新增
- 修改
- 删除
- 切换

规则：

当前正在使用的用户名不能被直接删除。

必须先切换到其他名字。

如果没有任何用户名：

进入初始名称创建流程。

---

# 26. 多语言

支持：

Japanese / Simplified Chinese / English / Burmese / Bengali

默认：

Japanese。

所有用户可见字符串必须本地化。

不能在 Kotlin / Compose / ViewModel 中直接硬编码文字。

## 26.1 实现机制

使用平台原生 API，**不引入 AppCompat**：Android 13+ 走 `LocaleManager`，
11~12 自行包装 `Configuration`。详见 `docs/04_UI_UX.md` §35.1。

语言选择同时写入 DataStore（`app_language`），用于应用外的组件（通知、悬浮窗）取值。

## 26.2 资源限定符约定

| 语言 | 目录 |
|---|---|
| 日本語（默认） | `values/`（默认资源即日语） |
| 简体中文 | `values-zh-rCN/` |
| English | `values-en/` |
| မြန်မာ | `values-my/` |
| বাংলা | `values-bn/` |

## 26.3 字体

缅甸语（`my`）与孟加拉语（`bn`）在部分低端 Android 11 ROM 上缺少系统字体，或使用 Zawgyi 编码导致显示错乱。

必须在低端参考设备上实测。若确认缺字，内置对应的 Noto 字体子集并在这两种语言下显式指定字体族。

APK 体积影响须在 Release 阶段评估。

# 27. Communication History

每次**成功建立并完成**的 PTT Session 自动生成历史记录。

请求被拒绝（BUSY）或无应答时**不生成**记录（§10.4）。

字段以 `05_DataModel §10` 为准。摘要如下：

- Record ID
- Session ID
- Sender Device ID / Receiver Device ID
- Local User ID（本机当时使用的用户名 ID）
- Remote User Name（通信发生时的名称快照）
- Direction（SEND / RECEIVE）
- Timestamp（UTC epoch millis）
- Duration（毫秒）
- Audio path（应用私有目录相对路径）
- Audio format（OPUS / AAC）
- Transcript
- Transcript status（NOT_REQUESTED / PENDING / PROCESSING / COMPLETED / FAILED）
- Read state
- Favorite state
- Record status（COMPLETED / INTERRUPTED / FAILED）

发送端与接收端各自在本机生成一条记录，互不同步。

# 28. 发送记录

自己发送：

```text
→ 张三
```

接收：

```text
← 张三
```

历史记录需要明显区分：

发送：

SEND

接收：

RECEIVE

---

# 29. 录音

每次成功完成的 PTT 默认生成本地录音。

格式：

Opus（Ogg 容器，`.opus`），16 kHz 单声道。

必要时的回退：

AAC。实际格式记录在 `audioFormat` 字段中，不得假设所有历史文件永远是 Opus。

不保存长期 PCM。

## 29.1 不做二次编码

- **发送端**：实时编码产生的 Opus 帧**同时**写入 UDP 与本地文件，不做第二次编码。
- **接收端**：把**收到的原始 Opus 帧**（经 jitter buffer 排序去重后）直接写入文件，不做「解码后重新编码」。

理由：

在 MTK P22 类设备上，重复编码同一段音频是明显的浪费。采用上述方案后，录音带来的 CPU 增量仅为文件 I/O。

丢失的帧在文件中不做补齐，`durationMs` 以会话实际时长为准。

详见 `docs/ADR/ADR-004-Audio-Params.md §6`。

## 29.2 双向录音

发送端与接收端**都要录音**，各自生成一条方向不同的历史记录。

录音属于通信记录的一部分。

# 30. 回放

点击历史记录：

可以播放录音。

至少支持：

- Play
- Pause
- Resume
- Stop

回放过程不能破坏：

- Foreground Service
- PTT
- 网络连接

---

# 31. 离线语音识别

语音识别过程必须完全本地。

不使用：

- 云端 API
- Google Cloud Speech
- Azure Speech
- OpenAI API
- 其它远程 ASR 服务

当前版本：

只支持日语。

引擎：

Vosk 或其它适合 Android 离线运行的日语 ASR（`docs/ADR/ADR-006-ASR-Engine-And-Model-Delivery.md`）。

## 31.1 模型分发与联网例外

识别模型**不打进 APK**。

用户在设置中首次开启 ASR 时，提示需要一次约 50 MB 的下载，确认后下载到应用私有目录。此后永久离线可用。

因此产品的离线承诺精确表述为：

> 核心功能——设备发现、在线状态、一对一 PTT、录音、回放、历史记录——**永不需要互联网**。
>
> 唯一例外：**离线日语字幕功能首次启用时需要一次联网下载识别模型**。下载完成后，识别过程 100% 本地，不上传任何音频或文本。

下载失败或中断：

ASR 保持关闭，PTT 与历史记录完全不受影响。

被否决的方案：

- 内置进 APK：体积增加 40~50 MB，而 ASR 默认关闭，绝大多数用户为不使用的功能付出成本。
- Play Asset Delivery：强制走 AAB + Play 渠道，侧载与企业内部分发场景失效。

# 32. 识别流程

PTT 结束后：

```text
VOICE_END
↓
保存录音
↓
创建 History Record
↓
加入 ASR Queue
↓
离线识别
↓
保存 Transcript
↓
更新 History Record
```

识别不得阻塞：

- PTT
- AudioRecord
- AudioTrack
- 网络线程

---

# 33. ASR 状态

五种状态：

```text
NOT_REQUESTED
PENDING
PROCESSING
COMPLETED
FAILED
```

| 状态 | 含义 | UI |
|---|---|---|
| `NOT_REQUESTED` | ASR 未开启，本条记录不参与识别 | 不显示字幕区域 |
| `PENDING` | 已加入队列 | 等待识别 |
| `PROCESSING` | 正在识别 | 正在识别 |
| `COMPLETED` | 识别成功 | 显示字幕 |
| `FAILED` | 识别失败 | 显示识别失败 + 重新识别入口 |

因为 ASR 默认关闭，`NOT_REQUESTED` 是绝大多数记录的初始状态，必须存在，不能省略。

即使 `FAILED`，录音也必须可以播放。

以 `05_DataModel §23` 为准。

# 34. 低端设备 ASR

离线 ASR 是高资源消耗模块。

因此：

默认关闭 ASR。

用户手动开启。

对于低端设备：

允许关闭或限制后台识别。

ASR 不得影响实时 PTT。

如果系统资源不足：

优先保证：

PTT

而不是：

ASR。

---

# 35. Communication History UI

历史页面按时间倒序排列。

示例：

```text
今天

11:25
← 张三
▶
おはようございます

11:30
→ 仓库
▶
了解しました

11:40
← 李四
▶
荷物をお願いします
```

状态：

- 未读
- 已读
- 收藏

---

# 36. 搜索

支持搜索：

远程用户名。

字幕。

例如：

搜索：

```text
仓库
```

或：

```text
おはよう
```

结果：

显示相关通信记录。

---

# 37. 未读

新收到的通信记录：

默认：

Unread。

历史列表显示明显标记。

进入记录后：

标记 Read。

---

# 38. 收藏

用户可以收藏重要通信。

收藏记录：

不会被普通自动清理删除。

---

# 39. 自动清理

支持：

- 1 day
- 3 days
- 7 days
- 30 days
- Forever

默认：

7 days。

清理时：

必须同时删除：

- 数据库记录
- 对应音频文件

避免孤立音频文件。

---

# 40. 存储结构

DataStore：

保存：

- Device ID
- User Names
- Active User
- Language
- Feature Settings

Room：

保存：

- Communication History
- Transcript
- Read state
- Favorite state

Audio：

保存到应用私有本地存储。

Room 保存：

Audio Path。

---

# 41. 性能要求

核心 PTT 功能必须在低端 Android 硬件正常运行。

以下为**可测量的目标值**，在 MTK P22 类参考设备上测量。这些是优化目标，不得为了达到数字而牺牲稳定性。

| 指标 | 目标 | 测量条件 |
|---|---|---|
| 待机 CPU | < 1% of one core | 服务运行、熄屏、无通信，10 分钟均值 |
| 对讲 CPU | ≤ 15% of one core | 采集 + Opus 编码 + UDP 发送 + 录音写盘 |
| 接收 CPU | ≤ 12% of one core | UDP 接收 + jitter buffer + 解码 + 播放 + 录音写盘 |
| Java heap | < 32 MB | 待机 |
| 总内存 PSS | < 130 MB | 待机，不含 ASR 模型 |
| 端到端延迟 P50 | ≤ 250 ms | 同一 AP，讲话开始到对端出声 |
| 端到端延迟 P95 | ≤ 400 ms | 同上 |
| 待机网络 | ≤ 1 广播包 / 5 秒 / 设备 | 心跳 |

## 41.1 关于内存目标的说明

原「约 30 MB」的表述容易诱导无效优化。含 Compose、Room、DataStore 与前台服务的 Android 应用，实际 PSS 通常在 60~120 MB 量级。

因此拆分为两个可测量口径：

- **Java heap < 32 MB**：这是应用自身对象分配的有效约束。
- **总 PSS < 130 MB**：包含 Android Runtime、图形栈与系统库，用于发现异常增长。

ASR 模型占用不计入以上任何目标。

## 41.2 CPU 目标的口径

全部按**单核占用百分比**计。八核设备上的「整机百分比」与单核百分比相差 8 倍，必须统一口径，否则测试结论不可比。

# 42. 电池使用

后台服务必须避免：

- 高频轮询
- 高频重新创建对象
- 持续扫描网络
- 高频日志

设备发现和心跳使用合理时间间隔（`03_Protocol §13`）。

正常待机时尽可能让系统休眠。

## 42.1 电源锁策略

原「禁止使用 WakeLock」的一般性禁令过严：熄屏后 WiFi 省电模式会丢弃广播帧，不持有 `MulticastLock` 则后台收不到心跳，核心需求失效。

因此精确表述为：

**除以下三处外，禁止持有任何电源锁。**

| 锁 | 持有时机 | 理由 |
|---|---|---|
| `MulticastLock` | 服务 READY 期间常驻 | 熄屏后接收广播心跳的必要条件 |
| `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` | 仅语音会话期间 | 降低语音抖动 |
| `PARTIAL_WAKE_LOCK`（超时上限 5 分钟） | 仅语音会话期间 | 保证熄屏时音频线程不被挂起 |

禁止：

- 为心跳、发现、日志或 UI 刷新持有任何电源锁
- 持有无超时上限的电源锁

详见 `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md §6`。

# 43. 状态机

PTT 必须采用明确状态机。**禁止使用多个 Boolean 表达复杂会话状态。**

发送端：

```text
IDLE → REQUESTING → TRANSMITTING → ENDING → IDLE
        ↓
     FAILED(TARGET_BUSY | NO_RESPONSE | MICROPHONE_UNAVAILABLE) → IDLE
```

`REQUESTING → TRANSMITTING` 由 **`VOICE_ACCEPT`** 触发。

接收端：

```text
IDLE → RECEIVING → ENDING → IDLE
        ↓
     INTERRUPTED → IDLE
        ↓
     FAILED → IDLE
```

`INTERRUPTED` 用于被强插终止、会话超时、WiFi 断开、AudioFocus 丢失等提前结束的情况。

## 43.1 会话所有权

Busy 与 Force Interrupt 均由**被叫方**通过单一原子操作仲裁（`03_Protocol §34`）。

所有权判定与应答（VOICE_ACCEPT / BUSY）的发送必须在同一个临界区内完成，避免并发请求同时读到「空闲」。

# 44. 网络错误

常见错误：

- WiFi disconnected
- Peer offline
- Peer busy
- Send failed
- Receive failed
- Timeout
- Invalid packet

错误必须：

- 不导致 Crash
- 可记录日志
- 对用户给出适当提示

---

# 45. 设备离线

如果目标在用户点击时已经离线：

不得开始 PTT。

提示：

> 对方当前不在线。

如果发送过程中突然离线：

立即结束通信。

保存已经完成的有效录音部分。

记录状态：

Failed / Interrupted

具体实现由 Architecture 文档定义。

---

# 46. 网络数据验证

所有入站数据必须验证。

至少验证：

- Protocol Version
- Packet Type
- Packet Length
- Sequence
- Device ID
- Payload size

非法数据：

直接丢弃。

不得导致 App Crash。

---

# 47. 协议兼容

Protocol 必须有：

Version。

旧版本和未知 Packet Type：

不能直接导致 Crash。

未支持的功能：

应安全忽略或返回适当错误。

---

# 48. 日志

开发版：

允许：

DEBUG

INFO

WARNING

ERROR

Release：

默认关闭 Debug/Verbose 日志。

日志不应：

- 保存完整音频数据
- 泄露无意义个人信息
- 高频刷屏

---

# 49. 设置页面

设置至少包含：

## 通用

- 当前用户名
- 用户名管理
- 语言

## 对讲

- 割り込みを許可（`allow_interrupt`，接收方策略，默认关闭）
- 音频相关设置（如最终产品需要）

## 通信记录

- 保存时间
- 自动清理

## 后台

- 后台运行状态
- 通知
- Overlay
- 电池优化状态

## 开发

Debug Build 才显示：

- Device ID
- 当前 IP
- 网络状态
- 协议版本
- 日志开关
- Discovery 状态
- Heartbeat 状态

Release Build 默认隐藏开发信息。

---

# 50. 首次启动流程

首次启动建议：

```text
启动
↓
初始化
↓
生成 Device ID
↓
权限检查
↓
创建用户名
↓
后台服务设置
↓
进入主页
↓
Discovery
↓
显示在线设备
```

如果权限不足：

在不影响其它功能的情况下：

明确提示。

---

# 51. 空状态

设备列表为空时：

不要显示错误。

显示：

> 現在オンラインのデバイスはありません。

其它语言：

通过 i18n 提供。

可以提供：

> 正在搜索设备……

---

# 52. 设备状态显示

建议区分：

| 状态 | 颜色 | 必须同时提供的非颜色提示 |
|---|---|---|
| 在线 | 绿色 | 图标 + 「オンライン」文本 |
| 忙线 | 橙色 | 图标 + 「通話中」文本 |
| 通信中（与本机） | 突出显示 | 边框 + 文本 |
| 离线 | 灰色 | 图标 + 「オフライン」文本 |

灰色离线样式仅用于可选的「最近设备」分区（§7.1）。主在线列表不显示离线设备。

颜色不是唯一状态表达方式。

必须配合文字或图标，以考虑无障碍和色觉差异。

# 53. 极简 UI 原则

SaikaiPTT 不应该看起来像：

微信。

Discord。

LINE。

WhatsApp。

设计应该接近：

数字对讲机。

优先：

大按钮。

清晰状态。

少层级。

少动画。

---

# 54. 录音与隐私

所有录音：

仅保存在本机。

用户可删除历史。

删除历史：

同时删除录音文件。

不会上传。

不会云同步。

---

# 55. 数据生命周期

User Settings：

长期保存。

Device ID：

长期保存。

Communication History：

按照用户设置自动删除。

Audio Files：

与 History Record 同步生命周期。

Transcript：

与 History Record 同步生命周期。

---

# 56. App 重启

App 重新启动后：

必须恢复：

- Device ID
- 用户名
- Active User
- Language
- Settings

不需要重新创建账号。

不需要重新注册设备。

网络设备：

重新 Discovery。

---

# 57. 手机重启

设备重新启动后，在系统允许的范围内恢复后台服务。

用户无需再次创建 Device ID 或 User Name。

## 57.1 平台约束

Android 14+ 禁止由 `BOOT_COMPLETED` 启动麦克风类型的前台服务。

因此开机自启后：

- 服务以 `connectedDevice` 类型启动
- 处于「**可接收、不可发送**」状态
- 用户首次打开应用后恢复完整能力

## 57.2 厂商限制

部分 ROM 会拦截自启动。

必须在权限状态页提供对应的系统设置引导入口，且不得硬编码厂商设置页路径。

如果系统或 ROM 阻止自启，应用不得表现为异常，只需在 UI 中如实反映服务状态。

# 58. 进程被系统杀死

如果系统杀死 App 进程：

不得损坏：

- DataStore
- Room
- Audio Files

## 58.1 恢复能力的边界

Android 12+ 禁止从后台启动前台服务。

因此**不承诺**进程被杀后自动恢复通信。

实际行为：

- 服务使用 `START_STICKY`。系统允许时重建服务；不允许时保持停止。
- 下次用户打开应用时，检测服务状态并恢复，重新 Discovery。
- 本地状态（Device ID、用户名、设置、历史）完整保留。

这是平台规则，必须在 README 的「已知限制」中如实说明，不得承诺做不到的事。

## 58.2 降低被杀概率

引导用户加入电池优化白名单（§24）。

保持前台服务与持续通知可见。

避免异常内存增长。

# 59. 实际设备测试

必须至少使用两台真实 Android 设备进行联调。

最低测试组合建议：

设备 A：

低端 Android 11 / 4GB RAM / MTK P22 类设备

设备 B：

现代 Android 旗舰设备

至少验证：

- Discovery
- PTT
- Background Receive
- Overlay
- Busy
- Force Interrupt
- Reconnect
- Recording
- Playback
- Japanese ASR

---

# 60. MVP 边界

第一可用版本（v1）的功能范围：

1. 初始化
2. Device ID
3. User Name 管理
4. Discovery（UDP 广播）
5. Heartbeat + 忙线状态通告
6. Peer List
7. 目标选择
8. 一对一 PTT（VOICE_START / ACCEPT / DATA / END）
9. Busy Mode
10. Force Interrupt（接收方开关，默认关闭）
11. 丢包 / 乱序 / 重复处理与 jitter buffer
12. Foreground Service 后台接收
13. 持续通知
14. 权限引导
15. Overlay
16. 网络自动恢复
17. 通信记录（Room）
18. 录音与回放
19. 历史搜索（用户名 + 字幕）
20. 收藏
21. 未读状态
22. 手动删除与自动清理
23. 离线日语 ASR（默认关闭）
24. 五语言 i18n

## 60.1 与 §62 的关系

`§62 Product Definition of Done` 与本节**范围一致**。

修订说明：本节原先把「收藏」与「更丰富的历史搜索」列为 MVP 之后的增强，与 §62、`06_DevelopmentPlan`、`07_TestPlan §33/§35`、`08_ReleaseChecklist §28/§29` 均冲突。现统一为：**搜索与收藏属于 v1**。

## 60.2 v1 之后的增强方向

- 全文搜索（FTS）
- 更丰富的历史筛选与批量操作
- 更完善的 Overlay 交互
- 更强的调试工具
- 基于真机 profiling 的进一步性能优化
- 更多 ASR 语言

以上均不属于 v1 验收范围。

# 61. Acceptance Criteria

## Discovery

两个设备连接同一 WiFi：必须能够互相发现。

## Identity

设备重启：Device ID 不变。

## User Name

修改用户名：远程设备能够看到新名称。

## PTT

A 选择 B，A 讲话：只有 B 能收到。

## Forced Receive

B 不需要确认即可听到 A 的声音。

## Session Handshake

A 按下 PTT 后收到 `VOICE_ACCEPT` 才进入发送态；首个音节不丢失。

## Background

B 在后台/熄屏：仍能收到 A。

## Background Send Constraint

B 在后台点击悬浮窗：应用被拉起后才能回话（`§10.5`）。

## Overlay

B 后台收到：绿色变红色。未授予悬浮窗权限时，通知内容更新（`§13.2`）。

## Busy

B 正在与 A 通信，C 呼叫 B：C 收到 BUSY，A 与 B 不受影响，C 不生成历史记录。

## Force Interrupt

**B 开启「允许被打断」**后，C 可以打断 A → B。A 收到 `SESSION_TERMINATE`，A 的记录状态为 `INTERRUPTED`。

## Force Interrupt Race

C 与 D 同时呼叫 B：只有一个建立会话，另一个收到 BUSY。

## Recovery

WiFi 断开后恢复：自动重新发现，Device ID 不变。

## Recording

PTT 完成：发送端与接收端各自生成本地录音。

## Playback

历史记录：可以回放。

## Transcript

开启 ASR（含首次模型下载）：完成后生成日语字幕。

## Offline

关闭互联网：核心 PTT、录音、回放、历史全部可用；已下载模型的 ASR 也可用。

## i18n

切换语言：UI 正确切换，通知与悬浮窗文本同步。

# 62. Product Definition of Done

当以下条件全部满足时：

SaikaiPTT 第一版才可认为完成：

- 可以在 Android 11+ 安装运行
- 可以生成 Device ID
- 可以创建/切换用户名
- 可以自动发现局域网设备
- 可以显示在线/忙线状态
- 可以选择一个目标
- 可以一对一点对点 PTT
- 可以后台接收
- 可以使用悬浮窗提醒
- 可以处理 Busy
- 可以处理 Force Interrupt
- 可以自动恢复网络
- 可以保存录音
- 可以回放录音
- 可以生成日语离线字幕
- 可以搜索历史
- 可以收藏记录
- 可以标记未读
- 可以自动清理
- 支持五种语言
- 没有核心功能依赖互联网
- 低端 Android 设备能够正常运行
- 核心模块拥有自动化测试
- 实际两台设备联调通过

---

# 63. 产品底线

SaikaiPTT 的任何新增功能都不得破坏以下原则：

> 局域网优先
> 一对一点对点
> PTT 优先
> 后台可靠
> 低功耗
> 低资源
> 本地数据
> 隐私优先
> 简单易用

如果某个新功能与这些原则冲突：

必须先进行架构评估。

不得直接实现。

---

# 64. 后续文档引用关系

本 PRD 描述产品行为。

后续文档分别负责：

| 文档 | 职责 |
|---|---|
| `00_MasterPrompt.md` | 最高级别产品和工程原则 |
| `02_Architecture.md` | 软件架构、模块和依赖 |
| `03_Protocol.md` | 网络协议 |
| `04_UI_UX.md` | 界面和交互细节 |
| `05_DataModel.md` | DataStore / Room / File 数据结构 |
| `06_DevelopmentPlan.md` | 开发顺序 |
| `07_TestPlan.md` | 测试策略 |
| `08_ReleaseChecklist.md` | 发布标准 |
| `docs/ADR/` | 重大技术决策，**冲突时以 ADR 为准** |
| `tasks/` | 具体开发任务 |

## 64.1 现行 ADR

| ADR | 主题 |
|---|---|
| ADR-001 | 设备发现策略（纯 UDP 广播） |
| ADR-002 | 传输层与端口方案 |
| ADR-003 | 协议二进制线格式 |
| ADR-004 | 音频参数与 AudioFocus 策略 |
| ADR-005 | 前台服务、Android 版本兼容与电源锁 |
| ADR-006 | 离线日语 ASR 引擎与模型分发 |

# 65. Final Product Statement

SaikaiPTT 的最终体验应该是：

> 打开 App。
>
> 选择自己的名字。
>
> 自动看到同一 WiFi 下的其它设备。
>
> 点选一个人。
>
> 按住按钮讲话。
>
> 对方立即听到。
>
> 即使对方 App 在后台，只要通信服务正常运行，也可以立即收到。
>
> 松开按钮后，这段通信自动保存。
>
> 以后可以回听。
>
> 开启离线识别后，还可以看到日语字幕。
>
> 整个过程不需要互联网、不需要账号、不需要服务器。

SaikaiPTT 应该始终保持：

> 像对讲机一样简单，
> 像本地应用一样私密，
> 像专业软件一样可靠。