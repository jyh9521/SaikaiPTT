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
- Dependency Injection（手写轻量容器，见 §7）
- Interface-first design
- Structured Concurrency

## 3.1 规范性 ADR

以下 ADR 是本文档的规范性组成部分，冲突时**以 ADR 为准**：

| ADR | 覆盖内容 |
|---|---|
| ADR-001 | 设备发现策略 |
| ADR-002 | 传输层与端口方案 |
| ADR-003 | 协议二进制线格式 |
| ADR-004 | 音频参数与 AudioFocus 策略 |
| ADR-005 | 前台服务、Android 版本兼容与电源锁 |
| ADR-006 | 离线日语 ASR 引擎与模型分发 |

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
- OnIncomingPttStarted（网络事件，**不是**用户确认动作）
- OnIncomingPttEnded
- HandleBusy
- SetAllowInterrupt（接收方策略开关）
- ObserveHistory
- ReplayRecording
- DeleteRecord
- RunTranscription
- ChangeActiveUser
- ChangeLanguage

命名说明：

原 `AcceptIncomingPtt` 已改名。产品要求接收方**不需要接听、不需要确认**（`01_PRD §14`），带 `Accept` 的命名容易被误实现为用户确认动作。

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

## 5.1 v1 的实际拆分

**初期只拆两个 Gradle module：**

```text
app        Android 应用、UI、Service、DI 装配
core       纯 Kotlin/JVM，业务与领域逻辑
```

其余边界用 **package** 表达，不建独立 Gradle module：

```text
core/
  common/        工具、Result、错误模型
  config/        集中配置
  logger/        日志抽象
  protocol/      packet 编解码与校验（无 Android 依赖）
  domain/        Peer、Session、LocalUser、History 领域模型与接口
  session/       PTT 状态机

app/
  network/       socket、接收循环、发送器
  discovery/     UDP 广播发现
  presence/      心跳与在线状态
  audio/         AudioRecord / AudioTrack / Codec / jitter buffer
  storage/       DataStore、Room、文件
  service/       Foreground Service 与 coordinator
  overlay/       悬浮窗
  asr/           离线识别
  ui/            Compose、ViewModel
  di/            依赖装配
```

理由：

`core` 无 Android 依赖，可用纯 JVM 单元测试快速验证协议、状态机与领域逻辑——这是测试收益最大的一刀。再细分会带来 Gradle 配置成本却没有真实边界收益（`§44`）。

只有当某个 package 出现真实的复用需求或编译时间问题时，才提升为独立 module，并记录 ADR。

## 5.2 依赖方向

```text
app  ──────────────► core
 │
 ├─ ui        ─► core.domain, core.config
 ├─ service   ─► core.session, core.domain, app.network, app.discovery,
 │                app.presence, app.audio, app.overlay
 ├─ network   ─► core.protocol, core.config, core.common
 ├─ discovery ─► app.network, core.protocol
 ├─ presence  ─► app.network, core.protocol
 ├─ audio     ─► core.config, core.common
 ├─ storage   ─► core.domain, core.common
 ├─ asr       ─► core.domain, app.storage
 └─ di        ─► 以上全部（唯一允许知晓所有实现的地方）

core.session   ─► core.protocol, core.domain, core.config
core.protocol  ─► core.common, core.config
core.domain    ─► core.common
core.logger    ─► core.common
core.config    ─► core.common
```

强制约束：

- `core` **不得**依赖任何 Android SDK 类型（`android.*`、`androidx.*`）。
- 任何 package **不得**反向依赖 `ui`。
- 只有 `app.di` 可以同时引用接口与实现。
- 不允许循环依赖，由 Gradle / lint 检查。

## 5.3 修订说明

原依赖图中 `service` 只依赖 `core`，与 `§6` 中「Service 持有 DiscoveryManager / HeartbeatManager / ConnectionManager / VoiceManager」自相矛盾——Service 无法只依赖 core 就取得这些实现。

现明确：**Service 依赖这些子系统的接口（在 core）与实现（在 app），实例由 `app.di` 装配后注入 Service。**

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

## 7.1 决策

**使用手写的轻量依赖容器，不引入 DI 框架。**

形式：

