# SaikaiPTT Architecture Specification

## 1. Purpose

本文档定义 SaikaiPTT 的软件架构、模块边界、数据流、线程模型、生命周期处理和核心状态机。

本文件服务于 Android 11 / API 30 及以上版本，并以低端 Android 设备的稳定运行作为重要设计约束。

架构目标：

- 稳定
- 低延迟
- 低功耗
- 低内存和 CPU 占用
- 易测试
- 易维护
- 可扩展
- 核心功能完全局域网离线运行

---

# 2. Architecture Principles

所有架构设计遵循以下优先级：

1. 稳定性
2. 实时通信可靠性
3. Android 生命周期正确性
4. 兼容性
5. 低功耗
6. 低资源占用
7. 可测试性
8. 可维护性
9. 可扩展性

禁止为了“看起来更高级”而引入不必要的复杂架构。

禁止为了减少少量代码而牺牲模块边界。

---

# 3. Recommended Architecture

采用分层架构：

```text
Presentation
    ↓
ViewModel
    ↓
UseCase
    ↓
Repository / Service Interface
    ↓
Core Implementations
    ↓
Android / Network / Audio / Storage APIs
```

建议结合：

- MVVM
- Repository Pattern
- UseCase
- Dependency Injection
- Interface-first design
- Structured Concurrency

如果实际实现表明某个完整框架会增加不必要的启动、内存或 APK 负担，可以采用更轻量的依赖注入方案。

不要为了使用某个架构框架而使用框架。

---

# 4. Layer Responsibilities

## 4.1 Presentation Layer

负责：

- Compose UI
- 页面导航
- UI state
- 用户交互
- 可访问性

不得直接：

- 操作 UDP Socket
- 操作 AudioRecord
- 操作 AudioTrack
- 操作 Room DAO
- 管理 Foreground Service 生命周期

---

## 4.2 ViewModel Layer

负责：

- 保存页面状态
- 接收 UI Intent
- 调用 UseCase
- 将业务状态转换为 UI State

不得：

- 直接访问 Android Context 进行复杂业务操作
- 创建 Socket
- 直接实现音频采集
- 直接执行数据库 SQL
- 编写网络协议逻辑

---

## 4.3 UseCase Layer

负责：

表达明确的业务动作。

示例：

- ObservePeers
- SelectPeer
- StartPtt
- StopPtt
- AcceptIncomingPtt（逻辑事件，不代表用户确认）
- HandleBusy
- ForceInterrupt
- ObserveHistory
- ReplayRecording
- RunTranscription
- ChangeActiveUser
- ChangeLanguage

UseCase 不应该知道具体使用的是：

UDP

还是：

其它传输实现。

---

## 4.4 Repository / Service Interface

为上层提供稳定接口。

例如：

```text
PeerRepository
SessionRepository
HistoryRepository
SettingsRepository
VoiceTransport
PeerDiscovery
PresenceService
AudioRecorder
AudioPlayer
SpeechRecognizer
```

实现可以位于 infrastructure / data / core 层。

---

# 5. Module Structure

推荐工程模块：

```text
app
core
common
network
discovery
heartbeat
protocol
audio
service
storage
ui
settings
logger
```

模块不是越多越好。

如果某个模块只有极少量代码且没有真实边界，可以合并。

建议依赖方向：

```text
app
 ├── ui
 ├── service
 └── core

ui
 └── core

service
 └── core

core
 ├── common
 └── domain interfaces

network
 ├── protocol
 └── common

discovery
 ├── network
 └── protocol

heartbeat
 ├── network
 └── protocol

audio
 └── common

storage
 └── common

logger
 └── common
```

低层模块禁止反向依赖 UI。

---

# 6. Application Process Model

SaikaiPTT 的核心后台通信能力不能依赖 Activity 一直存在。

主要组件：

```text
MainActivity / Compose UI
        |
        v
Application-level state
        |
        v
Foreground Communication Service
        |
        +---- DiscoveryManager
        +---- HeartbeatManager
        +---- ConnectionManager
        +---- VoiceManager
        +---- OverlayController
```

Activity 负责：

- 显示 UI
- 发送用户操作

Foreground Service 负责：

