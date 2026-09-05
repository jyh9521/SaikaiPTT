# ADR-002 — 传输层与端口方案

Status: Accepted
Date: 2026-09-05

## Context

`03_Protocol §6` 列出 Discovery / Control / Voice 三个端口后写「实际端口号由最终 Architecture / Config 决定」，并允许合并端口；`Task NetworkReceiveLoop` 又要求「统一 UDP 接收路径」。端口数量、socket 数量、线程模型三者未定，直接影响配置中心、网络恢复要重建哪些资源、以及实时语音是否与控制包争抢解析线程。

## Decision

**两个 UDP socket，两个专用接收线程。**

| 用途 | 端口（默认） | Socket | 线程 | 承载包类型 |
|---|---|---|---|---|
| Discovery + Control | UDP **45820** | `controlSocket`，bind `0.0.0.0:45820`，`setBroadcast(true)` | `control-rx` | DISCOVERY, DISCOVERY_RESPONSE, HEARTBEAT, PING, PONG, VOICE_START, VOICE_ACCEPT, VOICE_END, BUSY, SESSION_TERMINATE |
| Voice | UDP **45821** | `voiceSocket`，bind `0.0.0.0:45821` | `voice-rx` | VOICE_DATA |

- 端口号集中定义于 `Config`，禁止散落。
- 若 45820/45821 被占用，依次尝试 +1，最多 4 次；实际使用的 voice 端口通过 DISCOVERY / HEARTBEAT payload 通告给对端（见 ADR-003）。控制端口必须固定，否则无法被发现。
- **语音发送为 Unicast，目的地址 = 对端 endpoint IP + 对端通告的 voicePort。**
- 广播只在控制端口上发生。

## Rationale

- 语音包（50 包/秒）与控制包（约 0.2 包/秒）分离，控制包的解析、Peer 表更新、状态机跳转不会阻塞语音接收线程，抖动可控。
- 仅两个 socket，网络恢复时重建成本低（ADR-005）。
- 会话建立类包（VOICE_START / ACCEPT / BUSY / END / TERMINATE）走控制通道，与语音数据解耦，Force Interrupt 的终止通知不会被语音洪流延迟。

## Consequences

- `Task NetworkReceiveLoop` 的「统一接收路径」重新定义为：**两个接收循环，共用同一套 decode → validate → DomainEvent 管线**，而不是单一 socket。
- `Task NetworkRecovery` 必须同时重建两个 socket 并重新通告 voicePort。
- 两个接收线程均使用预分配的 `ByteArray` 复用缓冲区，禁止每包分配（`03_Protocol §51`）。