一个 `AppContainer`（Application scope）持有进程级唯一实例，Service 与 ViewModel 通过构造参数或 Factory 取得依赖。

```text
AppContainer
 ├── config: AppConfig
 ├── logger: Logger
 ├── settingsRepository: SettingsRepository
 ├── deviceIdentityProvider: DeviceIdentityProvider
 ├── localUserRepository: LocalUserRepository
 ├── historyRepository: HistoryRepository
 └── communicationContainer (Service scope, 随 Service 生命周期创建/销毁)
      ├── transport, discovery, presence
      ├── sessionManager
      ├── audioRecorder, audioPlayer, codec
      └── overlayController
```

## 7.2 理由

- 对象总数在数十个量级，手写容器完全可控。
- 避免 KSP/注解处理带来的构建时间与 APK 体积成本，符合 `§41 依赖政策`。
- 依赖关系在代码中显式可读，无生成代码，便于排查。
- 单元测试直接构造对象，无需框架支持。

若后期对象图确实失控，再评估引入 Hilt 并记录 ADR。

## 7.3 规则

- 优先 constructor injection。
- 允许真正需要进程级唯一实例的对象位于 Application scope（Config、Logger、SettingsRepository、DeviceIdentityProvider、HistoryRepository）。
- 通信相关组件属于 **Service scope**，随 Service 创建与销毁，避免 Service 停止后仍有对象持有 socket 或音频资源。
- 不要把所有对象都做成 Singleton。
- 核心模块必须能在没有容器的情况下直接 new 出来做单元测试。

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

UI 不直接读取 UDP Socket，也不直接解析 Packet。

Peer 状态只通过 `PeerRepository` 暴露的 Flow 到达 ViewModel。

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

# 17. Audio Architecture

完整参数由 `docs/ADR/ADR-004-Audio-Params.md` 定义。本节为规范性摘要。

（本节同时填补了原先 `04_UI_UX §44` 指向「Audio Architecture 决定」但架构文档未定义的空缺。）

## 17.1 音频参数（v1 固定）

| 参数 | 值 |
|---|---|
| 采样率 | 16000 Hz |
| 声道 | 单声道 |
| 采样格式 | PCM 16-bit |
| 帧长 | 20 ms（320 samples / 640 bytes PCM） |
| Opus 模式 | `OPUS_APPLICATION_VOIP` |
| Opus 码率 | 20 kbps CBR |
| Opus complexity | 3 |
| Opus inband FEC | 开启 |
| Opus DTX | 关闭 |

## 17.2 采集

- `AudioSource` 优先 `VOICE_COMMUNICATION`（平台 AEC/NS/AGC，适合噪声环境），初始化失败回退 `MIC`。两者在 Config 中可切换。
- `AudioRecord` buffer = `max(minBufferSize, 4 × frameBytes)`。
- 专用线程，优先级 `THREAD_PRIORITY_URGENT_AUDIO`。
- 缓冲区预分配复用，禁止每帧分配。

## 17.3 播放

- `AudioTrack`，`AudioAttributes` = `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`，`PERFORMANCE_MODE_LOW_LATENCY`。
- `AudioManager.mode` 保持 `MODE_NORMAL`。PTT 为半双工，麦克风与扬声器不同时工作，不存在回声路径，无需进入 `MODE_IN_COMMUNICATION`（后者会改变全局音量流并影响其他应用）。
- 音量走 `STREAM_VOICE_CALL`；默认扬声器输出，插入耳机/蓝牙时跟随系统路由。

## 17.4 发送管线

```text
Microphone
 ↓
AudioRecord (20ms PCM frame)
 ↓
Opus encoder
 ↓
 ├──► packetizer ─► VOICE_DATA ─► UDP unicast
 └──► Ogg/Opus writer ─► 本地录音文件
```

**同一份编码帧同时用于发送与落盘，不做二次编码。**

## 17.5 接收管线

```text
UDP (voice socket)
 ↓
packet parse + validate
 ↓
sequence 去重 / 排序
 ↓
jitter buffer
 ↓
 ├──► Opus decoder ─► AudioTrack ─► Speaker
 └──► Ogg/Opus writer ─► 本地录音文件（写入原始编码帧）
```

