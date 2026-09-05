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

实现默认 Busy Mode。

## Requirements

- 目标已有活动会话且 `allow_interrupt = false` 时：返回 `BUSY`，保持原会话不变
- BUSY 包：SessionId = 呼叫方在 VOICE_START 中使用的值，**payload 为空**（不回传第三方会话信息）
- 呼叫方收到 BUSY → `FAILED(TARGET_BUSY) → IDLE`，产生领域层的 busy 结果
- **不生成历史记录**
- 判定完全在被叫方，呼叫方不做本地拒绝

## Acceptance Criteria

- A → B 通话中，C → B 收到 BUSY。
- A 与 B 的会话不受影响。
- C 端不产生历史记录。
- 有单元测试覆盖 busy 判定路径。
