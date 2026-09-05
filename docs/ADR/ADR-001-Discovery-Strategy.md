# ADR-001 — 设备发现策略

Status: Accepted
Date: 2026-09-05
Supersedes: —

## Context

`00_MasterPrompt §14`、`02_Architecture §21`、`Task LanDiscovery` 原先写「优先 Android NSD / mDNS，必要时 UDP fallback」，而 `03_Protocol §9/§10` 又完整定义了 `DISCOVERY` 包。两条路线并存导致：

1. 协议任务（Task11）必须在发现策略未定的情况下先固化包类型集合。
2. 若采用 NSD，`DISCOVERY` 包成为死代码；若采用 UDP，NSD 相关设计作废。
3. `NsdManager` 在 minSdk 30 与 API 34+ 之间 API 不同（`registerServiceInfoCallback` / `resolveService` 分别只在一侧可用），必须维护两条代码路径。
4. 参考低端设备为 MTK P22 / Android 11 类机型，此类 ROM 上 NSD 的稳定性历史表现不佳（resolve 需串行、listener 泄漏、部分厂商 ROM 行为不一致），与「低端兼容是一等需求」冲突。

## Decision

**v1 采用纯 UDP 广播发现，作为唯一主方案。不实现 NSD / mDNS。**

- 发现与在线状态**共用同一个控制 socket 与同一份 payload 结构**（见 ADR-002、ADR-003）。
- 设备上线、网络恢复、Active User 变更时，主动广播 `DISCOVERY`，在 `0ms / 300ms / 900ms` 各发一次（抵抗单次丢包）。
- 收到 `DISCOVERY` 的设备立即单播回 `DISCOVERY_RESPONSE`，使新加入设备无需等待一个心跳周期即可看到全网。
- 此后由周期性广播 `HEARTBEAT` 维持在线状态与忙线状态（见 ADR-003 §Payload）。
- 广播地址使用当前 WiFi 接口的**子网定向广播地址**（由接口 IP 与掩码计算），失败时回退到 `255.255.255.255`。

## Rationale

- 一套代码覆盖 Android 11~16，无 API 分支，无厂商 ROM 差异面。
- 用户名变更可即时随下一个广播包生效，无需重新注册服务记录。
- 与心跳共用 socket 和 payload，待机时全网每设备每 5 秒仅 1 个广播包，流量与功耗可预测（NSD 的后台流量不可控）。
- 协议包类型集合可以在 Task11 一次性定死。

## Consequences

- **已知限制**：部分企业级 AP 开启 AP Isolation 或禁用广播转发时无法发现。此限制必须写入 `README` 的「已知限制」与 `01_PRD` 的适用环境说明；v1 不提供手动输入 IP 的补救手段（与 `01_PRD §18` 一致）。
- 接收广播必须持有 `WifiManager.MulticastLock`，见 ADR-005。
- 需要 `ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE` 权限。
- `PeerDiscovery` 接口保持不变，未来若需要 NSD 可作为第二实现接入，不影响上层 Peer 模型。