**接收端录音写入的是收到的原始 Opus 帧，不做「解码后重编码」。**

## 17.6 Jitter Buffer

| 参数 | 值 |
|---|---|
| 起播门限 | 3 帧（60 ms） |
| 目标深度 | 3 帧 |
| 最大深度 | 10 帧（200 ms），超出丢弃最旧帧 |
| 迟到包 | 已越过播放点的包直接丢弃 |
| 丢帧 | v1 插入静音帧，不做 PLC（Opus inband FEC 已覆盖单帧丢失） |

不要为了极端低延迟而取消必要的缓冲。

## 17.7 AudioFocus 策略

- 会话开始（发送或接收）申请 `AUDIOFOCUS_GAIN_TRANSIENT`，结束立即释放。
- `AUDIOFOCUS_LOSS`（如来电接通）：**立即终止当前会话**。发送方停止采集并发 `VOICE_END`；接收方停止播放并标记 `INTERRUPTED`。
- `AUDIOFOCUS_LOSS_TRANSIENT`：同上。对讲语义是实时的，「暂停后恢复」没有意义。
- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`：不降低音量，按 `LOSS_TRANSIENT` 处理（语音可懂度优先）。
- 麦克风被占用（初始化或 `startRecording` 失败）：返回 `MICROPHONE_UNAVAILABLE`，不进入 TRANSMITTING。

## 17.8 Codec 抽象

```text
interface VoiceCodec {
    val frameSizeSamples: Int
    val maxEncodedBytes: Int
    fun encode(pcm: ShortArray, out: ByteArray): Int
    fun decode(encoded: ByteArray, len: Int, out: ShortArray): Int
    fun release()
}
```

默认实现 Opus。上层不得依赖 Opus API。

Opus 需要原生库，必须提供 **16 KB page size 对齐**的 `.so`（Android 15+），ABI 至少覆盖 `arm64-v8a` 与 `armeabi-v7a`。

# 18. Audio Recording Pipeline

录音路径与实时发送路径**共享编码结果，但在写入上互相隔离**。

```text
Opus encoded frame
 ├── 实时路径：packetizer → UDP        （不得被文件 I/O 阻塞）
 └── 录音路径：有界队列 → 写盘线程 → Ogg/Opus 文件
