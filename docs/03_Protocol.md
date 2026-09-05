# SaikaiPTT Network Protocol Specification

## 1. 文档目的

本文档定义 SaikaiPTT 的局域网通信协议。

## 1.1 规范性来源

以下 ADR 是本文档的规范性组成部分。冲突时**以 ADR 为准**：

| ADR | 覆盖内容 |
|---|---|
| `docs/ADR/ADR-001-Discovery-Strategy.md` | 纯 UDP 广播发现，不使用 NSD / mDNS |
| `docs/ADR/ADR-002-Transport-And-Ports.md` | 两个 socket / 两个接收线程 / 端口分配 |
| `docs/ADR/ADR-003-Wire-Format.md` | 72 字节头部的完整二进制布局、包类型、Payload、校验顺序 |
| `docs/ADR/ADR-004-Audio-Params.md` | 音频参数、Opus 参数、jitter buffer |

## 1.2 覆盖范围

- 设备发现
- Heartbeat 与忙线状态通告
- 在线状态
- Peer Endpoint
- PTT Session 建立、接受、拒绝、终止
- VOICE_START / VOICE_ACCEPT / VOICE_DATA / VOICE_END
- BUSY / SESSION_TERMINATE
- PING / PONG
- Packet version
- Sequence 与回绕
- Session ID
- 超时、丢包、乱序、重复包、非法数据
- 网络恢复

## 1.3 首要目标

> 在同一 WiFi 局域网内实现稳定、低延迟、点对点的 PTT 语音通信。

# 2. 核心原则

## 2.1 Discovery 使用 UDP 广播

为了发现局域网设备，允许使用广播。

**v1 的发现机制是纯 UDP 广播**（`docs/ADR/ADR-001-Discovery-Strategy.md`）：

- DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT 走同一个控制 socket
- 不实现 mDNS / NSD
- 不使用 Multicast

选择理由与已知限制见 ADR-001 与 §10.6。

## 2.2 Voice 必须 Unicast

VOICE 数据：

必须发送到明确的目标 Peer。

禁止：

- UDP broadcast voice
- multicast voice
- 向所有在线设备发送语音

一次 PTT Session：

只有：

一个 Sender

和：

一个 Receiver。

---

# 3. Protocol Version

协议必须具有版本号。

初始版本：

Protocol Version = 1

版本号用于：

- 判断兼容性
- 防止新旧客户端错误解析
- 支持未来协议升级

收到无法处理的版本：

不得 Crash。

应安全忽略或返回明确的兼容性错误。

---

# 4. Device Identity

每个设备具有永久：

Device ID。

格式：

UUID v4。

Device ID：

- 初始化时生成
- 保存至 DataStore
- 正常情况下永久不变

不要使用：

- IP
- MAC
- IMEI
- Serial Number

作为逻辑身份。

IP 只表示当前 endpoint。

---

# 5. User Identity

每台设备可以保存多个本地用户名称。

当前 Active User：

在通信时作为 metadata 发送。

远程 Peer：

不需要提前保存该用户名。

例如：

Device ID：

A

User Name：

张三

远程收到后：

显示：

张三。

## 5.1 User Name 长度限制

线路上的 UserName 为 UTF-8 编码，前置 1 字节长度。

硬性上限：

**64 字节 UTF-8**。

UI 侧同时限制：

**24 个码位（code point）**。

两者取严。

原因：

缅甸语和孟加拉语单个字符的 UTF-8 编码可达 3~4 字节，仅按字符数限制无法约束包长。

超过上限的入站 UserName：

整包丢弃（见 §29）。

# 6. Network Ports

传输方案由 `docs/ADR/ADR-002-Transport-And-Ports.md` 定义。本节为其规范性摘要。

采用：

**两个 UDP socket，两个专用接收线程。**

| 用途 | 默认端口 | Socket | 接收线程 | 承载包类型 |
|---|---|---|---|---|
| Discovery + Control | UDP **45820** | `controlSocket`，bind `0.0.0.0:45820`，`setBroadcast(true)` | `control-rx` | DISCOVERY, DISCOVERY_RESPONSE, HEARTBEAT, PING, PONG, VOICE_START, VOICE_ACCEPT, VOICE_END, BUSY, SESSION_TERMINATE |
| Voice | UDP **45821** | `voiceSocket`，bind `0.0.0.0:45821` | `voice-rx` | VOICE_DATA |

规则：

- 端口号集中定义在 Config，禁止散落到多个类。
- 控制端口必须固定。被占用时依次尝试 +1，最多 4 次；若全部失败，服务以 `SERVICE_START_FAILED` 结束并向 UI 报告。
- 语音端口允许浮动。本机实际使用的语音端口通过 DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT 的 payload 通告给对端。
- 语音发送为 Unicast，目的地址 = 对端 endpoint IP + 对端通告的 voicePort。
- 广播只在控制端口上发生。

