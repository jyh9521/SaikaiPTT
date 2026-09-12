# core.protocol

Packet 编解码与校验，严格按 `docs/ADR/ADR-003-Wire-Format.md` 实现。

72 字节大端头部、包类型、各类型 Payload、12 步校验顺序、序列号回绕比较。

**不得依赖任何 socket 类型**：协议是纯粹的字节与结构，传输在 `app.network`。

## 已实现（Task11）

| 文件 | 职责 |
|---|---|
| `WireFormat.kt` | 字节级常量：magic、72 字节头部、字段偏移、长度上限、大端读写原语 |
| `PacketType.kt` | 15 个类型码（11 个 v1 实现 + 4 个保留） |
| `SessionId.kt` | 16 字节二进制会话 ID，与 `DeviceId` 是不同类型 |
| `SequenceNumbers.kt` | 各类型的 sequence 取值规则 + RFC 1982 回绕比较 |
| `PayloadEnums.kt` | `PeerState` / `AudioCodec` / `TerminationReason` |
| `PacketPayload.kt` | 各类型 Payload 的布局与编解码 |
| `PacketHeader.kt` | 头部编解码（纯结构，不做策略判断） |
| `Packet.kt` | 头部 + Payload |
| `PacketCodec.kt` | 整包 decode，以及 VOICE_DATA 的零分配快速编码路径 |

两条约定贯穿全包：

- **编码 `require`，解码返回 null**。编码的输入来自本机已校验的状态，越界是 bug；解码的输入来自局域网上任意一台设备，越界是常态，必须丢弃而不是抛异常。
- **缓冲区由调用方持有**。`WireFormat.newDatagramBuffer()` 每线程分配一次，收发路径每包零分配；`VoiceDataPayload` 是接收缓冲区上的视图，跨越 `receive()` 需要 `copyFrame()`。

## 已实现（Task12）

| 文件 | 职责 |
|---|---|
| `RejectionReason.kt` | 12 步各自的失败原因，带 `countsAsInvalid` / `isLoggable` 分类 |
| `ProtocolStats.kt` | 无锁计数：接受数 + 每种原因的丢弃数 |
| `ReceivingSession.kt` | 当前接收会话的只读快照（第 11 步要用） |
| `PacketValidator.kt` | ADR-003 §8 的 12 步有序校验 |
| `PacketRateLimiter.kt` | §45 的每来源速率限制与静默期 |

两个分类不是「非法包」，这是本任务最容易做错的地方：

- **`OWN_BROADCAST_ECHO`**：本机每个广播都会原样回到自己。把它算进非法包速率，设备会把自己静默掉。
- **`FOREIGN_SESSION`**：强插或正常挂断后，对端还有帧在路上。§45 明确要求静默丢弃，且语音包本来就不做速率限制。

两者都不计数、不记日志。其余 12 种都计数并按 kind 限频记 DEBUG 日志（`PROTOCOL` 分类，Release 不开）；只有「某来源非法包超限」这一条记 WARN 且放在 `SERVICE` 分类——§45 点名要这条，而它最有价值的场合恰恰是 Release。

### 接收管线怎么串（Task14 用）

```kotlin
if (!rateLimiter.admit(sourceKey)) return          // 静默中的来源，零成本
val outcome = validator.validate(buffer, 0, length)
rateLimiter.record(sourceKey, outcome)             // 只有真正的故障才计数
when (outcome) {
    is Outcome.Success -> dispatch(outcome.value)
    is Outcome.Failure -> Unit                     // 已计数、已限频记录
}
```

语音 socket 不调 `admit`：语音速率由第 11 步的会话校验天然约束。

## 已实现（Task24）

| 文件 | 职责 |
|---|---|
| `VoicePacketizer.kt` | 一次会话的四种包：VOICE_START / VOICE_ACCEPT / VOICE_DATA / VOICE_END，以及把它们串成一条流的序号纪律 |

Payload 布局与序号规则 Task11 就写好了；这里补上的是**跨越整场发送把它们连起来的那个东西**
——计数器，和它写进去的缓冲区。

**只存一个计数器**。帧从 1 开始编号、每帧加一，所以「最后一个序号」和「帧数」是同一个数的
两种读法。存两个就是两次分歧的机会，而这两个值不一致正是接收方拿它们要检测的东西。

因此 v1 里 VOICE_END 的两个 payload 字段恒等。**接收方不得依赖这一点**：任何会跳过序号的
发送方（DTX、编码器拒绝的一帧）都会让它们分开，而「网络丢了」与「压根没发」的区别正是
ADR 同时带这两个字段的全部理由。

**两个缓冲区，一个原子计数器**。VOICE_DATA 由采集线程每秒写 50 次，另外三种由会话机的协程写；
它们本来就是两条路（ADR-002 给了它们两个 socket），共用一个数组就是在本项目写得最勤的那块
内存上制造数据竞争。计数器是两条线程共享的，而 `writeVoiceEnd` 必须**把「读计数」和「封闭」
做成一步**——先读 volatile 再封闭会漏掉中间写进来的那一帧，而那不是假想情况，那正是松开
说话键的那一瞬间。一次 CAS 在 50 Hz 的路径上什么都不值，换来的是调用方可以自由决定先停采集
还是先结束会话。

**超限的帧被拒绝，而不是抛异常**——这是本包编码侧唯一的例外，理由是输入来源不同：别处编码
的输入都是本机已校验的状态，越界是 bug；这里是编解码器每秒 50 次的输出，为一帧怪数据打死
采集线程，等于把一声咔哒变成一通掉线的通话。

**被拒绝的帧不占序号**。必须如此：序号空洞会告诉接收方丢了一个包，然后它会去找一个根本
不存在的帧的 FEC 副本。
