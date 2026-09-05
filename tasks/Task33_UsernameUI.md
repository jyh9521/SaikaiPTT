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

实现首次用户名设置与用户名管理界面。

## Requirements

- 首启创建流程：标题、说明、输入框、创建按钮
- 校验：非空、去首尾空白、不允许全空白、**≤ 24 码位且 ≤ 64 字节 UTF-8**
- 管理：新增 / 编辑 / 删除 / 切换，当前用户明显标记
- 不能删除最后一个用户；不能直接删除当前用户（需先切换）
- **没有用户名时不能 PTT**
- 编辑未保存时返回需提示（`04_UI_UX §46`）
- **通话进行中不允许切换 Active User**，提示通话结束后再切换
- 全部文案本地化

## Acceptance Criteria

- 完整的本地用户名生命周期可通过 UI 完成。
- 无用户名时 PTT 不可用。
- 通话中切换用户被正确阻止。
- 未保存编辑返回时有提示。
