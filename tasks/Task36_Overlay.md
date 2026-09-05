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

实现系统悬浮窗。

> 顺序说明：本任务原为 Task31，现移到 Home 页面之后——悬浮窗点击需要打开「相关界面」，而该界面在 Task32 才存在。

## Requirements

- 权限感知：未授予时不显示，且**必须由通知承担降级提示**（Task31 已实现）
- 常态绿色，收到 PTT 变红色并显示远程用户名
- 通信结束恢复绿色
- 点击打开 App 对应界面
- **单一轻量 View / ComposeView，不创建多个 Window**
- 动画节制：允许有限时长的闪烁，**禁止持续动画**
- **不得持有通信状态**，只反映状态
- 后台运行时不产生高频重绘

## Acceptance Criteria

- 后台收到 PTT 时颜色正确变化并显示名称。
- 权限关闭时不显示，且通知降级提示生效。
- 点击后正确进入 App。
- 长时间后台运行时悬浮窗不产生可观测的 CPU 占用。