分离两个 socket 的理由：

语音包约 50 包/秒，控制包约 0.2 包/秒。分离后，控制包的解析、Peer 表更新和状态机跳转不会阻塞语音接收线程，抖动可控。

# 7. Packet Structure

完整线格式由 `docs/ADR/ADR-003-Wire-Format.md` 定义。本节为规范性摘要，实现必须与 ADR-003 逐字节一致。

## 7.1 通用规则

- 字节序：**大端（网络字节序）**。
- 每个 Packet = 固定 **72 字节头部** + `0..1024` 字节 Payload。
- Device ID 与 Session ID 在线路上为 **16 字节 UUID 二进制**，不是 36 字节 ASCII。全零表示「无 / 广播」。
- 单个 UDP 数据报最大 `72 + 1024 = 1096` 字节，明显小于 1500 MTU，不产生 IP 分片。
- `VOICE_DATA` 的 Payload 额外限制为 **≤ 400 字节**。

## 7.2 头部布局（72 bytes）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 0 | 4 | Magic | bytes | 固定 `0x53 0x4B 0x50 0x54`（ASCII `SKPT`） |
| 4 | 1 | ProtocolVersion | u8 | v1 = `0x01` |
| 5 | 1 | PacketType | u8 | 见 §8 |
| 6 | 2 | Flags | u16 | v1 全部保留，发送方必须置 0，接收方必须忽略未知位 |
| 8 | 2 | PayloadLength | u16 | **仅 Payload 字节数**，不含头部 |
| 10 | 2 | Reserved | u16 | 必须为 0 |
| 12 | 16 | SenderDeviceId | uuid | 发送方 Device ID |
| 28 | 16 | TargetDeviceId | uuid | 目标 Device ID；广播类包为全零 |
| 44 | 16 | SessionId | uuid | 非会话包为全零 |
| 60 | 4 | SequenceNumber | u32 | 见 §17 |
| 64 | 8 | Timestamp | i64 | UTC epoch milliseconds |

## 7.3 字段职责

**Magic**：防止把局域网内其它 UDP 流量误解析为 SaikaiPTT Packet。

**ProtocolVersion**：兼容性判断。

**PacketType**：见 §8。

**Flags**：预留扩展位。

**PayloadLength**：Payload 字节数。校验时必须满足 `72 + PayloadLength == 数据报实际长度`。

**SenderDeviceId / TargetDeviceId**：身份，不是地址。IP 只是当前 endpoint。

**SessionId**：识别「是哪一次对讲」。

**SequenceNumber**：识别「这一段语音数据在该会话中的顺序」。

**Timestamp**：用于日志、超时判断和异常数据识别。**不得**作为语音顺序的依据。

# 8. Packet Types

| 值 | 名称 | 通道 | v1 状态 | 说明 |
|---|---|---|---|---|
| `0x01` | DISCOVERY | 控制（广播） | 实现 | 上线 / 网络恢复 / 改名时主动通告 |
| `0x02` | DISCOVERY_RESPONSE | 控制（单播） | 实现 | 对 DISCOVERY 的即时回应 |
| `0x10` | HEARTBEAT | 控制（广播） | 实现 | 周期性在线 + 忙线状态通告 |
| `0x11` | PING | 控制（单播） | 实现 | 连通性探测，仅调试与网络恢复时使用 |
| `0x12` | PONG | 控制（单播） | 实现 | PING 应答 |
| `0x20` | VOICE_START | 控制（单播） | 实现 | 请求建立 PTT 会话 |
| `0x21` | VOICE_ACCEPT | 控制（单播） | 实现 | 接受会话 |
| `0x22` | VOICE_DATA | 语音（单播） | 实现 | 编码音频帧 |
| `0x23` | VOICE_END | 控制（单播） | 实现 | 发送方正常结束 |
| `0x24` | BUSY | 控制（单播） | 实现 | 拒绝：目标忙线且不允许被打断 |
| `0x25` | SESSION_TERMINATE | 控制（单播） | 实现 | 由接收方终止会话 |
| `0x30` | FORCE_INTERRUPT | — | **保留，不使用** | 见 §33，强插由接收方策略实现，无需独立包类型 |
| `0x31` | ERROR | — | 保留 | |
| `0x32` | GOODBYE | — | 保留 | |
| `0x33` | CAPABILITIES | — | 保留 | |

收到未知 PacketType：

安全丢弃。

Debug 构建下按不超过 1 次/秒的频率记录日志。

Release 构建下不打印。

# 9. Discovery Packet

发现策略由 `docs/ADR/ADR-001-Discovery-Strategy.md` 定义：

> **v1 采用纯 UDP 广播发现，不实现 NSD / mDNS。**

DISCOVERY 用于回答：

> 我是谁，我在这个局域网里，我的语音端口是多少，我现在忙不忙。

