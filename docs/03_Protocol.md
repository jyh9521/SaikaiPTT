# SaikaiPTT Network Protocol Specification

## 1. 文档目的

本文档定义 SaikaiPTT 的局域网通信协议。

覆盖：

- 设备发现
- Heartbeat
- 在线状态
- Peer Endpoint
- PTT Session
- VOICE_START
- VOICE_DATA
- VOICE_END
- BUSY
- FORCE_INTERRUPT
- PING
- PONG
- Packet version
- Sequence
- Session ID
- 超时
- 丢包
- 乱序
- 重复包
- 非法数据
- 网络恢复

本协议的首要目标：

> 在同一 WiFi 局域网内实现稳定、低延迟、点对点的 PTT 语音通信。

---

# 2. 核心原则

## 2.1 Discovery 可以广播

为了发现局域网设备：

允许：

- UDP Broadcast
- mDNS / NSD
- Multicast

具体 Discovery 实现由 Discovery 模块决定。

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

---

# 6. Network Ports

协议需要定义：

Discovery Port

Control Port

Voice Port

具体端口号必须集中定义在：

Config。

不要硬编码到多个类中。

推荐：

使用固定 UDP 端口体系。

例如：

```text
Discovery Port
Control Port
Voice Port
```

实际端口号由最终 Architecture / Config 决定。

如果 Discovery、Control 和 Voice 使用相同 UDP socket 能显著降低资源占用且不会降低可靠性，也可以合并端口，但协议中的 PacketType 必须能够明确区分消息类型。

---

# 7. Packet Structure

逻辑 Packet 应包含：

```text
Magic
ProtocolVersion
PacketType
Flags
PacketLength

SenderDeviceId
TargetDeviceId

SessionId
SequenceNumber

Timestamp

Payload
```

推荐字段：

### Magic

固定协议标识。

作用：

防止把其它局域网 UDP 数据误解析为 SaikaiPTT Packet。

### ProtocolVersion

当前协议版本。

### PacketType

数据包类型。

### Flags

预留。

### PacketLength

完整包长度或 Payload 长度。

### SenderDeviceId

发送方 Device ID。

### TargetDeviceId

目标 Device ID。

对于 Discovery：

可以没有固定 target，具体由 PacketType 规则定义。

### SessionId

PTT Session ID。

非 Session 消息：

可以为空。

### SequenceNumber

用于：

- 语音排序
- 去重
- 丢包检测

### Timestamp

用于：

- 超时
- 排查问题
- 日志

不能单独依赖 Timestamp 判断语音顺序。

### Payload

实际业务数据。

---

# 8. Packet Types

初始协议至少支持：

```text
DISCOVERY
HEARTBEAT
PING
PONG
VOICE_START
VOICE_DATA
VOICE_END
BUSY
FORCE_INTERRUPT
```

可以预留：

```text
ERROR
GOODBYE
CAPABILITIES
```

但未实现 Packet 必须安全忽略。

---

# 9. Discovery Packet

DISCOVERY 用于：

发现：

> 我是谁？

以及：

> 我在这个局域网里。

至少包含：

- Device ID
- User Name
- Protocol Version
- 当前监听端口
- 能力信息（可选）
- 时间戳

Discovery 不表示：

永久在线。

必须由 Heartbeat 判断持续在线状态。

---

# 10. Discovery Behavior

设备上线后：

主动发送 Discovery。

可以：

重复发送数次。

避免只发送一次导致：

丢包后双方互相不可见。

收到新的 Discovery：

记录：

- Device ID
- User Name
- IP
- Port
- lastSeen
- protocol version

如果 Device ID 已存在：

更新 endpoint。

不能创建重复 Peer。

---

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

Heartbeat 用于：

确认 Peer 仍在线。

包含：

- SenderDeviceId
- ProtocolVersion
- Timestamp
- optional state

Heartbeat 可以：

发送：

HEARTBEAT

返回：

PONG

或者：

HEARTBEAT

与：

PONG

结合使用。

具体实现必须保持简单。

---

# 13. Heartbeat Timing

心跳频率必须统一配置。

默认建议：

约 5 秒一次。

这只是初始建议。

最终必须通过低端设备测试确定。

超时时间：

应明显高于一个 heartbeat interval。

建议：

连续多个周期没有收到有效消息后：

标记 OFFLINE。

不要因为单次丢包立刻判定离线。

---

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

VOICE_DATA：

必须有：

Sequence Number。

例如：

```text
1
2
3
4
5
...
```

作用：

- 排序
- 去重
- 检测丢包

VOICE_START：

可以使用 sequence 0。

VOICE_DATA：

从 1 开始。

VOICE_END：

使用最后序列号或独立控制序列，具体实现必须统一。

---

# 18. Sequence Wraparound

Sequence Number 不应无限增长。

推荐使用：

32-bit unsigned integer。

