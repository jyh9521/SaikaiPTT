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

完善 Task15 的服务骨架，使其达到生产标准。

## Requirements

- 完整的组件编排：discovery、presence、session、audio、network recovery 全部挂载
- 完整的启动 / 停止序列与失败回滚（`02_Architecture §26`）
- 前台服务类型的运行时切换已在 Task25 实现，此处复核其生命周期正确性
- 电源锁策略复核：`MulticastLock` 常驻、`WifiLock` 与 `WakeLock` 仅会话期间
- `BOOT_COMPLETED` 接收器：以 `connectedDevice` 类型启动，处于「可接收、不可发送」状态
- `START_STICKY`；不承诺进程被杀后自动恢复通信（Android 12+ 限制）
- Service **不承担**协议、音频、业务逻辑
- 明确的 cancellation 与 SupervisorJob 隔离：单个子系统失败不得杀死整个服务

## Acceptance Criteria

- App 退到后台、Activity 销毁后仍保持网络接收。
- 服务停止后所有资源释放，无 socket / 音频 / 线程泄漏。
- 开机后服务以可接收状态启动。
- 单个子系统（如 discovery）抛错不影响语音接收。