DISCOVERY、DISCOVERY_RESPONSE 与 HEARTBEAT **共用同一份 Payload 结构**：

| 长度 | 字段 | 说明 |
|---|---|---|
| 1 | peerState | `0x00` IDLE，`0x01` BUSY |
| 2 | voicePort | u16，本机实际监听的语音端口 |
| 1 | userNameLen | 1..64 |
| n | userName | UTF-8，见 §5.1 |

其余身份与版本信息位于 72 字节头部（SenderDeviceId、ProtocolVersion、Timestamp），不在 Payload 中重复。

DISCOVERY 不代表：

永久在线。

持续在线状态由 HEARTBEAT 判断（§13）。

# 10. Discovery Behavior

## 10.1 主动通告

以下事件发生时，广播 DISCOVERY：

- 通信服务进入 READY
- 网络恢复完成（§40、§41）
- Active User 变更（用户改名或切换名称）

每次通告在 `0ms / 300ms / 900ms` 各发送一次，共 3 包。

理由：

只发一次时，单次丢包会导致双方长时间互相不可见。

## 10.2 广播地址

优先使用当前 WiFi 接口的**子网定向广播地址**（由接口 IP 与掩码计算）。

计算失败时回退到 `255.255.255.255`。

## 10.3 即时应答

收到 DISCOVERY 的设备立即向来源单播 DISCOVERY_RESPONSE。

理由：

使新加入的设备无需等待一个心跳周期即可看到全网设备。

## 10.4 Peer 记录

收到 DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT 后记录：

- Device ID
- User Name
- IP
- voicePort
- peerState
- protocolVersion
- lastSeen

如果 Device ID 已存在：

更新 endpoint 与 userName。

不能创建重复 Peer。

## 10.5 回环过滤

SenderDeviceId 等于本机 Device ID 的包一律丢弃（本机会收到自己发出的广播）。

## 10.6 已知限制

部分企业级 AP 开启 AP Isolation 或禁用广播转发时，设备之间无法互相发现。

此限制必须写入 README 的「已知限制」。

v1 不提供手动输入 IP 的补救手段。

# 11. Endpoint 更新

同一个 Device ID：

如果 IP 地址改变：

只更新 endpoint。

不能视为新设备。

例如：

```text
Device ID: A
Old IP: 192.168.1.10
New IP: 192.168.1.25
```

仍然是同一个 Peer。

---

# 12. Heartbeat Packet

HEARTBEAT 用于确认 Peer 仍在线，并通告本机的忙线状态。

传输方式：

**广播**，与 DISCOVERY 使用同一个控制 socket 和同一份 Payload 结构（§9）。

Payload：

| 长度 | 字段 | 说明 |
|---|---|---|
| 1 | peerState | `0x00` IDLE，`0x01` BUSY |
| 2 | voicePort | u16 |
| 1 | userNameLen | 1..64 |
| n | userName | UTF-8 |

## 12.1 为什么是广播而不是逐 Peer 单播

逐 Peer 单播时，N 台设备每周期产生 N×(N-1) 个包。广播时每台设备每周期只发 1 个包，全网 N 个包。

这对「待机流量合理」「待机 CPU 尽量接近 0」的要求是决定性的。

## 12.2 忙线状态的传播

`peerState` 是**第三方设备获知忙线状态的唯一途径**。

`04_UI_UX §9/§11` 要求在设备列表中显示「通話中」，`Peer.BUSY` 状态即由此字段驱动。

规则：

- 本机存在任何活动语音会话（发送或接收）时，`peerState = BUSY`；否则 `IDLE`。
- `peerState` 发生变化时，**立即额外广播一次 HEARTBEAT**，不等待下一个周期。这样对讲开始/结束时，其它设备的 UI 能在数百毫秒内更新，而不是最长 5 秒。
- 立即广播使用与 §10.1 相同的通道，但只发 1 包（状态变化频率远低于上线事件，无需三连发）。

## 12.3 PING / PONG 不参与在线判定

在线状态只由 DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT 的 lastSeen 决定。

PING / PONG 仅用于调试与网络恢复探测（§15）。

# 13. Heartbeat Timing

以下为 v1 默认值，全部集中定义在 Config，必须通过低端设备实测复核。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `heartbeatIntervalMs` | **5000** | 广播周期 |
| `peerTimeoutMs` | **16000** | 超过此时长未收到该 Peer 的任何有效包 → OFFLINE |
| `peerTimeoutMissedIntervals` | 3 | `peerTimeoutMs` 的推导依据（3 × 5000 + 1000 容差） |
| `presenceEvaluationIntervalMs` | 2000 | 超时评估的扫描周期 |

规则：

- 单次丢包**不得**立即判定离线。必须连续错过 3 个周期。
- 任何来自该 Peer 的有效包（含 VOICE_* 与 PONG）都刷新 lastSeen。
- Peer 从 OFFLINE 恢复：收到任意有效包即刻转回 ONLINE，不需要额外握手。
- 心跳广播不得为每次发送重新创建 socket 或 DatagramPacket 对象（`02_Architecture §22`）。

