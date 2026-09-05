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

**新增任务。** 建立前台通信服务骨架，让 socket 与通信组件的生命周期**从一开始就归 Service 所有**。

> 为什么新增：原计划把 Foreground Service 排在 PTT 收发之后。那样 socket、会话、音频资源会先由 Activity 持有，到服务任务时再整体迁移属主——这是全项目最大的返工来源。现在先立骨架，后续任务直接挂载。

## Required Reading

- **`docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`**
- `docs/02_Architecture.md §25`、`§26`

## Requirements

- `CommunicationService`，`foregroundServiceType="connectedDevice|microphone"`
- 启动时以 **`connectedDevice`** 类型调用 `startForeground()`（麦克风类型的运行时提升在 Task25 实现）
- 最小占位通知（正式通知在 Task31）
- 按 `02_Architecture §26` 实现启动 / 停止序列，**每步失败都要倒序回滚已完成的步骤**
- 服务持有 Task14 的两个 socket 与接收循环
- **`MulticastLock`：服务 READY 期间常驻**（不持有则熄屏后收不到广播）
- Service scope 的 DI 容器，随服务创建与销毁
- 停止流程幂等
- `START_STICKY`
- Service **不承担**协议解析、音频、业务逻辑，只做生命周期与组件编排

## Acceptance Criteria

- 服务可干净启动与停止，Activity 不存在时仍持有 socket。
- 停止后 socket、线程、MulticastLock 全部释放，无泄漏。
- 启动中途失败时资源被正确回滚。
- 重复调用 stop 不抛异常。
