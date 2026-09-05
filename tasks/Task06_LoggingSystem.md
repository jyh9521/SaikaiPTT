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

建立统一日志系统。

## Requirements

Provide:

- `Logger.d`
- `Logger.i`
- `Logger.w`
- `Logger.e`

Support categories.

Debug builds may log detailed diagnostics.

Release builds must disable verbose/debug logging by default.

Never log raw audio payloads.

Avoid high-frequency spam.

## Acceptance Criteria

- Unit tests cover logging behavior where practical.
- Core code can use Logger without Android-specific UI dependencies.
- Release configuration disables debug logging.