# 14. Presence State

逻辑状态：

```text
DISCOVERED
ONLINE
OFFLINE
BUSY
COMMUNICATING
```

Heartbeat 主要决定：

ONLINE / OFFLINE。

PTT Session 决定：

BUSY / COMMUNICATING。

---

# 15. PING / PONG

PING：

测试 Peer 是否仍然可响应。

PONG：

响应 PING。

用途：

- 网络恢复检测
- Debug
- 延迟测试

不要在正常待机情况下高频发送 PING。

---

# 16. Session ID

每一次 PTT 必须生成新的 Session ID。

例如：

UUID。

Session ID 用于：

区分不同语音会话。

即使：

同一个设备连续讲话：

每次也是不同 Session。

作用：

防止旧数据包：

污染新会话。

---

# 17. Sequence Number

SequenceNumber 为头部中的 u32 字段（偏移 60）。

规则如下，**不留实现自由度**：

| 包类型 | SequenceNumber |
|---|---|
| DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT / PING / PONG | 0 |
| VOICE_START | 0 |
| VOICE_ACCEPT | 0 |
| VOICE_DATA | 从 **1** 开始，每帧 +1 |
| VOICE_END | **最后一个 VOICE_DATA 的 sequence + 1** |
| BUSY / SESSION_TERMINATE | 0 |

作用：

- 语音排序
- 去重
- 丢包检测

`VOICE_END` 采用「最后数据帧 + 1」，因此接收端可以据此精确判断本次会话应收到的帧总数，与 VOICE_END Payload 中的 `finalDataSequence` / `frameCount` 互为校验。

# 18. Sequence Wraparound

SequenceNumber 为 32-bit unsigned，达到最大值后自然回绕。

比较必须使用回绕安全算法（RFC 1982 风格）：

```kotlin
fun isNewer(a: UInt, b: UInt): Boolean {
    val d = (a - b)
    return d != 0u && d < 0x8000_0000u
}
```

禁止：

直接使用 `a > b` 处理长期运行的实时流。

必须有单元测试覆盖回绕边界（`0xFFFFFFFE → 0xFFFFFFFF → 0x00000000 → 0x00000001`）。

# 19. VOICE_START 与会话建立

## 19.1 Payload

| 长度 | 字段 | 说明 |
|---|---|---|
| 1 | codec | `0x01` = OPUS |
| 4 | sampleRate | u32，v1 固定 16000 |
| 2 | frameMs | u16，v1 固定 20 |
| 1 | userNameLen | 1..64 |
| n | userName | 本次会话的身份快照 |

头部提供 SenderDeviceId、TargetDeviceId、SessionId、Timestamp。

## 19.2 接收方校验顺序

收到 VOICE_START 后，目标 Peer 依次检查：

1. TargetDeviceId 是否为本机（否则丢弃，见 §20）
2. SenderDeviceId 是否有效且非本机
3. codec / sampleRate / frameMs 是否受支持（不支持则返回 BUSY 并记录 ProtocolError，**不尝试重采样**）
4. 本机当前是否已有活动会话
5. 若已有活动会话：本机的 `allow_interrupt` 设置（见 §33）

## 19.3 三种结果

| 条件 | 接收方动作 |
|---|---|
| 空闲 | 建立接收会话，回 **VOICE_ACCEPT** |
| 忙线 且 `allow_interrupt = false` | 回 **BUSY**，保持原会话不变 |
| 忙线 且 `allow_interrupt = true` | 向原发送方发 **SESSION_TERMINATE**，然后向新请求方回 **VOICE_ACCEPT** |

## 19.4 发送方时序

```text
C --VOICE_START--> B      C: IDLE → REQUESTING
                          立即开始麦克风采集并本地缓冲，暂不发送 VOICE_DATA
C <--VOICE_ACCEPT-- B     C: REQUESTING → TRANSMITTING
                          先补发缓冲帧，再转入实时发送
C --VOICE_DATA...-> B
C --VOICE_END-----> B     双方 → ENDING → IDLE
```

超时与重发：

- 未在 **150ms** 内收到应答则重发 VOICE_START，最多重发 2 次。
- 累计 **500ms** 无任何应答 → `FAILED(NO_RESPONSE)`，丢弃缓冲，**不生成历史记录**。
- 收到 BUSY → `FAILED(TARGET_BUSY)`，丢弃缓冲，**不生成历史记录**。

采集缓冲：

- 上限 **500ms**，超出部分丢弃最旧帧。
- 目的是保证「按下即录」，不丢失第一个音节。

## 19.5 为什么必须有 VOICE_ACCEPT

原设计只有否定应答（BUSY），发送端状态机的 `REQUESTING` 没有任何合法事件可以进入 `TRANSMITTING`。

