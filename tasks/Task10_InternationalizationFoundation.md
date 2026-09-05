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

建立 i18n 基础设施。**本任务只落地 `ja` 与 `en` 两种语言。**

> 顺序说明：`zh-CN` / `my` / `bn` 的完整翻译推迟到 Task35（Settings UI）之后统一进行。在 UI 尚未定型时翻译五种语言，会导致同一批文案被反复重译。

## Requirements

- 资源限定符约定：`values/`（日语，默认）、`values-en/`、后续 `values-zh-rCN/`、`values-my/`、`values-bn/`
- `res/xml/locales_config.xml` 列出全部五种语言
- 语言切换使用 `AppCompatDelegate.setApplicationLocales()`（Android 13+ 由系统托管，13 以下由 AndroidX 兼容层处理）
- 语言选择同时写入 DataStore 的 `app_language`，供通知与悬浮窗等应用外组件取值
- 新建 UI / 业务代码中**禁止硬编码用户可见字符串**
- 复数使用 Android plural resources；日期时间与数字按 Locale 格式化
- 通知与悬浮窗文案从一开始就走资源

## Acceptance Criteria

- 一个内部演示页面可在 `ja` / `en` 之间切换且立即生效。
- 切换结果在重启后保持。
- lint 中的硬编码字符串检查开启且通过。
