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

**新增任务。** 实现首次启动的分步权限引导与权限状态页。

> 为什么新增：`01_PRD §24/§50` 与 `04_UI_UX §6/§36` 要求完整的权限引导流程，但原 40 个任务中没有任何一个负责它——它被含糊地混在 Settings 任务里。

## Required Reading

- `docs/01_PRD.md §24`、`§50`
- `docs/04_UI_UX.md §36`
- `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`

## Requirements

首启分步引导，每次只解释一个权限，说明「不授予会失去什么」：

| 权限 | 缺失后果 |
|---|---|
| 麦克风 | 不能发送，仍可接收 |
| 通知（Android 13+ 运行时） | 看不到运行状态，更易被系统回收 |
| 悬浮窗 | 后台提示降级为通知 |
| 电池优化白名单 | 后台可能被回收 |
| 自启动（厂商项） | 开机不恢复 |

要求：

- **允许跳过任意一项**，跳过后应用仍可运行，能力相应降级
- 权限被拒绝后**不得反复弹窗骚扰**
- 权限状态页展示当前状态并提供再次跳转入口
- **不得硬编码厂商设置页路径**；跳转失败降级为文字指引
- 引导完成状态写入 `first_launch_completed` / `permission_guidance_shown`
- 全部文案本地化

## Acceptance Criteria

- 分别拒绝麦克风 / 通知 / 悬浮窗，应用均不 Crash 且能力按设计降级。
- 权限状态页反映真实系统状态。
- 跳转失败时有可读的替代指引。