- 后台通信生命周期
- 网络监听
- PTT 会话
- 后台接收
- 必要的状态协调

Service 不应该包含全部业务逻辑。

Service 只负责生命周期和组件编排。

---

# 7. Dependency Injection

优先使用：

constructor injection。

避免：

全局 Singleton 到处调用。

允许真正需要进程级唯一实例的对象使用 Application scope。

例如：

- SettingsRepository
- DeviceIdentityProvider
- Logger
- ServiceCoordinator

但不要把所有对象都做成 Singleton。

核心模块应容易进行单元测试。

---

# 8. Coroutines and Concurrency

使用 Kotlin Coroutines。

推荐：

```text
Dispatchers.Main
UI

Dispatchers.IO
Network / Storage

Default
CPU-heavy processing such as decoding/ASR preparation
```

音频实时路径必须避免：

- 主线程操作
- 不受控线程创建
- 无界 Coroutine
- 不必要的对象分配

使用结构化并发。

所有长期任务必须有明确的 coroutine scope 和 cancellation 生命周期。

---

# 9. Supervisor Strategy

后台通信中的单个子系统失败：

不应该导致整个 Service 崩溃。

推荐使用：

SupervisorJob

对独立组件进行隔离。

例如：

- Discovery 失败不应直接杀死 Voice
- ASR 失败不应影响 PTT
- History 写入失败不应停止网络通信

但如果核心网络栈本身无法运行，应通过明确状态通知上层。

---

# 10. Core Components

## 10.1 DeviceIdentityProvider

职责：

- 生成 UUID v4
- 持久化 Device ID
- 提供当前 Device ID

规则：

Device ID 一旦生成，正常情况下永久不变。

---

## 10.2 LocalUserRepository

职责：

- 保存用户名列表
- 获取当前用户名
- 新增用户名
- 修改用户名
- 删除用户名
- 切换用户名

---

## 10.3 PeerDiscovery

职责：

- 发现局域网设备
- 发布发现事件
- 更新设备 endpoint
- 去除失效设备

不负责：

- 判断长期在线状态
- 语音通信
- UI

---

## 10.4 PresenceService

职责：

- Heartbeat
- 在线超时
- Peer presence

Discovery 和 Presence 必须分离。

---

## 10.5 ProtocolEngine

职责：

- Packet encode
- Packet decode
- Packet validation
- Protocol version
- Sequence validation

ProtocolEngine 不负责 Socket 生命周期。

---

## 10.6 VoiceTransport

职责：

- UDP Socket 发送/接收
- Unicast endpoint
- Datagram handling

不负责：

- PTT UI
- 麦克风
- Room
- ASR

---

## 10.7 AudioRecorder

职责：

- 麦克风初始化
- PTT 音频采集
- 本地临时录音
- 编码/封装

---

## 10.8 AudioPlayer

职责：

- 低延迟播放
- 音频缓冲
- 停止/恢复

---

## 10.9 VoiceSessionManager

职责：

- PTT 状态机
- Sender / Receiver session
- Busy
- Force Interrupt
- Session timeout
- Sequence

---

## 10.10 HistoryRepository

职责：

- 保存通信记录
- 保存 transcript metadata
- 未读状态
- 收藏状态
- 查询历史

---

## 10.11 SpeechRecognizer

职责：

- 本地日语 ASR
- 音频输入转换
- Transcription result

不得依赖网络。

---

## 10.12 OverlayController

职责：

- 控制悬浮窗显示状态
- 绿色 / 红色状态
- 显示发送者名字
- 点击打开应用

---

# 11. Data Flow: Discovery

```text
WiFi
 ↓
Discovery transport
 ↓
Packet decode
 ↓
Packet validation
 ↓
PeerDiscovery
 ↓
PeerRepository
 ↓
Flow<State>
 ↓
ViewModel
 ↓
UI
```

UI 不直接读取：

NSD

或：

UDP Socket。

---

# 12. Data Flow: Heartbeat

```text
HeartbeatScheduler
 ↓
Heartbeat Packet
 ↓
UDP
 ↓
Remote Peer
 ↓
Heartbeat response
 ↓
PresenceService
 ↓
Peer State
```

Heartbeat 独立于 Discovery。