```

规则：

- 录音写盘在**独立线程**，通过**有界队列**（容量 = 5 秒帧数）与实时路径解耦。
- 队列满时：**丢弃录音帧并记录一次 WARN**，绝不阻塞实时路径。
- 录音写入失败（磁盘满、I/O 错误）：
  - **实时 PTT 必须继续**
  - 本次记录 `status = FAILED`，`audioPath = null`
  - 向 UI 报告 `StorageError`
  - 不 Crash
- 临时文件写入 `records/.tmp/`，`VOICE_END` 后 finalize 并移动到正式目录（`05_DataModel §32`）。
- 只有 finalized 的文件才进入常规清理范围。

保存失败不允许导致实时 PTT 中断。

# 19. Codec Architecture

见 `§17.8`。

Codec 必须通过接口抽象，默认实现 Opus，上层不得依赖 Opus API。

若最终选定的 Opus 库无法满足 Android 11 兼容或 16 KB 对齐要求，回退方案为平台 `MediaCodec` AAC-LC（16 kHz 单声道，24 kbps），并**同步升 ProtocolVersion 至 2**。该回退必须另立 ADR，不得静默切换。

# 20. Network Transport Architecture

传输方案由 `docs/ADR/ADR-002-Transport-And-Ports.md` 定义。

```text
interface VoiceTransport {
    fun start()
    fun stop()
    fun send(packet: Packet, endpoint: Endpoint)
    fun incoming(): Flow<RawDatagram>
}
```

v1 实现：

**两个 UDP socket，两个专用接收线程。**

| 用途 | 默认端口 | 承载 |
|---|---|---|
| Discovery + Control | 45820（固定，被占用时 +1 最多 4 次） | 发现、心跳、会话建立与终止 |
| Voice | 45821（浮动，通过心跳通告） | VOICE_DATA |

理由：

语音包约 50 包/秒，控制包约 0.2 包/秒。分离后，控制包的解析与状态机跳转不会阻塞语音接收线程。

规则：

- Protocol 层不得直接依赖 `DatagramSocket` 的实现细节。
- 两个接收线程均使用预分配并复用的 `ByteArray`（2048 字节），禁止每包分配。
- 语音发送为 Unicast，目的地址 = 对端 endpoint IP + 对端通告的 voicePort。
- 广播只在控制端口发生。

未来可以替换其它局域网传输方式而不影响上层。

# 21. Discovery Architecture

发现策略由 `docs/ADR/ADR-001-Discovery-Strategy.md` 定义：

> **v1 采用纯 UDP 广播发现，不实现 NSD / mDNS。**

```text
interface PeerDiscovery {
    fun start()
    fun stop()
    fun announce()          // 上线 / 网络恢复 / 改名时主动通告
    fun events(): Flow<PeerDiscoveryEvent>
}
```

行为：

- 上线、网络恢复、Active User 变更时广播 DISCOVERY，在 `0 / 300 / 900 ms` 各发一次。
- 收到 DISCOVERY 立即单播回 DISCOVERY_RESPONSE。
- 广播地址优先使用接口子网定向广播，失败回退 `255.255.255.255`。
- 过滤 SenderDeviceId 等于本机的回环包。

## 21.1 为什么放弃 NSD/mDNS

- `NsdManager` 在 minSdk 30 与 API 34+ 之间 API 不同（`registerServiceInfoCallback` / `resolveService` 各只在一侧可用），必须维护两条代码路径。
- 参考低端设备为 MTK P22 / Android 11 类机型，此类 ROM 上 NSD 稳定性历史表现不佳，与「低端兼容是一等需求」冲突。
- UDP 广播可与心跳共用 socket 与 payload，待机流量与功耗可预测。
- 用户名变更可即时随下一个广播生效，无需重新注册服务记录。

已知限制：

部分企业级 AP 开启 AP Isolation 时无法发现。此限制写入 README，v1 不提供手动输入 IP 的补救手段。

`PeerDiscovery` 接口保持稳定，未来若需要 NSD 可作为第二实现接入，不影响上层 Peer 模型。

# 22. Presence Architecture

Heartbeat 使用独立 scheduler，与 Discovery 分离。

```text
周期广播 HEARTBEAT（含 peerState）
 ↓
收到任意有效包 → 更新 lastSeen
 ↓
周期评估超时
 ↓
ONLINE / OFFLINE
```

## 22.1 广播而非逐 Peer 单播

逐 Peer 单播时，N 台设备每周期产生 N×(N-1) 个包；广播时全网只有 N 个包。

这对「待机流量合理」「待机 CPU 尽量接近 0」是决定性的。

## 22.2 忙线状态通告

HEARTBEAT payload 中的 `peerState`（IDLE / BUSY）是第三方设备获知忙线的**唯一途径**，驱动 `Peer.BUSY` 状态与设备列表的「通話中」显示。

状态变化时**立即额外广播一次**，不等待下一周期，使对端 UI 在数百毫秒内更新。

## 22.3 时间参数

| 参数 | 默认值 |
|---|---|
| `heartbeatIntervalMs` | 5000 |
| `peerTimeoutMs` | 16000（3 个周期 + 容差） |
| `presenceEvaluationIntervalMs` | 2000 |

单次丢包不得立即判定离线。

不要每次 heartbeat 都重新创建网络对象。

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

完整平台约束见 `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`。

Foreground Service 负责：

- 服务生命周期
- 启动/停止核心通信组件
- 持续通知
- 后台接收

不负责：

- UI state rendering
- database query implementation
- protocol serialization implementation
- audio codec implementation

Service 通过 coordinator 管理核心组件，组件实例由 `app.di` 的 Service scope 容器装配。

## 25.1 前台服务类型

```xml
<service android:name=".service.CommunicationService"
         android:exported="false"
         android:foregroundServiceType="connectedDevice|microphone" />
