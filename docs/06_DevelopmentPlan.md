# SaikaiPTT Development Plan

## 1. Purpose

本文档定义 SaikaiPTT 从空仓库到可发布版本的完整开发路线。

开发必须采用增量方式进行。

禁止：

- 一次生成整个应用
- 多个核心模块同时开发
- 未验证前继续堆功能
- 用大量临时代码快速拼出 Demo

每个 Task 都应具备：

- 明确目标
- 明确输入
- 明确输出
- 明确验收标准
- 明确测试要求
- 明确完成条件

完成一个 Task 后：

1. 编译
2. 测试
3. Review
4. 检查架构影响
5. Git commit
6. STOP

等待下一条指令。

---

# 2. Development Strategy

整体分为：

```text
Phase 0  文档与工程基础
Phase 1  项目骨架
Phase 2  基础核心
Phase 3  网络发现与状态
Phase 4  音频与 PTT
Phase 5  后台服务
Phase 6  UI
Phase 7  通信记录
Phase 8  离线 ASR
Phase 9  联调与性能
Phase 10 测试与发布
```

---

# 3. Task Rules

当收到：

```text
Execute TaskXX
```

Claude 必须：

1. 阅读 `.claude/CLAUDE.md`
2. 阅读相关 docs
3. 阅读当前 Task
4. 检查现有代码
5. 明确依赖
6. 只实现当前 Task
7. 运行必要测试
8. 汇报修改内容
9. 汇报测试结果
10. 停止

不得主动执行后续 Task。

---

# 4. Task 01 — Project Repository Baseline

目标：

确认 Git 仓库基础状态。

工作：

- 检查 repository
- 检查 branch
- 检查已有文档
- 确认 .gitignore
- 确认 README
- 确认 CLAUDE.md

禁止：

创建 Android 业务代码。

验收：

仓库结构清晰。

---

# 5. Task 02 — Android Project Initialization

目标：

建立 Android 工程。

要求：

- Kotlin
- Gradle Kotlin DSL
- Android application module
- Android 11 minimum
- latest stable compile/target SDK
- 基本 Compose 环境
- lint
- unit test infrastructure

暂不实现：

- Network
- Audio
- Room business logic

验收：

空工程成功 Build。

---

# 6. Task 03 — Build and Dependency Baseline

目标：

建立稳定依赖管理。

工作：

- Version Catalog
- dependency versions
- repositories
- build types
- Debug / Release

要求：

尽量减少第三方依赖。

验收：

Debug / Release 均可编译。

---

# 7. Task 04 — Module Skeleton

建立最终架构需要的必要模块。

根据 Architecture 文档确定：

- core
- common
- network
- discovery
- heartbeat
- protocol
- audio
- service
- storage
- ui
- settings
- logger

如果某些模块当前没有真实边界：

允许暂不拆成独立 Gradle module，但必须保持 package boundary。

验收：

依赖方向正确。

---

# 8. Task 05 — Configuration System

实现统一 Config。

包含：

- protocol version
- network ports
- heartbeat interval
- peer timeout
- discovery timing
- audio parameters
- retry limits
- logging settings

禁止 magic numbers。

验收：

关键配置可集中管理。

---

# 9. Task 06 — Logging System

实现：

```text
Logger.d()
Logger.i()
Logger.w()
Logger.e()
```

要求：

- Debug 可详细输出
- Release 禁用 verbose/debug
- 支持 category
- 不记录音频 payload
- 不产生高频日志

验收：

可以查看 Network / Protocol / Audio / Service 日志。

---

# 10. Task 07 — Device Identity

实现：

UUID v4 Device ID。

要求：

- 首次生成
- DataStore 保存
- 重启不改变
- IP 改变不影响
- WiFi 改变不影响

测试：

重启应用。

重启设备。

确认 Device ID 不变。

---

# 11. Task 08 — Local User Management

实现：

LocalUser。

支持：

- 创建
- 修改
- 删除
- 切换
- Active User

规则：

不能删除最后一个用户。

验收：

重新启动后恢复。

---

# 12. Task 09 — Internationalization Foundation

实现：

- Japanese
- Simplified Chinese
- English
- Burmese
- Bengali

要求：

所有 UI 字符串 externalized。

禁止硬编码。

验收：

至少能够在测试页面切换五种语言。

---

# 13. Task 10 — DataStore Settings

建立 SettingsRepository。

保存：

- Device ID
- users
- active user
- language
- interrupt mode
- ASR enabled
- history retention
- overlay preference

验收：

设置重启后保持。

---

# 14. Task 11 — Protocol Data Structures

建立协议：

- Packet
- PacketHeader
- PacketType
- Packet validation
- Packet encoding
- Packet decoding

至少支持：

DISCOVERY
HEARTBEAT
PING
PONG
VOICE_START
VOICE_DATA
VOICE_END
BUSY
FORCE_INTERRUPT

