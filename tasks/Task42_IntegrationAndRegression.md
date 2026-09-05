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

完整集成回归测试。

> 变更说明：原 Task40 把集成、性能、发布三件事合成一个任务，与「每个 Task 一个 commit」的纪律不符，现拆为 Task42 / 43 / 44。

## Required Reading

- `docs/07_TestPlan.md`

## Scope

### Network
discovery / heartbeat / reconnect / IP change / 多设备（至少 4 台验证心跳流量与 Peer 表）

### PTT
send / receive / **VOICE_ACCEPT 握手** / busy / force interrupt / **并发强插竞态** / loss / reorder / duplicate / **sequence 回绕**

### Background
foreground / background / screen off / device locked / **发送要求界面可见的约束验证** / notification / overlay / **无悬浮窗权限时的通知降级**

### Storage
recording（双向）/ playback / history / 手动删除 / 批量删除 / cleanup / orphan files / **正在录制文件的保护** / 磁盘写满

### ASR
Japanese recognition / 模型下载与校验 / queue / failure / retry

### Lifecycle
rotation / process recreation / app restart / device reboot / 权限变更

### i18n
五种语言全界面，含缅甸语与孟加拉语字体

### Negative
malformed UDP / 错误 target / 错误 sender / 超大 payload / 未知类型 / 非法 sequence / 洪泛攻击

## Acceptance Criteria

- 全部核心用例通过。
- 任何核心通信路径的 Crash 均视为阻塞项。
- 产出完整测试报告（环境 / 用例 / 结果 / 失败项 / 已知问题）。
