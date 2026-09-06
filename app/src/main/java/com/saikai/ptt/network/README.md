# app.network

UDP socket 与接收循环。两个 socket、两条接收线程，按 `docs/ADR/ADR-002-Transport-And-Ports.md`：
控制/发现 45820，语音 45821。

只负责收发字节与分发领域事件，**不解析协议**（那是 `core.protocol`），
**不管理会话状态**（那是 `core.session`）。

由 **Task14** 填充。