验收：

encode/decode tests。

---

# 15. Task 12 — Protocol Validation

实现：

- version validation
- packet size validation
- device ID validation
- target validation
- payload limit
- unknown packet handling
- sequence validation

验收：

非法数据不会 Crash。

---

# 16. Task 13 — Peer Domain Model

建立 Peer：

```text
Peer
- deviceId
- userName
- ip
- ports
- protocolVersion
- state
- lastSeen
```

状态：

- DISCOVERED
- ONLINE
- OFFLINE
- BUSY
- COMMUNICATING

验收：

状态转换有明确规则。

---

# 17. Task 14 — LAN Discovery

实现：

DiscoveryService。

优先：

Android NSD / mDNS。

如果实际验证需要：

UDP discovery fallback。

要求：

- 自动发现
- endpoint 更新
- duplicate device 去重
- Device ID 作为身份

验收：

两台真实设备互相发现。

---

# 18. Task 15 — Heartbeat and Presence

实现：

HeartbeatService。

要求：

- 周期 heartbeat
- lastSeen
- timeout
- online/offline
- 不与 Discovery 混写

验收：

两台设备可自动判断对方在线/离线。

---

# 19. Task 16 — Network Recovery

处理：

- WiFi disconnect
- reconnect
- IP change
- socket recreation
- discovery restart
- heartbeat restart

验收：

断开 WiFi → 恢复 → 自动重新发现。

---

# 20. Task 17 — Network Receive Loop

建立统一 UDP 接收路径。

职责：

```text
Datagram
↓
Decode
↓
Validate
↓
Event
```

不得直接修改 UI。

验收：

能接收和分发控制包。

---

# 21. Task 18 — Session Manager

实现：

PTT Session state machine。

发送端：

- IDLE
- REQUESTING
- TRANSMITTING
- ENDING
- FAILED

接收端：

- IDLE
- RECEIVING
- ENDING
- FAILED

加入：

Session ID

Sequence Number

验收：

状态转换测试完整。

---

# 22. Task 19 — Audio Capture

实现：

AudioRecorder。

使用：

AudioRecord。

目标：

低延迟。

要求：

- 正确 lifecycle
- buffer 管理
- microphone error
- cancellation
- release

验收：

真实设备可稳定采集声音。

---

# 23. Task 20 — Audio Playback

实现：

AudioPlayer。

使用：

AudioTrack。

要求：

- low latency
- start/stop
- buffer
- release

验收：

真实设备能够稳定播放测试音频。

---

# 24. Task 21 — Voice Codec Interface

定义：

```text
VoiceCodec
```

提供：

encode / decode。

默认计划：

Opus。

Codec 必须可替换。

验收：

Codec 单元测试。

---

# 25. Task 22 — Opus Integration

集成：

Opus。

要求：

- low bitrate
- low latency
- Android 11 compatibility
- minimal overhead

如果目标设备兼容性存在问题：

记录 ADR。

验收：

PCM → Opus → PCM 测试。

---

# 26. Task 23 — Voice Packetization

实现：

VOICE_START

VOICE_DATA

VOICE_END。

加入：

- Session ID
- Sequence
- Timestamp

要求：

避免过大 UDP packet。

验收：

连续音频 frame 可发送和恢复。

---

# 27. Task 24 — Basic PTT Sender

实现：

按住：

Start。

松开：

Stop。

流程：

```text
PTT press
↓
target validation
↓
VOICE_START
↓
AudioRecord
↓
Opus
↓
UDP
```

验收：

A 能把声音发送给 B。

---

# 28. Task 25 — Basic PTT Receiver

实现：

VOICE_START。

VOICE_DATA。

VOICE_END。

接收：

自动播放。

无需接受按钮。

验收：

B 自动听到 A。

---

# 29. Task 26 — Packet Loss and Reordering

实现：

- sequence tracking
- duplicate drop
- late packet drop
- small jitter buffer
- missing packet handling

验收：

模拟：

- 丢包
- 乱序
- 重复

不会崩溃。

---

# 30. Task 27 — Busy Mode

实现：

目标忙线检测。

Busy request：

返回：

BUSY。

验收：

A → B 通话。

C → B：

C 得到 BUSY。

---

# 31. Task 28 — Force Interrupt

实现：

Force Interrupt。

要求：

- 默认关闭
- 原 Session 结束
- 新 Session 建立
- Race condition 防护

验收：

强插测试通过。

---

# 32. Task 29 — Foreground Service

实现：

Foreground Communication Service。

负责：

- background lifecycle
- network coordination
- PTT receiving
- notification

不把业务逻辑全部放 Service。

验收：

App 在后台仍能保持网络接收。

---

# 33. Task 30 — Notification

实现持续通知：

“SaikaiPTT 正在运行”

要求：

- localized
- low frequency updates
- no spam

验收：

