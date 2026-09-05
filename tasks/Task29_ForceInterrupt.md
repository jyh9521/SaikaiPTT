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

实现可选的强制插入。

## Required Reading

- `docs/03_Protocol.md §33`、`§34`
- `docs/ADR/ADR-003-Wire-Format.md §6`、`§7`

## Requirements

**这是接收方开关**：`allow_interrupt`（Boolean，默认 false），含义为「允许其他设备打断我正在进行的通话」。

呼叫方**没有**任何强插开关，也无法感知对方策略。

流程：

1. B 忙线且 `allow_interrupt = true` 时，**原子地**把会话所有权从 S1 转移到 S2
2. B → A 发送 `SESSION_TERMINATE(reason = 0x01)`
3. B → C 发送 `VOICE_ACCEPT(S2)`
4. A 收到后结束会话，已采集音频保存为 `INTERRUPTED`
5. 属于 S1 的后续包一律丢弃

竞态：

- 所有权转移必须是单一原子操作
- 并发请求中只有一个被接受，其余返回 BUSY

## Acceptance Criteria

- 开启后 C 可以打断 A → B。
- A 收到终止通知，其记录状态为 `INTERRUPTED`。
- 并发强插测试：只有一个会话被接受，绝不出现两人同时播放。
- 默认关闭时行为与 Task28 完全一致。
