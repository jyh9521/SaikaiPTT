# ADR-003 — 协议二进制线格式

Status: Accepted
Date: 2026-09-05

## Context

`03_Protocol §7` 原先只给出逻辑字段清单，没有字节序、字段宽度、字段顺序、Device ID 编码方式，`PacketLength` 的语义也写成「完整包长度**或** Payload 长度」。在此状态下 `Task ProtocolDataStructures` 的验收标准「encode/decode round trips」无法保证两台设备互通，`Task ProtocolValidation` 也没有可验证的边界。

同时协议缺少三个必要的包类型，导致：

- 发送端状态机 `REQUESTING → TRANSMITTING` 没有任何肯定应答事件可以触发（只有否定应答 BUSY）。
- Force Interrupt 时被打断方无法被通知。
- 第三方设备的忙线状态没有传播途径，`Peer.BUSY` 永远无法赋值，而 `04_UI_UX §9/§11` 要求在设备列表中显示「通話中」。

## Decision

### 1. 通用规则

- 字节序：**大端（网络字节序）**。
- 所有 Packet = 固定 **72 字节头部** + `0..1024` 字节 Payload。
- Device ID / Session ID 在线路上为 **16 字节 UUID 二进制**（不是 36 字节 ASCII）。全零表示「无 / 广播」。
- 单个 UDP 数据报最大 `72 + 1024 = 1096` 字节，明显小于 1500 MTU，不产生 IP 分片。
- `VOICE_DATA` 的 Payload 额外限制为 **≤ 400 字节**。

### 2. 头部布局（72 bytes）

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 0 | 4 | Magic | bytes | 固定 `0x53 0x4B 0x50 0x54`（ASCII `SKPT`） |
| 4 | 1 | ProtocolVersion | u8 | v1 = `0x01` |
| 5 | 1 | PacketType | u8 | 见 §3 |
| 6 | 2 | Flags | u16 | v1 全部保留，必须为 0，接收方必须忽略未知位 |
| 8 | 2 | PayloadLength | u16 | **仅 Payload 字节数**，不含头部 |
| 10 | 2 | Reserved | u16 | 必须为 0 |
| 12 | 16 | SenderDeviceId | uuid | 发送方 |
| 28 | 16 | TargetDeviceId | uuid | 目标；广播类包为全零 |
| 44 | 16 | SessionId | uuid | 非会话包为全零 |
| 60 | 4 | SequenceNumber | u32 | 见 §5 |
| 64 | 8 | Timestamp | i64 | UTC epoch milliseconds |

### 3. PacketType 取值

| 值 | 名称 | 通道 | 说明 |
|---|---|---|---|
| `0x01` | DISCOVERY | 控制（广播） | 上线 / 网络恢复 / 改名时主动通告 |
| `0x02` | DISCOVERY_RESPONSE | 控制（单播） | 对 DISCOVERY 的即时回应 |
| `0x10` | HEARTBEAT | 控制（广播） | 周期在线 + 忙线状态通告 |
| `0x11` | PING | 控制（单播） | 连通性探测，仅调试/恢复时使用 |
| `0x12` | PONG | 控制（单播） | PING 应答 |
| `0x20` | VOICE_START | 控制（单播） | 请求建立 PTT 会话 |
| `0x21` | VOICE_ACCEPT | 控制（单播） | **接受**会话（本 ADR 新增） |
| `0x22` | VOICE_DATA | 语音（单播） | 编码音频帧 |
| `0x23` | VOICE_END | 控制（单播） | 发送方正常结束 |
| `0x24` | BUSY | 控制（单播） | 拒绝：目标忙线且不允许被打断 |
| `0x25` | SESSION_TERMINATE | 控制（单播） | 由接收方终止会话（本 ADR 新增） |
| `0x30` | FORCE_INTERRUPT | — | **保留，v1 不使用**（见 §6） |
| `0x31` | ERROR | — | 保留 |
| `0x32` | GOODBYE | — | 保留 |
| `0x33` | CAPABILITIES | — | 保留 |

未知 PacketType：安全丢弃，Debug 下按 1 次/秒上限记日志。

### 4. Payload 布局

字符串一律 UTF-8，前置 1 字节长度。**UserName 最大 64 字节 UTF-8**（UI 侧同时限制 24 个码位，两者取严）。

**DISCOVERY / DISCOVERY_RESPONSE / HEARTBEAT**

| 长度 | 字段 | 说明 |
|---|---|---|
| 1 | peerState | `0x00` IDLE，`0x01` BUSY |
| 2 | voicePort | u16，本机实际监听的语音端口 |
| 1 | userNameLen | 1..64 |
| n | userName | UTF-8 |

**VOICE_START**

| 长度 | 字段 |
|---|---|
| 1 | codec（`0x01` = OPUS） |
| 4 | sampleRate（u32，v1 固定 16000） |
| 2 | frameMs（u16，v1 固定 20） |
| 1 | userNameLen |
| n | userName（本次会话的身份快照） |