Background 服务运行正常。

---

# 34. Task 31 — Overlay

实现：

系统悬浮窗。

正常：

绿色。

Incoming：

红色。

显示：

remote username。

点击：

打开 App。

验收：

后台收到 PTT 时颜色变化。

---

# 35. Task 32 — Main UI

实现：

Home。

包含：

- current user
- peer list
- selected target
- network state
- PTT

验收：

核心操作完整。

---

# 36. Task 33 — Username UI

实现：

- first launch name setup
- name management
- switch user

验收：

没有用户名：

不能 PTT。

---

# 37. Task 34 — Settings UI

实现：

- language
- interrupt mode
- ASR
- history retention
- permissions
- background settings

验收：

所有设置能够保存。

---

# 38. Task 35 — History Database

实现：

Room。

CommunicationRecord。

支持：

- timestamp
- direction
- users
- session
- audio path
- transcript
- read
- favorite
- status

验收：

PTT 完成后可以保存记录。

---

# 39. Task 36 — Audio Recording History

将 PTT 与本地录音结合。

要求：

- finalize file
- Room metadata
- failed-save handling
- orphan handling

验收：

每段有效 PTT 都可以回放。

---

# 40. Task 37 — Audio Playback UI

实现：

History Detail。

支持：

- play
- pause
- resume
- stop
- progress

验收：

录音可以正确播放。

---

# 41. Task 38 — Offline Japanese ASR

集成本地日语 ASR。

推荐：

Vosk。

要求：

- offline
- Japanese
- no network
- queue
- retry
- failure state
- low priority compared with PTT

默认：

关闭。

验收：

录音完成后能够生成日语字幕。

---

# 42. Task 39 — History Search and Favorites

实现：

- username search
- transcript search
- favorite
- unread
- retention cleanup

如果全文搜索复杂：

优先简单可靠实现。

验收：

可以快速找到目标记录。

---

# 43. Task 40 — Integration, Performance and Release

最终阶段：

## Network

验证：

- discovery
- heartbeat
- reconnect
- IP change
- multiple peers

## PTT

验证：

- send
- receive
- busy
- force interrupt
- loss
- reorder
- duplicate

## Background

验证：

- foreground
- background
- screen off
- device sleep
- notification
- overlay

## Storage

验证：

- recording
- playback
- history
- cleanup
- orphan files

## ASR

验证：

- Japanese recognition
- queue
- failure
- retry
- resource usage

## Performance

至少测试：

低端 Android 11 / 4GB RAM / MTK P22 类设备

以及：

现代旗舰设备。

测量：

- startup
- idle CPU
- active PTT CPU
- memory
- battery
- network traffic
- latency
- ASR processing time

## Release

完成：

- lint
- unit tests
- integration tests
- real device testing
- README
- CHANGELOG
- release checklist
- version code/name
- debug logging check
- permission review

只有全部通过：

才可以定义为：

MVP Release Candidate。

---

# 44. Git Strategy

推荐：

每个 Task：

一个 commit。

示例：

```text
feat: implement device identity

feat: implement LAN discovery

feat: implement heartbeat

feat: implement PTT sender

feat: implement PTT receiver

feat: implement communication history
```

文档：

```text
docs: add protocol specification
docs: add architecture specification
```

修 Bug：

```text
fix: recover discovery after wifi reconnect
```

---

# 45. Task Completion Report

每次 Task 完成后：

Claude 必须报告：

## Summary

完成什么。

## Files Changed

哪些文件。

## Tests

运行了什么。

## Result

成功/失败。

## Known Issues

尚未解决的问题。

## Architecture Impact

是否修改公共接口、协议、数据库等。

如果有重大变化：

要求创建 ADR。

最后：

> Task completed. Waiting for next instruction.

然后停止。

---

# 46. Task Dependency Rules

核心依赖顺序：

```text
Project
 ↓
Core
 ↓
Protocol
 ↓
Discovery
 ↓
Heartbeat
 ↓
Session
 ↓
Audio
 ↓
PTT
 ↓
Service
 ↓
UI
 ↓
History
 ↓
ASR
 ↓
Performance
 ↓
Release
```

禁止跳过核心依赖直接实现高级功能。

---

# 47. Definition of Done

一个 Task 只有在以下条件全部满足后才算完成：

- implementation complete
- compilation successful
- relevant tests pass
- no known blocking error
- architecture remains consistent
- documentation updated if necessary
- no unrelated feature added

---

# 48. Final Rule

开发过程中：

不要追求：

“尽快把所有功能写出来”。

追求：

“每完成一个阶段，就得到一个可信赖的系统”。

SaikaiPTT 的开发路线必须始终遵循：

```text
Correct
↓
Stable
↓
Tested
↓
Optimized
↓
Extended
```

而不是：

```text
Feature
↓
Feature
↓
Feature
↓
Bug
↓
Rewrite
```