加入 VOICE_ACCEPT 后状态机闭合，且忙线判定发生在被叫方，不依赖呼叫方手上可能已经过期的心跳状态。

# 20. Target Validation

Voice packet 必须验证：

TargetDeviceId。

如果：

TargetDeviceId != 本机 Device ID：

直接丢弃。

禁止因为：

“本机恰好监听到了这个 UDP 包”

而播放。

这进一步保证：

点对点通信。

---

# 21. VOICE_DATA

Payload：

原始编码音频帧字节（Opus packet）。

上限：

**400 字节**。

参数：

见 `docs/ADR/ADR-004-Audio-Params.md`。v1 为 16 kHz 单声道、20 ms 帧、Opus VOIP 模式、20 kbps CBR、inband FEC 开启。

规则：

- 每个 VOICE_DATA 对应恰好一个音频帧。
- 不得合并多帧到一个 UDP 包。
- 不得发送超过 MTU 的 UDP 包（头部 72 + 上限 400 = 472 字节，远小于 1500）。
- 传输编码由 ProtocolVersion 固定，**不做能力协商**。`05_DataModel` 中的 `audioFormat` 字段仅描述本地存档文件格式，与传输编码无关。

VOICE_DATA 走独立的语音 socket（§6），不与控制包争抢解析线程。

# 22. VOICE_END

用户松开 PTT 时发送。

Payload：

| 长度 | 字段 |
|---|---|
| 4 | finalDataSequence（u32，最后一个 VOICE_DATA 的 sequence） |
| 4 | frameCount（u32，本次会话实际发送的 VOICE_DATA 总数） |

头部的 SequenceNumber = `finalDataSequence + 1`（§17）。

接收端收到 VOICE_END 后：

1. 冲刷 jitter buffer 中剩余帧并播放收尾
2. finalize 本地录音文件
3. 创建 History Record
4. 若 ASR 已启用，加入 ASR Queue
5. 会话 → ENDING → IDLE，并立即广播一次 `peerState = IDLE` 的 HEARTBEAT（§12.2）

`frameCount` 与实际收到的帧数之差即本次会话的丢包数，写入 Debug 日志与开发者信息页。

# 23. Voice Session Timeout

收到 VOICE_START 并建立接收会话后，若长时间没有 VOICE_DATA 或 VOICE_END：

接收端不得无限等待。

| 参数 | 默认值 |
|---|---|
| `sessionIdleTimeoutMs` | **3000** |
| `sessionMaxDurationMs` | **300000**（5 分钟，防止按键卡死或异常设备长期占用） |

超时处理：

1. 结束该 Session
2. 向对端发送 `SESSION_TERMINATE(reason = 0x02 TIMEOUT)`
3. finalize 已收到的音频
4. 历史记录 `status = INTERRUPTED`
5. 广播 `peerState = IDLE`

注意：

记录状态使用 `INTERRUPTED`，**不引入独立的 TIMEOUT 状态**，与 `05_DataModel §26` 的三态保持一致。超时的具体原因记录在日志中，不进入数据库 schema。

# 24. Packet Loss

UDP 本身不保证可靠传输。

VOICE_DATA 允许丢失。

如果某个：

SequenceNumber

缺失：

不要等待无限时间。

应：

- 继续播放后续帧
- 使用 jitter buffer
- 必要时丢弃缺失帧
- 避免明显卡顿

不要对每个语音包使用 TCP 式重传。

实时性优先。

---

# 25. Packet Reordering

如果收到：

100

然后：

102

然后：

101

必须根据：

SequenceNumber

重新排序。

如果：

101

已经错过播放窗口：

不要为了等待它阻塞后面的音频。

使用：

小型 jitter buffer。

---

# 26. Duplicate Packet

如果同一个：

SessionId + SequenceNumber

重复收到：

只处理一次。

重复包：

直接丢弃。

---

# 27. Late Packet

如果 Packet：

属于已经结束的 Session：

丢弃。

如果：

SessionId 不是当前 Session：

丢弃或根据状态机进行安全处理。

---

# 28. Unknown Packet

收到未知：

PacketType：

不得 Crash。

处理：

安全忽略。

可在 Debug 日志：

记录：

Unknown PacketType。

Release：

不要高频打印。

---

# 29. Invalid Packet

完整校验顺序由 `docs/ADR/ADR-003-Wire-Format.md §8` 定义。实现必须按该顺序执行：

