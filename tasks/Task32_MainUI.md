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

实现 Home 页面。

## Requirements

显示：

- 当前用户名
- 在线设备列表（用户名 + 状态图标 + 状态文本）
- 已选目标
- 网络状态
- 服务状态
- PTT 按钮

要求：

- 使用 UiState 模型 + ViewModel，**不直接访问 network / audio / storage 实现**
- 单向数据流：Action → ViewModel → UseCase → Domain → UiState → UI
- 忙线设备**仍可点选**，PTT 按钮不因缓存的忙线状态而禁用
- 状态表达**不得只靠颜色**：图标 + 文本必须同时存在
- 心跳更新不得导致整页重组：使用精确的 StateFlow 切分
- PTT 按钮大、易按住、手指轻微滑动不易误释放
- 无 WiFi 时顶部显示提示，设备列表为空且 PTT 不可用
- 空列表显示搜索中提示，不显示 Error

## Acceptance Criteria

- 用户可以选择 Peer 并看到正确状态。
- 心跳更新时不发生整页重组（可用重组计数验证）。
- 所有状态在关闭颜色的情况下仍可分辨。
