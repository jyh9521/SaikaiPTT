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

实现在线状态与忙线状态通告。

## Required Reading

- `docs/03_Protocol.md §12`、`§13`
- `docs/02_Architecture.md §22`

## Requirements

- **广播**心跳，与 Discovery 共用 socket 与 payload 结构（不是逐 Peer 单播——N 台设备单播会产生 N×(N-1) 个包）
- `heartbeatIntervalMs = 5000`，`peerTimeoutMs = 16000`（3 个周期 + 容差）
- 任何来自该 Peer 的有效包都刷新 `lastSeen`
- **单次丢包不得立即判定离线**
- Peer 从 OFFLINE 恢复：收到任意有效包即刻转回 ONLINE
- **`peerState` 忙线通告**：本机存在任何活动语音会话时置 BUSY；状态变化时**立即额外广播一次**，不等下一周期
- 与 Discovery 分离，不混写
- 不得每次心跳重新创建网络对象

## Acceptance Criteria

- 两台设备可自动判断对方在线 / 离线。
- 单次丢失心跳不导致离线。
- 连续错过 3 个周期后判定离线。
- 一端进入通话后，另一端在 1 秒内看到「通話中」。