1. 数据报长度 ≥ 72
2. Magic 匹配 `SKPT`
3. ProtocolVersion 受支持（v1 只接受 `0x01`）
4. `PayloadLength` ≤ 1024，且 `72 + PayloadLength == 数据报实际长度`
5. `Reserved == 0`
6. PacketType 已知（未知 → 丢弃）
7. SenderDeviceId 非全零，且 != 本机 Device ID（丢弃回环）
8. 需要目标的包：TargetDeviceId == 本机 Device ID，否则立即丢弃（§20）
9. 会话类包：SessionId 非全零
10. Payload 按类型解析，长度不足或字段越界 → 丢弃
11. `VOICE_DATA`：SessionId == 当前接收会话，且 SenderDeviceId == 当前会话发送方（§35），否则丢弃
12. Payload 大小超该类型上限 → 丢弃

任何一步失败：

丢弃该包，计入 invalid-packet 速率统计（§45），不抛异常、不进入业务层、不导致 App Crash。

# 30. Packet Size Limits

| 限制项 | 值 |
|---|---|
| 头部固定长度 | 72 字节 |
| Payload 通用上限 | 1024 字节 |
| 单个数据报上限 | 1096 字节 |
| `VOICE_DATA` Payload 上限 | **400 字节** |
| UserName 上限 | 64 字节 UTF-8（§5.1） |

目的：

- 防止内存攻击
- 防止 IP 分片（1096 < 1500 MTU）
- 防止异常设备占用资源

正常语音包实际长度约 `72 + 50~80 = 122~152` 字节。

接收缓冲区按 **2048 字节**预分配并复用，超过 1096 字节的数据报直接丢弃。

# 31. Busy Flow

当 B 正在进行 `A → B` 通信时，C 向 B 发起 VOICE_START：

B 依据**自身**的 `allow_interrupt` 设置决定（见 §33）：

```text
allow_interrupt = false （默认）
    B --BUSY--> C
    A ←→ B 通信不受影响
    C: REQUESTING → FAILED(TARGET_BUSY)，不生成历史记录
```

C 的 UI 显示：

> 对方正在通话中。

C 不得：

- 播放任何语音
- 进入 TRANSMITTING
- 生成历史记录

## 31.1 UI 预判与最终判定的关系

设备列表中的「通話中」标记来自 HEARTBEAT 的 `peerState`（§12.2），用于**事前提示**。

但忙线的**最终判定权在被叫方**：即使 UI 显示对方空闲，仍可能收到 BUSY（心跳存在最长数百毫秒的滞后）。

实现不得依据本地缓存的 peerState 直接拒绝发送，必须实际发出 VOICE_START 并等待应答。

# 32. BUSY Packet

BUSY 由被叫方发出。

头部：

- SenderDeviceId = 被叫方
- TargetDeviceId = 呼叫方
- SessionId = **呼叫方在 VOICE_START 中使用的 SessionId**（便于呼叫方匹配请求）
- SequenceNumber = 0

Payload：

**空**。

不回传当前活动会话的 SessionId 或对方身份，避免向第三方泄露通信关系。

呼叫方收到 BUSY 后：

`REQUESTING → FAILED(TARGET_BUSY) → IDLE`。

UI 显示本地化的「对方正在通话中」。

# 33. Force Interrupt

## 33.1 归属方

**Force Interrupt 是接收方开关。**

设置项：

```text
allow_interrupt
```

含义：

> 允许其他设备打断我正在进行的通话。

默认：

`false`。

发送方不需要、也无法感知对方的策略。发送方永远只发 VOICE_START，由被叫方返回 VOICE_ACCEPT 或 BUSY。

## 33.2 被否决的方案

发送方开关：任何一台设备单方面开启即可打断全网任意通话，被叫方无法拒绝。不可接受。

## 33.3 流程

```text
A ←→ B  进行中（SessionId = S1）

C --VOICE_START(S2)--> B

B: 当前忙线，且 allow_interrupt = true
   1. 原子地把会话所有权从 S1 转移到 S2
   2. A <--SESSION_TERMINATE(reason = 0x01 INTERRUPTED_BY_PEER)-- B
   3. C <--VOICE_ACCEPT(S2)-- B
   4. 广播 HEARTBEAT（peerState 仍为 BUSY）

A: 收到 SESSION_TERMINATE → ENDING
   finalize 已采集音频
   历史记录 status = INTERRUPTED
   UI 提示：通话已被中断

C: → TRANSMITTING
```

转移完成后：

属于 S1 的任何后续包一律丢弃（§27）。

## 33.4 FORCE_INTERRUPT 包类型

保留但 **v1 不使用**（§8）。

强插完全由被叫方的策略与会话所有权转移实现，不需要独立的请求包类型。

# 34. Force Interrupt Race Condition

若 C 与 D 同时向 B 发起 VOICE_START：

B 是**唯一的会话所有权仲裁者**。

要求：

会话所有权的转移必须是**单一原子操作**。

实现约束：

- 使用单一 `Mutex` 或 `AtomicReference.compareAndSet` 保护「当前活动会话」这一个变量。
- 禁止用多个布尔量组合表达会话状态（`01_PRD §43`）。
- 所有权判定与 VOICE_ACCEPT / BUSY 的发送必须在同一个临界区内决定，避免「两个请求都读到空闲」。

