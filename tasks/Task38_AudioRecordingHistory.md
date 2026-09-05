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

将本地录音与通信历史可靠关联。

## Required Reading

- `docs/ADR/ADR-004-Audio-Params.md §6`
- `docs/02_Architecture.md §18`
- `docs/05_DataModel.md §32`、`§34`

## Requirements

### 不做二次编码

- **发送端**：实时编码产生的 Opus 帧同时写入 UDP 与本地文件。
- **接收端**：把收到的原始 Opus 帧（经 jitter buffer 排序去重后）直接写入文件，**不解码后重编码**。
- 容器：Ogg/Opus（`.opus`），16 kHz 单声道。

### 隔离

- 录音写盘在独立线程，通过**有界队列**（容量 = 5 秒帧数）与实时路径解耦。
- **队列满时丢弃录音帧并记 WARN，绝不阻塞实时路径。**

### 文件生命周期

- 临时文件写 `records/.tmp/`，VOICE_END 后 finalize 并移动到 `records/YYYY/MM/DD/<recordId>.<ext>`
- 只有 finalized 文件进入常规清理范围
- **正在录制的文件不得被 cleanup 删除**
- 数据库保存相对路径，不含私有目录前缀

### 失败处理

- 录音写入失败（磁盘满 / IO 错误）：**实时 PTT 必须继续**，记录 `status = FAILED`、`audioPath = null`，向 UI 报 `StorageError`，不 Crash
- Room insert 失败：保留音频文件并标记为 orphan candidate，供后续 cleanup 处理
- 不得创建指向不存在文件的 `COMPLETED` 记录

## Acceptance Criteria

- 发送端与接收端每段有效 PTT 都可以回放。
- 磁盘写满时实时通信不中断。
- 录音写盘不影响 PTT 延迟（有对比测量）。
- 正在录制的文件不会被清理删除。
