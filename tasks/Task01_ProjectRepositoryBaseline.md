# SaikaiPTT Task

## Execution Rules

Before implementing this task:

1. Read `.claude/CLAUDE.md`.
2. Read the relevant documents under `docs/`.
3. Read this task completely.
4. Inspect the current repository and existing implementation.
5. Do not assume the repository is empty or matches the planned structure.

Scope rule:

- Implement ONLY this task.
- Do not silently implement later tasks.
- Do not redesign unrelated modules.
- Do not modify protocol, database schema, public interfaces, or module boundaries unless this task explicitly requires it.
- If an architectural change is necessary, explain why before making it and create/update an ADR when appropriate.

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

建立并确认项目开发基线，不实现 Android 业务代码。

## Required Reading

- `docs/00_MasterPrompt.md`
- `docs/01_PRD.md`
- `docs/02_Architecture.md`
- `docs/03_Protocol.md`
- `docs/04_UI_UX.md`
- `docs/05_DataModel.md`
- `docs/06_DevelopmentPlan.md`
- `docs/07_TestPlan.md`
- `docs/08_ReleaseChecklist.md`

## Work

- Inspect Git repository.
- Inspect current files and branches.
- Verify `.claude/CLAUDE.md`.
- Verify documentation structure.
- Verify `.gitignore`.
- Verify README.
- Do not create business implementation.

## Acceptance Criteria

- Repository baseline is understood.
- Existing documentation is preserved.
- No unrelated files are changed.
- A concise repository status report is produced.

