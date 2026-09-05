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

实现 History Detail 与录音播放 UI。

## Requirements

- play / pause / resume / stop / 进度显示
- 字幕五态显示（`NOT_REQUESTED` 不显示字幕区域；`FAILED` 显示失败提示 + 重新识别入口）
- **播放按钮在任何字幕状态下都必须可用**
- 文件缺失或 `audioPath` 为 null：播放按钮禁用并说明原因，其余信息照常显示，**不得 Crash**
- 进入详情自动标记已读
- 收藏 / 取消收藏
- **播放不得停止通信服务，也不得中断正在进行的 PTT**（播放期间收到 PTT，语音优先）
- 全部状态与错误文案本地化

## Acceptance Criteria

- 真实设备上历史录音可靠播放。
- 文件不存在时显示合理错误且不 Crash。
- 播放期间收到 PTT 时行为符合设计。
