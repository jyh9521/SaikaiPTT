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

完成通信记录的搜索、收藏、未读、删除与清理。

> 变更说明：本任务原为 Task39，现补入**手动删除与批量删除**——`01_PRD §54` 与 `05_DataModel §38/§39` 要求该能力，但原任务列表中无人负责。

## Requirements

### 搜索

- 用户名搜索
- 字幕搜索
- v1 使用 `LIKE '%keyword%'`，**不引入 FTS**（`05_DataModel §27.1`）
- 空搜索显示全部历史
- 支持中日缅孟四种文字与特殊字符

### 未读 / 收藏

- 新接收记录默认未读，发送记录默认已读
- 打开记录标记已读
- 未读用圆点 / 粗体 / 图标标记，**不得只用颜色**
- 收藏状态持久化

### 删除

- **单条删除**：二次确认，同步删除音频文件
- **批量删除**：明确确认；收藏记录默认不删除，若用户选择连同收藏一起删除需单独二次确认

### 清理

- 保留期：1 / 3 / 7 / 30 天 / 永久，默认 7 天
- 顺序：查询过期记录 → 删除音频 → 删除数据库记录 → 扫描孤立文件
- **收藏记录不参与自动清理**
- **正在录制、正在播放、正在进行的 PTT 涉及的文件不得删除**
- 单个文件删除失败只记日志并继续，不中止整个清理任务
- 孤立文件清理需考虑文件正在创建的情况

### 存储使用量

打开历史设置时计算一次。

## Acceptance Criteria

- 用户名与字幕搜索均可快速定位记录。
- 未读 / 收藏状态在重启后保持。
- 各保留期清理正确，收藏记录不被删除。
- 清理不产生孤立音频文件，也不误删使用中的文件。
- 手动删除同步删除音频。
