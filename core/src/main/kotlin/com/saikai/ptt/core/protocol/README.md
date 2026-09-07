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

## 待实现（Task12）

`PacketValidator`：ADR-003 §8 的 12 步有序校验、本机回环过滤、目标匹配、会话与发送方匹配、非法包速率统计与限频日志。它按顺序调用本包已有的原语（`WireFormat.hasMagic` / `PacketHeader.read` / `PacketCodec.decodePayload`），因此每一步失败都能单独计数——这正是 `PacketCodec.decode` 只做结构性检查、不做策略判断的原因。