达到最大值后：

自然回绕。

比较算法必须使用：

wraparound-safe sequence comparison。

不要简单使用：

`a > b`

处理长期运行的实时流。

---

# 19. VOICE_START

开始 PTT：

发送：

VOICE_START。

必须包含：

- SenderDeviceId
- TargetDeviceId
- SessionId
- UserName
- Timestamp

接收后：

目标 Peer 检查：

1. TargetDeviceId 是否是自己。
2. Sender 是否有效。
3. Sender 是否在线。
4. 当前是否 Busy。
5. 当前是否允许 Force Interrupt。

---

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

VOICE_DATA：

包含：

- SessionId
- SequenceNumber
- encoded audio payload

推荐：

Opus。

每一个 VOICE_DATA：

对应一个音频 frame 或合理大小的 encoded audio packet。

不要发送：

过大的 UDP packet。

避免：

IP fragmentation。

---

# 22. VOICE_END

用户松开 PTT：

发送：

VOICE_END。

至少包含：

- SessionId
- Final sequence
- Timestamp

接收端收到：

VOICE_END：

结束当前 Session。

完成：

- 音频播放收尾
- 本地录音
- History
- ASR queue

---

# 23. Voice Session Timeout

如果：

VOICE_START 已收到。

但长时间没有：

VOICE_DATA

或：

VOICE_END。

接收端不能无限等待。

应有：

Session timeout。

超时：

结束该 Session。

记录：

Interrupted / Timeout。

---

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

以下情况：

直接丢弃：

- Magic 错误
- Version 不支持
- PacketLength 非法
- Payload 超过限制
- Device ID 非法
- Target Device ID 不匹配
- Session 数据缺失
- Sequence 无效

不能：

导致 App Crash。

---

# 30. Packet Size Limits

必须设置：

最大 Packet Size。

特别是：

VOICE_DATA。

目的：

防止：

- 内存攻击
- 错误包
- IP fragmentation
- 异常设备占用资源

正常语音 Packet 应明显小于网络 MTU。

具体上限在实现阶段根据 Opus 参数确定。

---

# 31. Busy Flow

当：

B

正在：

A → B

通信。

C → B：

发送：

VOICE_START。

B：

判断 Busy。

如果：

Busy Mode：

返回：

BUSY。

C：

结束本次请求。

不播放任何语音。

---

# 32. BUSY Packet

BUSY：

至少包含：

- SenderDeviceId
- TargetDeviceId
- ActiveSessionId（可选）
- Timestamp

收到：

BUSY：

发送方进入：

BUSY / REQUEST_FAILED。

UI 显示：

> 对方正在通话中。

---

# 33. Force Interrupt

如果 B 开启：

Force Interrupt。

C → B。

B 当前：

A → B。

B 可以接受 C。

流程：

```text
A → B
existing session

C → B
new request

B validates FORCE_INTERRUPT

terminate A → B

notify old session

start C → B

new SessionId
```

新 Session：

必须使用新的：

SessionId。

旧语音包：

收到后：

丢弃。

---

# 34. Force Interrupt Race Condition

如果：

C 和 D

同时强插 B。

协议和 SessionManager 必须保证：

最终只有一个新 Session 被接受。

推荐：

使用原子 session transition。

只有成功获得：

“communication ownership”

的请求才能开始。

其它请求：

返回 BUSY 或被忽略。

---

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

后台运行时：

Foreground Service：

持续监听：

Control Packet

和：

Voice Packet。

收到：

VOICE_START：

即使 Activity 不存在：

也必须能够进入：

Receiving Session。

UI 状态由：

StateFlow / shared state

同步。

---

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

Discovery：

可以允许适量广播。

Heartbeat：

固定频率。

未知设备发送大量 Packet：

不得导致：

CPU 无限增长。

建议：

对异常：

- Packet rate
- Discovery rate
- invalid packet rate

进行基本限制。

具体阈值由实现阶段通过测试确定。

---

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

至少测试：

- encode
- decode
- invalid packet
- unknown packet
- wrong target
- wrong sender
- duplicate
- out-of-order
- missing sequence
- session mismatch
- version mismatch
- oversized packet
- corrupted packet

---

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

- Device ID 已定义
- Packet Header 已定义
- Packet Types 已定义
- Session ID 已定义
- Sequence 已定义
- Discovery 已定义
- Heartbeat 已定义
- Voice Start 已定义
- Voice Data 已定义
- Voice End 已定义
- Busy 已定义
- Force Interrupt 已定义
- timeout 已定义
- packet validation 已定义
- packet size limit 已定义
- loss handling 已定义
- reorder handling 已定义
- duplicate handling 已定义
- compatibility 已定义

实现阶段必须：

严格遵循本协议。

如果实际 Android/网络测试发现协议存在缺陷：

不要偷偷修改。

先创建 ADR。

然后更新本文件及相关实现。
