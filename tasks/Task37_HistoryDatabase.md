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

实现 Room 通信记录。

## Required Reading

- **`docs/05_DataModel.md §10`（完整字段表）、`§23`、`§26`、`§27`**

## Requirements

按 `05_DataModel §10` 实现 `CommunicationRecord` 的**全部字段**，注意：

- `transcriptStatus` 为**五态**，含 `NOT_REQUESTED`（默认值）
- `status` 为**三态**：`COMPLETED` / `INTERRUPTED` / `FAILED`，**不引入 TIMEOUT**
- `audioPath` 与 `audioFormat` 可空（保存失败时为 null）
- `remoteDeviceId` 是有意的冗余列，写入时一次性计算

索引：`timestamp DESC`、`remoteDeviceId`、`remoteUserName`、`sessionId`（唯一）、`isRead`、`isFavorite`、`transcriptStatus`、`status`

其它：

- 数据库名 `saikai_ptt.db`，版本从 1 开始
- **禁止生产版本使用 destructive migration**
- 导出 schema 到版本控制
- 记录创建使用 transaction
- **History 故障不得影响 Discovery / Heartbeat / Voice**（`05_DataModel §48`）

## Acceptance Criteria

- 完成一次 PTT 后可以保存有效记录。
- 字段与 `05_DataModel §10` 完全一致。
- Room 异常时核心通信不受影响。
- 索引已建立并有查询测试。
