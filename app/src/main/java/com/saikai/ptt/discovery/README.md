# app.discovery

纯 UDP 广播发现（`docs/ADR/ADR-001-Discovery-Strategy.md`）。不实现 NSD/mDNS。

只回答「局域网里有哪些设备」，**不负责判断谁还在线**——那是 `presence`。

由 **Task16** 填充。

## 已实现（Task16）

| 文件 | 职责 |
|---|---|
| `BroadcastAddresses.kt` | 由接口 IP 与掩码算出子网定向广播地址，失败回退 `255.255.255.255` |
| `UdpPeerDiscovery.kt` | 广播通告 + 单播应答，两端都喂 `PeerRegistry` |

广播地址是**自己算的**（`address or mask.inv()`），不是读 `InterfaceAddress.getBroadcast()`
——后者在 Android 的部分接口上返回 null。顺序也重要：很多 AP 丢弃受限广播
`255.255.255.255` 却转发定向广播，把回退当主路会让发现在恰恰是本产品目标的网络上不可靠。

**应答路径跑在控制接收线程上，不能挂起**，所以本机 presence 快照缓存在一个 volatile
字段里，通告时刷新。通告与应答因此各用一个缓冲区：它们在不同线程上，谁也不该等谁。

**没名字就不广播**。全新安装还没设置名字时保持沉默——否则全网每台设备的列表里都会
多一行空白，而且谁也打不通。同样的检查也拦住空端口。

对端通告的 `voicePort` 为 0 时丢弃：协议允许任何 u16，但 0 不是能被呼叫的端口，
放进列表只会让后面的发送莫名其妙地失败。

Peer 的 endpoint 一律取**数据报实际到达的源地址**，不取对端自称的地址——那是唯一
不可能过时或说谎的值（`03_Protocol §11`）。

## 待实现

- 周期性 HEARTBEAT 与超时评估（Task17）
- 网络恢复后重新通告（`AnnounceReason.NETWORK_RECOVERED` 已定义，触发在网络恢复任务）
