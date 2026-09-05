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

实现 LocalUser 数据与仓储逻辑，不做完整 UI。

## Requirements

模型：

```text
LocalUser(id: UUID, displayName: String, createdAt: Long, updatedAt: Long)
```

支持：create / rename / delete / list / activeUser / switchActiveUser

规则：

- `id` 在 `displayName` 修改后保持不变。
- 不能删除最后一个用户。
- 不能直接删除当前 Active User，必须先切换。
- `active_user_id` 指向不存在的用户时进行数据修复，不得 Crash（`05_DataModel §6`）。
- `displayName` 校验：非空、去首尾空白、不允许全空白、**≤ 24 个码位且 ≤ 64 字节 UTF-8**（`03_Protocol §5.1`）。

## Acceptance Criteria

- 数据在重启后保持。
- Active User 无效时能安全自愈。
- 全部业务规则可在无 UI 情况下单元测试。