结果：

只有一个请求获得会话，其余一律收到 BUSY。

绝不允许：

同时播放两个发送方的声音。

必须有单元测试模拟并发 VOICE_START（`07_TestPlan §19`）。

# 35. Sender Validation

接收 Voice 时：

验证：

SenderDeviceId。

如果：

当前 Session Sender：

A

突然收到：

Sender = C

在同一个 SessionId 下：

必须拒绝。

不要允许：

第三个设备注入语音。

---

# 36. User Name Consistency

VOICE_START 中：

发送当前 UserName。

VOICE_DATA：

不需要重复发送 UserName。

接收端保存：

Session → UserName。

之后：

VOICE_DATA

只依赖：

SessionId。

VOICE_END：

使用：

SessionId。

---

# 37. Username Change During Session

如果用户在 PTT Session 进行过程中：

修改本地用户名：

当前已经开始的 Session：

继续使用原来的：

UserName。

下一次 PTT：

使用新的 UserName。

这样保证：

一次 Session 内身份一致。

---

# 38. Timestamp

Timestamp 用于：

- Debug
- 日志
- 判断明显异常数据
- 可选 replay protection

Timestamp 不应该作为：

唯一的顺序依据。

手机系统时间变化：

不能破坏：

Sequence。

---

# 39. Local Network Scope

协议默认工作在：

同一个局域网广播域。

不需要：

NAT traversal。

不需要：

Internet relay。

不需要：

公网上的 server。

---

# 40. Network Change

如果 IP 改变：

Device ID 保持不变。

重新 Discovery。

更新 Peer endpoint。

Heartbeat：

重新建立。

正在进行的旧 Session：

可以结束并标记 Interrupted。

不要尝试跨网络状态强行恢复实时语音 Session。

---

# 41. WiFi Disconnect

WiFi 断开：

停止网络发送。

关闭或暂停相关 socket。

Peer 状态：

OFFLINE。

当前 PTT：

结束。

录音：

根据已经采集的数据决定保存为：

Interrupted。

WiFi 恢复：

重新 Discovery。

重新 Heartbeat。

---

# 42. Background Receive

后台运行时，Foreground Service 持续监听控制端口与语音端口。

收到 VOICE_START 时，即使 Activity 不存在，也必须能够进入 Receiving Session。

UI 状态通过 StateFlow / shared state 同步。

## 42.1 平台约束：接收不受限，发送受限

依据 `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`：

- **接收与播放不需要麦克风**，因此后台、熄屏、锁屏状态下均可正常接收。前台服务以 `connectedDevice` 类型常驻即可。
- **发送需要麦克风**。Android 14+ 禁止从后台启动或提升为 `microphone` 类型的前台服务，因此**按住 PTT 讲话要求应用界面处于可见状态**。
- 从悬浮窗点击时：先拉起 Activity，再允许发送。

这是平台规则，不是实现缺陷，必须在 UI 上明确表达。

## 42.2 电源锁

- `MulticastLock`：服务 READY 期间常驻。不持有则熄屏后收不到广播心跳，后台接收失效。
- `WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` 与 `PARTIAL_WAKE_LOCK`：仅在语音会话存在期间持有，会话结束立即释放。

详见 ADR-005 §6。

# 43. Foreground Service Boundary

Service：

负责：

- lifecycle
- socket availability
- communication coordinator
- background receive

ProtocolEngine：

负责：

packet parsing。

VoiceSessionManager：

负责：

session state。

AudioPlayer：

负责：

播放。

不要把所有逻辑都写进 Service。

---

# 44. Security / Validation

当前产品定位：

Trusted LAN。

不要求复杂认证系统。

但是：

所有 Packet：

必须做严格验证。

不得因为局域网内存在一个恶意或损坏设备：

导致：

- Crash
- 无限内存增长
- 无限任务创建
- 无限日志刷屏

---

# 45. Rate Limiting

必须对异常流量做基本限制，防止局域网内的恶意或损坏设备导致 CPU、内存、日志无限增长。

v1 默认阈值（集中定义在 Config，实测后可调）：

| 项目 | 阈值 | 超限动作 |
|---|---|---|
| 单个来源 IP 的控制包速率 | 50 包/秒 | 超出部分丢弃，该来源进入 5 秒静默期 |
| 单个来源 IP 的非法包速率 | 20 包/秒 | 同上，并记录一条 WARN |
| 非法包日志 | 每类每秒最多 1 条 | 其余静默计数 |
| Peer 表容量上限 | 64 | 达到上限后拒绝新增，记录 WARN |
| 未知 PacketType 日志 | 每秒最多 1 条（仅 Debug） | Release 不打印 |

语音包不做速率限制（其速率由 Session 与 sequence 校验天然约束），但非当前会话的 VOICE_DATA 一律丢弃且不记日志。

