# app.network

UDP socket 与接收循环。两个 socket、两条接收线程，按 `docs/ADR/ADR-002-Transport-And-Ports.md`：
控制/发现 45820，语音 45821。

只负责收发字节与分发领域事件，**不解析协议**（那是 `core.protocol`），
**不管理会话状态**（那是 `core.session`）。

## 已实现（Task14）

| 文件 | 职责 |
|---|---|
| `InboundPacket.kt` | 分发事件 + `InboundPacketListener` |
| `UdpTransport.kt` | 两个 socket、两条命名线程、收发与生命周期 |

两条接收循环共用同一套管线：`Datagram → Decode → Validate → InboundPacket`。
唯一的区别是限流——控制通道按来源限流（`03_Protocol §45`），语音通道不限，
因为它的速率已经被校验第 11 步的会话检查约束住了。

三个容易踩的点，都有回归测试盯着：

1. **停止时先关 socket，再取消协程**。阻塞在 `DatagramSocket.receive` 的线程不响应
   协程取消，也不响应任何别的东西；关掉 socket 才能让那个调用返回。反过来做，
   `stop()` 会一直挂到下一个数据报到达——安静的网络上就是永远。
2. **`receive()` 会把 `DatagramPacket` 的长度改成实际收到的字节数**。不重置的话，
   复用缓冲区会永久缩到「见过的最小数据报」那么大，之后每个包都被截断。
3. **包必须到达 ADR-002 指派给它的那个 socket**。这不是整洁问题：限流只作用于控制
   通道，把控制包发到语音端口就能完全绕开它。走错通道的包记在来源账上。

`MulticastLock`（接收广播所必需，见 ADR-005）**不在这里**：它是有生命周期的电源
资源，归 Service 管；传输层保持纯 java.net，才能脱离设备做单元测试。

## 待实现

发送侧的 Discovery / Heartbeat 调度在 `app.discovery` 与 `app.presence`；
网络恢复时重建两个 socket 并重新通告 voicePort 见 ADR-002「Consequences」。
