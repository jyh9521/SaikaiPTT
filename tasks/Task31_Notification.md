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

实现 Foreground Service 的持续通知。

## Requirements

- 稳定的 notification channel（重要性设为不打扰级别）
- 本地化文案，来自资源
- 常态文案：`SaikaiPTT が動作中です`
- **接收中的降级提示**（无悬浮窗权限时）：`SaikaiPTT — 受信中：<名前>`，通信结束后恢复常态
- **每次会话最多更新 2 次**（开始、结束），禁止逐秒刷新
- 点击通知打开对应界面
- Android 13+ 的 `POST_NOTIFICATIONS` 未授予时，服务仍需正常运行（只是用户看不到状态）
- 不同 Android 版本的行为差异需实测记录

## Acceptance Criteria

- 服务运行期间通知持续存在。
- 不产生重复通知或刷屏。
- 未授予通知权限时服务不崩溃、不停止。
- 接收时通知内容按设计更新。