---

# 13. Data Flow: Outgoing PTT

```text
UI PTT press
 ↓
ViewModel
 ↓
StartPttUseCase
 ↓
VoiceSessionManager
 ↓
Busy/Target validation
 ↓
VOICE_START
 ↓
AudioRecorder
 ↓
Voice codec
 ↓
VoiceTransport
 ↓
Target peer
```

持续过程中：

```text
AudioRecord
 ↓
audio frame
 ↓
codec
 ↓
VOICE_DATA
 ↓
UDP Unicast
```

释放按钮：

```text
UI release
 ↓
StopPttUseCase
 ↓
VoiceSessionManager
 ↓
stop AudioRecorder
 ↓
VOICE_END
 ↓
finalize recording
 ↓
create history record
 ↓
queue ASR
```

---

# 14. Data Flow: Incoming PTT

```text
UDP
 ↓
VoiceTransport
 ↓
ProtocolEngine
 ↓
validation
 ↓
VoiceSessionManager
 ↓
receiver state
 ↓
AudioPlayer
```

同时：

```text
VoiceSessionManager
 ↓
App state event
 ↓
UI if foreground
```

以及：

```text
VoiceSessionManager
 ↓
OverlayController
 ↓
red overlay
```

后台接收时：

不依赖 Activity。

---

# 15. PTT State Machine

发送端：

```text
IDLE
  |
  v
REQUESTING
  |
  v
TRANSMITTING
  |
  v
ENDING
  |
  v
IDLE
```

异常：

```text
REQUESTING → FAILED → IDLE
TRANSMITTING → FAILED → IDLE
```

接收端：

```text
IDLE
  |
  v
RECEIVING
  |
  v
ENDING
  |
  v
IDLE
```

Force Interrupt：

```text
RECEIVING
   |
   v
INTERRUPTED
   |
   v
RECEIVING(new session)
```

所有状态转换必须有明确事件。

---

# 16. Session Identity

每次 PTT Session 应具有独立的 Session ID。

建议：

UUID 或随机高熵 ID。

作用：

- 区分连续 PTT
- 防止旧数据包污染新会话
- 处理重连
- 处理延迟数据包

Packet 中应包含：

Session ID

Sequence Number

二者职责不同：

Session ID：

识别“是哪一次对讲”。

Sequence：

识别“这一段语音数据在该会话中的顺序”。

---

# 17. Audio Pipeline

建议：

```text
Microphone
 ↓
AudioRecord
 ↓
PCM frame
 ↓
Voice codec
 ↓
packetizer
 ↓
UDP
```

接收：

```text
UDP
 ↓
packet parser
 ↓
sequence handling
 ↓
jitter buffer
 ↓
codec decoder
 ↓
AudioTrack
 ↓
Speaker
```

必须为网络抖动预留合理的 jitter buffer。

不要为了极端低延迟而取消必要的缓冲。

---

# 18. Audio Recording Pipeline

录音建议与实时发送路径解耦。

实时路径：

```text
AudioRecord
 ├── realtime encoder → UDP
 └── local recorder → local file
```

两个路径不能互相阻塞。

保存失败：

不允许导致：

实时 PTT 中断。

---

# 19. Codec Architecture

Codec 必须通过接口抽象。

例如：

```text
interface VoiceCodec {
    encode(...)
    decode(...)
}
```

默认实现：

Opus。

未来可以替换。

上层不得依赖 Opus API。

---

# 20. Network Transport Architecture

Transport 通过接口抽象：

```text
interface VoiceTransport

send(...)

receive(...)

start()

stop()
```

当前实现：

UDP Unicast。

未来可以替换其它局域网传输方式。

Protocol 层不得直接依赖：

DatagramSocket 的具体实现细节。

---

# 21. Discovery Architecture

Discovery 通过接口：

```text
interface PeerDiscovery {
    start()
    stop()
    peers()
}
```

当前首选：

Android NSD / mDNS。

如果实际设备测试发现部分局域网环境兼容性不足：

可以增加 UDP discovery fallback。

Fallback 不应改变上层 Peer 模型。

---

# 22. Presence Architecture

Heartbeat 采用：

独立 scheduler。

逻辑：