**VOICE_ACCEPT** — 空 Payload。
**VOICE_DATA** — 原始编码帧字节（Opus packet），≤ 400 字节。
**VOICE_END** — `u32 finalDataSequence` + `u32 frameCount`。
**BUSY** — 空 Payload（不回传 activeSessionId，避免泄露第三方通信关系）。
**SESSION_TERMINATE** — `u8 reason`：`0x01` INTERRUPTED_BY_PEER，`0x02` TIMEOUT，`0x03` SERVICE_SHUTDOWN，`0x04` NETWORK_LOST。
**PING / PONG** — 空 Payload。

### 5. SequenceNumber 规则（消除原 `§17` 的「具体实现必须统一」）

| 包 | Sequence |
|---|---|
| DISCOVERY / HEARTBEAT / PING / PONG | 0 |
| VOICE_START | 0 |
| VOICE_ACCEPT | 0 |
| VOICE_DATA | 从 **1** 开始，每帧 +1 |
| VOICE_END | **最后一个 VOICE_DATA 的 sequence + 1** |
| BUSY / SESSION_TERMINATE | 0 |

u32 自然回绕。比较必须使用 RFC 1982 风格的回绕安全比较：
`isNewer(a, b) = ((a - b) and 0xFFFFFFFF) in 1 until 0x80000000`
禁止直接使用 `a > b`。

### 6. 会话建立时序（消除原 `§19/§31/§33` 的空洞）

正常：

```
C --VOICE_START--> B          C: IDLE → REQUESTING，立即开始采集并本地缓冲，不发数据
C <--VOICE_ACCEPT-- B          C: REQUESTING → TRANSMITTING，先补发缓冲帧再实时发送
C --VOICE_DATA...-> B
C --VOICE_END-----> B          双方 → ENDING → IDLE
```

- `VOICE_START` 未在 **150ms** 内收到应答则重发，最多重发 2 次；累计 **500ms** 无应答 → `FAILED(NO_RESPONSE)`，丢弃缓冲，不生成历史记录。
- 采集缓冲上限 **500ms**；超出部分丢弃最旧帧。此设计保证「按下即录」，不丢失首个音节。

忙线拒绝：

```
C --VOICE_START--> B （B 正忙且 allowInterrupt = false）
C <--BUSY--------- B           C: REQUESTING → FAILED(TARGET_BUSY)，不生成历史记录
```

强制插入（**由接收方 B 的设置决定**，见 ADR 决策记录 §7）：

```
A <==== 进行中 ====> B
C --VOICE_START--> B （B 正忙且 allowInterrupt = true）
A <--SESSION_TERMINATE(reason=0x01)-- B    A: → ENDING，本端记录 status = INTERRUPTED
C <--VOICE_ACCEPT-- B                      B 生成新 SessionId 的接收会话
```

- B 对「当前会话所有权」的转移必须是**单一原子操作**（`compareAndSet` 或单一 Mutex 保护）。并发的多个 VOICE_START 中只有一个能成功，其余一律返回 BUSY。
- 转移完成后，旧 SessionId 的任何后续包一律丢弃。

### 7. Force Interrupt 归属方

**Force Interrupt 是接收方开关**：`allow_interrupt`（默认 `false`），表示「允许其他设备打断我正在进行的通话」。

发送方不需要、也无法感知对方的策略：它永远只发 `VOICE_START`，由 B 返回 `VOICE_ACCEPT` 或 `BUSY`。

否决的替代方案（发送方开关）：任何一台设备单方面打开即可打断全网任意通话，被叫方无法拒绝，不可接受。

### 8. 校验顺序（`Task ProtocolValidation` 必须按此顺序实现）

1. 数据报长度 ≥ 72
2. Magic 匹配
3. ProtocolVersion 受支持（v1 只接受 `0x01`）
4. `PayloadLength` ≤ 1024，且 `72 + PayloadLength` == 实际数据报长度
5. `Reserved` == 0
6. PacketType 已知（未知 → 丢弃）
7. SenderDeviceId 非全零，且 != 本机 Device ID（丢弃自己的广播回环）
8. 需要目标的包：TargetDeviceId == 本机 Device ID，否则**立即丢弃**（`03_Protocol §20`）
9. 会话类包：SessionId 非全零
10. Payload 按类型解析，长度不足或字段越界 → 丢弃
11. `VOICE_DATA`：SessionId == 当前接收会话，且 SenderDeviceId == 当前会话发送方（`03_Protocol §35`），否则丢弃
12. Payload 大小超类型上限 → 丢弃

任何一步失败：丢弃，计入 invalid-packet 速率统计，不抛异常、不进入业务层。

## Consequences

- `03_Protocol.md` 的 §7 / §8 / §17 / §19 / §31 / §33 必须按本 ADR 重写。
- `05_DataModel.md §8` 的 `interrupt_mode` 语义改为接收方策略 `allow_interrupt`。
- `04_UI_UX.md §22` 的强插 UI 从呼叫方移到设置页（被叫方）。
- 头部 72 字节 + 20ms 帧 → 每方向约 50 包/秒、约 55 kbps。在局域网内可接受；换取的是所有包类型使用统一头部与统一校验路径，实现与测试成本最低。
