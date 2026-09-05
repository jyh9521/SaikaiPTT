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

建立并确认项目开发基线。不实现任何 Android 业务代码。

## Required Reading

- `docs/00_MasterPrompt.md` ~ `docs/08_ReleaseChecklist.md`
- `docs/ADR/README.md` 与全部 6 份 ADR

## Work

- 检查 Git 仓库、分支、工作区状态。
- 确认 `.claude/CLAUDE.md` 与 `docs/` 结构完整。
- 确认 `.gitignore` 存在且覆盖 Gradle / Android / NDK / IDE / 模型文件。
- 确认 `docs/ADR/` 存在且 6 份 ADR 齐全。
- 确认 README 已提交。
- 提交当前所有未提交的文档改动。
- 不创建任何业务实现。

## Acceptance Criteria

- 工作区干净，无未提交文件。
- 文档与 ADR 结构完整。
- 产出一份简明的仓库状态报告。