```

运行时切换：

| 状态 | `startForeground` 使用的类型 |
|---|---|
| 常驻（发现 / 心跳 / 接收播放） | `connectedDevice` |
| 用户按住 PTT 发送期间 | `connectedDevice \| microphone` |

**接收与播放不需要麦克风**，因此后台接收不受麦克风类型限制影响——这是本产品最关键的后台能力。

**发送需要麦克风**，Android 14+ 禁止从后台提升为麦克风类型，因此发送要求应用界面可见（`01_PRD §10.5`）。

## 25.2 电源锁

| 锁 | 持有时机 |
|---|---|
| `MulticastLock` | 服务 READY 期间常驻（不持有则熄屏后收不到广播心跳） |
| `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` | 仅语音会话期间 |
| `PARTIAL_WAKE_LOCK`（超时上限 5 分钟） | 仅语音会话期间 |

除以上三处外，禁止持有任何电源锁。

## 25.3 开机与进程死亡

- `BOOT_COMPLETED` → 以 `connectedDevice` 类型启动，处于「可接收、不可发送」状态。
- `START_STICKY`；Android 12+ 禁止从后台启动 FGS，因此**不承诺**进程被杀后自动恢复通信。
- 本地数据（DataStore / Room / 音频文件）在任何情况下不得损坏。

# 26. Service Lifecycle

启动：

```text
START_REQUEST
 ↓
initialize dependencies (Service scope container)
 ↓
startForeground(connectedDevice) + 通知
 ↓
acquire MulticastLock
 ↓
open controlSocket (45820) + voiceSocket
 ↓
start control-rx / voice-rx 接收循环
 ↓
start discovery (announce ×3)
 ↓
start heartbeat scheduler
 ↓
READY
```

停止：

```text
STOP_REQUEST
 ↓
stop PTT / terminate active session
 ↓
release AudioFocus, stop AudioRecord/AudioTrack
 ↓
release WifiLock / WakeLock
 ↓
stop heartbeat
 ↓
stop discovery
 ↓
stop 接收循环 (cancel scope, join)
 ↓
close sockets
 ↓
release MulticastLock
 ↓
close Service scope container
 ↓
stopForeground + SERVICE_STOPPED
```

要求：

- 每个启动步骤失败时，必须回滚已完成的步骤（`try/finally` 或倒序释放列表）。
- 任何中间失败都必须安全释放资源，并以 `SERVICE_START_FAILED` 通知上层。
- 停止流程必须幂等，重复调用不得抛异常。

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

- `device_id`
- `local_users`（用户名列表）
- `active_user_id`
- `app_language`
- `allow_interrupt`（接收方策略，默认 false）
- `history_retention`（默认 7_DAYS）
- `asr_enabled`（默认 false）
- `asr_model_ready`
- `overlay_enabled`

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

不能仅凭代码推测性能。必须通过真实设备测量。

目标值以 `01_PRD §41` 为准，全部按**单核占用百分比**计：

| 指标 | 目标 |
|---|---|
| 待机 CPU | < 1% of one core |
| 对讲 CPU（发送） | ≤ 15% of one core |
| 对讲 CPU（接收） | ≤ 12% of one core |
| Java heap（待机） | < 32 MB |
| 总 PSS（待机，不含 ASR 模型） | < 130 MB |
| 端到端延迟 | P50 ≤ 250 ms，P95 ≤ 400 ms |
| 待机网络 | ≤ 1 广播包 / 5 秒 / 设备 |

必须测量：

- startup time
- idle CPU
- PTT CPU（发送 / 接收分别测）
- Java heap 与 PSS
- battery
- network packet rate
- audio latency（P50 / P95 / max）
- ASR processing time

至少包含：

MTK P22 / Android 11 / 4GB 类设备，以及现代旗舰设备。

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

- 模块划分（§5.1）
- 依赖方向（§5.2）
- DI 方案（§7）
- 核心接口
- 数据流
- PTT 状态机（含 VOICE_ACCEPT 的会话建立）
- 网络状态机
- Service 生命周期与前台服务类型（§25、§26）
- 电源锁策略（§25.2）
- Audio 参数、管线与 AudioFocus 策略（§17）
- Jitter buffer 参数（§17.6）
- Storage pipeline
- ASR pipeline 与模型分发（ADR-006）
- Error model
- Logging model
- Testing strategy
- 性能目标口径（§38）

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
