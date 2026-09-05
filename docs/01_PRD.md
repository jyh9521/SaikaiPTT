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

- 自动发现局域网设备
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

设备列表只显示当前局域网内可通信的在线设备。

每个设备至少显示：

- 用户名
- 在线状态
- 忙线状态

示例：

```text
🟢 张三
🟢 李四
🔴 仓库（忙线）
```

Device ID 不需要直接展示给普通用户。

但在 Debug 信息中可以查看。

---

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

1. 检查目标是否在线。
2. 检查目标是否忙线。
3. 建立当前 PTT Session。
4. 发送 VOICE_START。
5. 开始采集麦克风数据。
6. 持续发送 VOICE_DATA。

## 10.2 持续讲话

用户继续按住：

持续发送音频。

## 10.3 结束讲话

用户松开 PTT：

1. 停止采集。
2. 发送 VOICE_END。
3. 完成本地录音。
4. 创建通信记录。
5. 如果启用离线识别，则进入识别队列。

---

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

当 App 当前处于前台：

收到对讲：

自动显示对应 PTT 页面或进入当前通信页面。

显示：

```text
张三
正在讲话...
```

同时：

自动播放语音。

无需：

- 接听
- 点击允许
- 确认

---

# 13. 后台接收行为

当 App 不在前台：

只要通信服务仍然正常运行：

必须能够收到 PTT。

收到后：

悬浮窗从：

绿色

切换为：

红色。

悬浮窗显示：

远程用户名。

例如：

```text
🔴 张三
```

用户点击悬浮窗：

打开 SaikaiPTT。

进入对应 PTT 状态。

---

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

A → B

正在进行。

C → B

请求：

B 返回 BUSY。

C 显示：

> 对方正在通话中。

A 与 B 当前通信继续。

---

# 16. Force Interrupt Mode

设置中提供：

Force Interrupt。

默认关闭。

关闭：

完全采用 Busy Mode。

开启：

目标正在通话时：

新的 PTT 请求可以打断当前通信。

系统需要：

1. 向当前通信发送终止状态。
2. 结束当前播放/发送。
3. 建立新的 PTT Session。
4. 切换到新的发送者。

如果多个强插请求同时发生：

必须由协议和会话状态机保证最终只有一个有效通信会话。

---

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

第一次运行时：

根据 Android 版本：

检查所需权限。

必要时引导用户：

- 麦克风
- 通知
- Overlay
- 后台运行
- 电池优化

权限不存在：

不得假定权限存在。

权限拒绝：

必须给出可理解的提示。

---

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

Japanese

Simplified Chinese

English

Burmese

Bengali

默认：

Japanese。

所有用户可见字符串：

必须本地化。

不能：

在 Kotlin/Compose/ViewModel 中直接硬编码文字。

语言切换：

应该在 App 内完成。

如果 Android 版本/架构导致即时刷新复杂：

允许重建 Activity。

---

# 27. Communication History

每次完成 PTT Session：

自动生成历史记录。

必须记录：

- Record ID
- Sender Device ID
- Receiver Device ID
- Remote User Name
- Direction
- Timestamp
- Duration
- Audio path
- Transcript
- Recognition state
- Read state
- Favorite state

---

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

每次 PTT 默认生成本地录音。

建议：

Opus。

必要时：

AAC。

不要保存长期 PCM。

录音属于通信记录的一部分。

---

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

语音识别必须完全本地。

不使用：

- 云端 API
- Google Cloud Speech
- Azure Speech
- OpenAI API
- 其它远程 ASR 服务

当前版本：

只支持日语。

推荐：

Vosk 或其它适合 Android 离线运行的日语 ASR。

---

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

至少支持：

```text
PENDING
PROCESSING
COMPLETED
FAILED
```

UI：

PENDING：

等待识别

PROCESSING：

正在识别

COMPLETED：

显示字幕

FAILED：

显示识别失败

即使：

FAILED

录音也必须可以播放。

---

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

待机：

目标尽量接近：

CPU < 1%

对讲：

目标：

CPU < 10%

这些是优化目标，不得为了达到数字而牺牲稳定性。

内存：

尽可能低。

参考目标：

普通模式约 30 MB 量级。

ASR 模型占用不计入普通待机目标。

---

# 42. 电池使用

后台服务：

必须避免：

- 无意义 WakeLock
- 高频轮询
- 高频重新创建对象
- 持续扫描网络
- 高频日志

设备发现和心跳：

使用合理时间间隔。

正常待机时：

尽可能让系统休眠。

---

# 43. 状态机

PTT 必须采用明确状态机。

发送端至少包含：

```text
IDLE
REQUESTING
TRANSMITTING
ENDING
FAILED
```

接收端至少包含：

```text
IDLE
RECEIVING
ENDING
FAILED
```

Busy/Force Interrupt：

必须通过状态机处理。

禁止使用大量 Boolean 来表达复杂会话状态。

---

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

- 忙线/强插模式
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

在线：

绿色。

忙线：

橙色或其它明确颜色。

通信：

突出显示。

离线：

灰色。

颜色不是唯一状态表达方式。

需要配合文字或图标，以考虑无障碍和色觉差异。

---

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

设备重新启动后：

系统允许的情况下：

后台服务重新恢复。

用户无需再次创建：

- Device ID
- User Name

如果 Android 系统限制自动启动：

在 UI 中提供对应系统设置引导。

---

# 58. 进程被系统杀死

如果系统杀死 App：

不要损坏：

- DataStore
- Room
- Audio Files

重新启动后：

恢复本地状态。

重新 Discovery。

---

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

第一可用版本的核心功能：

1. 初始化
2. Device ID
3. User Name
4. Discovery
5. Heartbeat
6. Peer List
7. PTT
8. One-to-One Voice
9. Foreground Service
10. Basic Overlay
11. Reconnect
12. Basic History
13. Recording
14. Playback
15. Japanese Offline ASR
16. i18n

以下功能可以在 MVP 完成后继续增强：

- 更丰富的历史搜索
- Favorite
- 更完善的 Overlay
- 更强的调试工具
- 更高级的性能优化

---

# 61. Acceptance Criteria

## Discovery

两个设备连接同一 WiFi：

必须能够互相发现。

## Identity

设备重启：

Device ID 不变。

## User Name

修改用户名：

远程设备能够看到新名称。

## PTT

A 选择 B：

A 讲话：

只有 B 能收到。

## Forced Receive

B 不需要确认：

即可听到 A 的声音。

## Background

B 在后台：

仍能收到 A。

## Overlay

B 后台收到：

绿色变红色。

## Busy

B 正在与 A 通信：

C 呼叫 B：

C 收到 BUSY。

## Force Interrupt

开启强插：

C 可以打断 A → B。

## Recovery

WiFi 断开后恢复：

自动重新发现。

## Recording

PTT 完成：

生成本地录音。

## Playback

历史记录：

可以回放。

## Transcript

开启 ASR：

完成后生成日语字幕。

## Offline

关闭互联网：

核心 PTT 仍然可工作。

## i18n

切换语言：

UI 正确切换。

---

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

本 PRD 描述：

产品行为。

后续文档分别负责：

00_MasterPrompt.md

定义最高级别产品和工程原则。

02_Architecture.md

定义软件架构、模块和依赖。

03_Protocol.md

定义网络协议。

04_UI_UX.md

定义界面和交互细节。

05_DataModel.md

定义 DataStore / Room / File 数据结构。

06_DevelopmentPlan.md

定义开发顺序。

07_TestPlan.md

定义测试策略。

08_ReleaseChecklist.md

定义发布标准。

tasks/

定义具体开发任务。

---

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