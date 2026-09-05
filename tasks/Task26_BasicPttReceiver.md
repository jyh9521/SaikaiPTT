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

实现接收端 PTT，**含最小 jitter buffer**。

> 变更说明：最小 jitter buffer 并入本任务。若先实现「收到就播」再在后续任务插入缓冲，等于重写喂数据路径。

## Requirements

- 校验 TargetDeviceId，**不是本机则立即丢弃**
- 接受合法 VOICE_START → 回 `VOICE_ACCEPT` → 进入 RECEIVING
- **最小 jitter buffer**：起播门限 3 帧（60 ms），目标深度 3 帧，最大 10 帧（200 ms），超出丢弃最旧帧
- 按 sequence 排序，丢帧插入静音帧
- 收到 VOICE_END → 冲刷缓冲 → 收尾 → ENDING → IDLE
- **自动接收，不需要用户确认**
- 进入 / 离开会话时驱动 `peerState` 并立即广播心跳
- Activity 不存在时同样工作（挂在 Task15 的 Service 上）

## Acceptance Criteria

- B 自动听到 A，无需任何操作。
- C 不是目标时收不到声音。
- App 在后台、熄屏、锁屏时仍能接收并播放。
- 起播延迟符合 60 ms 门限设计。