```text
send heartbeat
 ↓
receive response
 ↓
update lastSeen
 ↓
evaluate timeout
 ↓
ONLINE / OFFLINE
```

不要每次 heartbeat 都重新创建网络对象。

---

# 23. Network Recovery

网络状态变化：

```text
CONNECTED
    ↓
DISCONNECTED
    ↓
RECOVERING
    ↓
CONNECTED
```

Recovery 时：

1. 停止旧网络 endpoint。
2. 清理失效 IP。
3. 重新初始化 socket。
4. 重新 Discovery。
5. 重新建立 Heartbeat。
6. 更新 Peer endpoint。

Device ID 不变。

---

# 24. IP Address Handling

IP 地址只代表当前网络 endpoint。

一个 Peer：

```text
Device ID = persistent
IP = temporary
```

当 IP 改变：

不要创建新的设备。

应该：

更新已有 Peer 的 endpoint。

---

# 25. Foreground Service Architecture

Foreground Service 负责：

- 服务生命周期
- 启动/停止核心通信组件
- 持续通知
- 后台 PTT

不负责：

- UI state rendering
- database query implementation
- protocol serialization implementation
- audio codec implementation

Service 应通过 coordinator 管理核心组件。

---

# 26. Service Lifecycle

启动：

```text
START_REQUEST
 ↓
initialize dependencies
 ↓
start foreground
 ↓
start discovery
 ↓
start heartbeat
 ↓
start receive loop
 ↓
READY
```

停止：

```text
STOP_REQUEST
 ↓
stop PTT
 ↓
stop audio
 ↓
stop network
 ↓
stop discovery
 ↓
stop heartbeat
 ↓
release resources
 ↓
SERVICE_STOPPED
```

任何中间失败都必须安全释放资源。

---

# 27. Activity Lifecycle

Activity：

可以被：

- 创建
- 销毁
- 重建
- 进入后台
- 返回前台

核心 PTT 服务不能依赖 Activity 存活。

UI 使用：

StateFlow / Flow

观察：

- Peer state
- Session state
- Incoming PTT
- History state
- Service state

---

# 28. Compose State Management

UI 状态应保持：

immutable。

建议：

```text
HomeUiState
PttUiState
HistoryUiState
SettingsUiState
```

不要让多个 Compose Composable 直接修改共享业务状态。

统一通过：

Intent / Action

进入：

ViewModel。

---

# 29. Persistent Storage

DataStore：

用于：

- Device ID
- username list
- active username
- language
- feature settings
- history retention
- force interrupt preference

Room：

用于：

- CommunicationRecord
- transcript
- read state
- favorite state

Audio file：

单独保存在 App 私有目录。

---

# 30. Storage Consistency

删除历史记录时：

必须保证：

Room record

和：

Audio file

最终一致。

建议：

```text
mark/delete database record
 ↓
delete file
 ↓
cleanup orphan files
```

如果文件删除失败：

必须记录并允许后续 cleanup retry。

不要直接留下不可追踪的文件。

---

# 31. Background ASR Architecture

ASR 不得运行于：

UI thread。

也不得阻塞：

PTT receive/send path。

建议：

```text
PTT completed
 ↓
save audio
 ↓
create history record
 ↓
enqueue transcription job
 ↓
ASR worker
 ↓
update transcript
```

ASR worker 必须具备：

- cancellation
- retry
- failure state
- resource awareness

如果设备资源不足：

PTT 优先。

---

# 32. ASR Model Lifecycle

ASR 模型不是每次通信都重新加载。

应该：

- 按需加载
- 复用
- 空闲时释放（具体策略根据实际内存测试决定）

低端设备：

允许配置：

禁用 ASR。

默认：

ASR 关闭。

---

# 33. Logging Architecture

所有模块使用统一 Logger。

禁止：

直接大量使用 System.out。

日志等级：

DEBUG
INFO
WARN
ERROR

Release：

默认关闭 DEBUG。

Logger 不应成为性能瓶颈。

---

# 34. Error Model

业务层不要直接暴露底层异常类型。

建议：

```text
NetworkError
AudioError
PermissionError
StorageError
ProtocolError
SpeechRecognitionError
```

最终转换成：

