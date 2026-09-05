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

实现接收端 PTT。

## Requirements

- validate target
- accept valid VOICE_START
- receive VOICE_DATA
- play audio
- finalize on VOICE_END
- automatic receive without user confirmation

## Acceptance Criteria

B automatically hears A.
C does not hear A when C is not the target.

