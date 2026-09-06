# core.protocol

Packet 编解码与校验，严格按 `docs/ADR/ADR-003-Wire-Format.md` 实现。

72 字节大端头部、包类型、各类型 Payload、12 步校验顺序、序列号回绕比较。

**不得依赖任何 socket 类型**：协议是纯粹的字节与结构，传输在 `app.network`。

由 **Task11 / Task12** 填充。