# 46. Protocol Extensibility

以后允许增加：

- ERROR
- CAPABILITIES
- TEXT
- GROUP
- FILE
- OTHER

但当前版本：

只实现产品需求定义的 Packet。

未知 Packet：

安全忽略。

---

# 47. Capability Negotiation

可以预留：

CAPABILITIES。

用于未来告诉 Peer：

支持：

- Opus
- 某些音频参数
- 特殊功能

初始版本：

不是必须实现。

如果最终实现：

不能增加明显的待机功耗。

---

# 48. Protocol Compatibility

旧版本：

收到未知字段：

应尽量忽略。

新版本：

不能假设旧客户端理解所有 Packet。

核心 PTT 必须有明确：

compatible / incompatible

行为。

---

# 49. Protocol Testing

必须针对协议进行自动化测试。

至少覆盖：

- 每种已实现 PacketType 的 encode / decode round trip
- 头部逐字节布局与 ADR-003 一致（固定字节数组断言）
- 大端字节序
- UUID 二进制编解码
- invalid packet：Magic 错误
- version mismatch
- packet length 与 PayloadLength 不一致
- Payload 超限（通用 1024 / VOICE_DATA 400 / UserName 64 字节）
- unknown packet type
- wrong target
- wrong sender（同一 SessionId 下发送方变化）
- duplicate
- out-of-order
- missing sequence
- session mismatch
- corrupted packet（随机翻转字节）
- **sequence 32-bit 回绕比较**（`0xFFFFFFFE → 0xFFFFFFFF → 0x00000000 → 0x00000001`）
- 校验顺序：确认在 Magic 错误时不会继续解析后续字段
- 并发 VOICE_START 时会话所有权唯一（§34）

所有非法输入的期望结果一致：

丢弃，不抛异常，不进入业务层。

# 50. Logging

Debug 模式记录：

- Packet type
- Sender
- Target
- Session
- Sequence
- latency
- state transitions
- error

禁止记录：

完整 VOICE_DATA payload。

Release：

关闭详细 Packet logging。

---

# 51. Performance

协议实现必须避免：

每个 UDP packet：

创建大量临时对象。

实时语音优先考虑：

- buffer reuse
- bounded queues
- efficient serialization

但不要在没有 profiling 证据前进行过度 micro-optimization。

---

# 52. Protocol State Ownership

Packet 本身：

只代表：

网络事件。

真正的状态变化：

由：

SessionManager / PresenceManager

决定。

不要收到一个 Packet：

就直接修改多个全局状态。

---

# 53. Protocol and UI Separation

Protocol 层：

不能包含：

- Compose
- Android View
- UI 文本
- Toast
- Activity 操作

例如：

Protocol 收到 BUSY：

只产生：

BusyEvent。

UI 决定：

显示哪种语言的文字。

---

# 54. Protocol and Storage Separation

Protocol 不直接写：

Room。

Protocol 产生：

domain event。

Session/History 层：

决定：

什么时候保存。

---

# 55. Recommended Event Flow

例如收到：

VOICE_START：

```text
UDP
↓
PacketDecoder
↓
Validation
↓
VoiceStartEvent
↓
VoiceSessionManager
↓
Session accepted
↓
AudioPlayer
↓
UI State
```

收到：

VOICE_END：

```text
UDP
↓
PacketDecoder
↓
Validation
↓
VoiceEndEvent
↓
VoiceSessionManager
↓
finalize audio
↓
HistoryRepository
↓
ASR Queue
```

---

# 56. Protocol Definition of Done

协议设计完成必须满足：

- Device ID 编码已定义（16 字节 UUID 二进制）
- Packet Header 逐字节布局已定义（72 字节，大端）
- Packet Types 已定义并标注 v1 实现状态
- 每种包的 Payload 布局已定义
- Session ID 已定义
- Sequence 规则已定义（含 VOICE_END）
- Sequence 回绕比较算法已定义
- Discovery 策略已定义（纯 UDP 广播）
- Heartbeat 已定义（广播 + peerState 忙线通告）
- 端口与 socket 方案已定义
- Voice Start / Accept / Data / End 已定义
- Busy 已定义
- Session Terminate 已定义
- Force Interrupt 归属方已定义（接收方）
- Force Interrupt 竞态处理已定义
- timeout 已定义（含具体数值）
- packet validation 顺序已定义
- packet size limit 已定义
- UserName 长度上限已定义
- loss / reorder / duplicate handling 已定义
- rate limiting 阈值已定义
- compatibility 行为已定义

实现阶段必须严格遵循本协议及其引用的 ADR。

如果实际 Android / 网络测试发现协议存在缺陷：

不要偷偷修改。

先创建新的 ADR，将受影响的旧 ADR 标记为 Superseded，然后更新本文件及相关实现。

协议格式的任何变更都必须伴随 ProtocolVersion 升版。
