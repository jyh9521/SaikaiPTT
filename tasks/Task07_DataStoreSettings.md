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

建立 `SettingsRepository`，作为**所有本地设置的唯一入口**。

> 顺序说明：本任务原为 Task10。Device Identity 与 LocalUser 都依赖 DataStore，因此必须先于它们完成，否则后两者会各自实现一遍持久化，Task10 变成重构。

## Required Reading

- `docs/05_DataModel.md §3`（完整 key 清单与默认值）

## Requirements

持久化 `05_DataModel §3` 中列出的全部 key：

`device_id`、`local_users`、`active_user_id`、`app_language`、`allow_interrupt`、`history_retention`、`asr_enabled`、`asr_model_ready`、`overlay_enabled`、`first_launch_completed`、`permission_guidance_shown`

要求：

- 使用类型化模型，不散落字符串 key。
- 每个 key 有明确默认值。
- 缺失 / 损坏值必须安全降级到默认值，不得 Crash（`05_DataModel §41`）。
- 以 Flow 暴露读取。

## Acceptance Criteria

- 所有设置在进程重启后保持。
- 损坏数据不导致 Crash。
- 单元测试覆盖默认值与损坏值恢复。