Result / sealed error model。

UI 根据错误类型显示本地化信息。

---

# 35. Thread Safety

共享状态必须明确拥有者。

避免：

多个线程直接写同一个 mutable collection。

建议：

- StateFlow
- Mutex
- actor-like coroutine ownership
- thread-safe collections only where justified

网络接收线程不应直接修改 UI state。

---

# 36. Resource Management

以下资源必须保证释放：

- DatagramSocket
- AudioRecord
- AudioTrack
- file streams
- Room resources
- coroutine jobs
- overlay resources

使用：

try/finally

或：

use

进行资源生命周期管理。

---

# 37. Memory Strategy

重点避免：

- 大数组长期驻留
- 无限增长 List
- 无限增长日志
- 长期缓存音频
- 重复加载 ASR 模型
- 每个数据包创建大量临时对象

实时音频应考虑：

buffer reuse。

但在没有性能数据前，不要过度手工优化。

先建立清晰正确的实现，再根据实际设备 profiling 优化。

---

# 38. Performance Measurement

不能仅凭代码推测性能。

必须通过真实设备测量：

- startup time
- idle CPU
- PTT CPU
- memory
- battery
- network packet rate
- audio latency
- ASR processing time

至少包含：

MTK P22 / Android 11 / 4GB 类设备。

---

# 39. Privacy Boundaries

核心通信：

只在：

Local Network

发生。

应用不应：

- 上传录音
- 上传 transcript
- 使用云 ASR
- 上传 analytics 数据作为核心要求

调试日志也不得保存完整语音 payload。

---

# 40. Testability

每个核心模块都应能够独立测试。

例如：

```text
ProtocolEngine
PeerDiscovery
PresenceService
VoiceSessionManager
HistoryRepository
TranscriptManager
```

网络实现可以通过 fake transport 测试。

Audio 可以通过 fake recorder/player 测试状态机。

---

# 41. Architectural Constraints

以下约束属于强制：

1. UI 不直接访问 Socket。
2. UI 不直接操作 AudioRecord。
3. UI 不直接访问 Room DAO。
4. Discovery 不负责 Heartbeat。
5. Heartbeat 不负责 Discovery。
6. VoiceTransport 不负责 PTT 状态机。
7. ASR 不得阻塞 PTT。
8. Service 不承担所有业务逻辑。
9. IP 不等于 Device ID。
10. 语音必须点对点 Unicast。
11. 核心功能不能依赖 Internet。
12. 不得为了简单而破坏模块边界。

---

# 42. Architectural Decision Policy

如果开发过程中发现：

当前设计与实际 Android 行为、设备兼容性或性能测试结果存在冲突：

不要静默重构。

应：

1. 记录问题。
2. 分析影响。
3. 提出替代方案。
4. 选择方案。
5. 必要时创建 ADR。
6. 更新相关架构文档。

---

# 43. Architecture Definition of Done

架构阶段完成时必须至少明确：

- 模块划分
- 依赖方向
- 核心接口
- 数据流
- PTT 状态机
- 网络状态机
- Service 生命周期
- Audio pipeline
- Storage pipeline
- ASR pipeline
- Error model
- Logging model
- Testing strategy

在这些内容明确以前：

不要开始大规模业务编码。

---

# 44. Implementation Philosophy

不要为了“看起来企业级”而过度设计。

目标不是拥有最多：

- interface
- module
- class
- abstraction

目标是：

> 在保持清晰边界的同时，让 SaikaiPTT 尽可能简单、稳定、轻量。

每增加一个抽象层：

都应有明确理由。

每增加一个第三方依赖：

都应有成本评估。

每增加一个后台任务：

都必须说明其生命周期和功耗影响。

---

# 45. Final Architecture Principle

SaikaiPTT 的架构最终应满足：

```text
Simple UI
     ↓
Clear Business Layer
     ↓
Stable Interfaces
     ↓
Independent Network / Audio / Storage Components
     ↓
Minimal Android Runtime Dependencies
```

所有复杂性应该被封装在内部，而不是暴露给用户。

用户看到的应该是：

一个简单的对讲机。

开发者看到的应该是：

一个有清晰边界、可测试、可维护、可扩展的 Android 工程。
