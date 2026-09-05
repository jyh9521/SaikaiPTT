# SaikaiPTT Task

## Execution Rules

Before implementing this task:

1. Read `.claude/CLAUDE.md`.
2. Read the relevant documents under `docs/`.
3. **Read the ADRs listed under Required Reading below.** Where an ADR and a `docs/` file disagree, the ADR wins.
4. Read this task completely.
5. Inspect the current repository and existing implementation.
6. Do not assume the repository is empty or matches the planned structure.

Scope rule:

- Implement ONLY this task.
- Do not silently implement later tasks.
- Do not redesign unrelated modules.
- Do not modify protocol format, database schema, public interfaces, or module boundaries unless this task explicitly requires it.
- If an architectural change is necessary, explain why before making it, and create a NEW ADR (never edit an accepted one silently).

After implementation:

1. Build the affected project.
2. Run relevant tests.
3. Review changed files.
4. Check for architecture regressions.
5. Summarize changes.
6. Report tests and results.
7. Report known issues.
8. STOP and wait for the next instruction.

---

## Goal

实现局域网自动发现。

## Required Reading

- **`docs/ADR/ADR-001-Discovery-Strategy.md`**
- `docs/03_Protocol.md §9`、`§10`

## Requirements

**纯 UDP 广播发现，不实现 NSD / mDNS。**

- 上线、网络恢复、Active User 变更时广播 DISCOVERY，在 `0 / 300 / 900 ms` 各发一次
- 收到 DISCOVERY 立即单播回 DISCOVERY_RESPONSE
- 广播地址：优先用接口子网定向广播（由 IP 与掩码计算），失败回退 `255.255.255.255`
- 过滤 SenderDeviceId == 本机的回环包
- Payload 携带 `peerState` / `voicePort` / `userName`
- 同一 deviceId 的 IP 变化只更新 endpoint，不创建新 Peer
- 挂载在 Task15 的 Service 上，使用 Task14 的 controlSocket
- 接口：`PeerDiscovery`，实现可替换

不实现语音传输。

## Acceptance Criteria

- 两台真实设备在同一 WiFi 下可互相发现。
- 第三台设备加入后自动出现。
- 重复 DISCOVERY 不产生重复 Peer。
- 改名后对端在下一个广播内看到新名称。
