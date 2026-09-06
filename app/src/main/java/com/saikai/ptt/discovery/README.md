# app.discovery

纯 UDP 广播发现（`docs/ADR/ADR-001-Discovery-Strategy.md`）。不实现 NSD/mDNS。

只回答「局域网里有哪些设备」，**不负责判断谁还在线**——那是 `presence`。

由 **Task16** 填充。
