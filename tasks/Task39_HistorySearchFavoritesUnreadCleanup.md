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

完成通信记录增强功能。

## Requirements

Implement:

- remote username search
- transcript search
- favorite
- unread
- retention cleanup
- orphan audio cleanup

Retention:

- 1 day
- 3 days
- 7 days
- 30 days
- forever

Favorites are protected from normal cleanup.

## Acceptance Criteria

Search works for usernames and transcripts.
Unread/favorite state persists.
Cleanup removes eligible records and their audio safely.

