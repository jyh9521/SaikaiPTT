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

实现 PTT Session 状态机。

## Required Reading

- `docs/ADR/ADR-003-Wire-Format.md §6`（会话建立时序）
- `docs/03_Protocol.md §19`、`§33`、`§34`

## Requirements

发送端状态：

```text
IDLE → REQUESTING → TRANSMITTING → ENDING → IDLE
        ↓ FAILED(TARGET_BUSY | NO_RESPONSE | MIC_UNAVAILABLE) → IDLE
```

接收端状态：

```text
IDLE → RECEIVING → ENDING → IDLE
       ↓ INTERRUPTED → IDLE
       ↓ FAILED → IDLE
```

要求：

- 每次 PTT 生成新的 SessionId
- **`REQUESTING → TRANSMITTING` 由 `VOICE_ACCEPT` 触发**；超时与重发按 Config（150ms 重发，最多 2 次，累计 500ms 放弃）
- 收到 BUSY → `FAILED(TARGET_BUSY)`
- **会话所有权是单一原子变量**，用一个 Mutex 或 `AtomicReference.compareAndSet` 保护；所有权判定与应答发送在同一临界区内完成
- **禁止用多个 Boolean 表达会话状态**
- 会话超时：`sessionIdleTimeoutMs = 3000`，`sessionMaxDurationMs = 300000`
- 本机存在活动会话时驱动 `peerState = BUSY`

本任务不接音频，用 fake 音频源验证状态机。

## Acceptance Criteria

- 合法与非法状态转换均有单元测试。
- 并发 VOICE_START 的所有权竞争测试通过：只有一个会话被接受。
- 超时路径有测试覆盖。
