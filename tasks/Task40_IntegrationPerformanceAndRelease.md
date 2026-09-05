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

完成最终集成、回归、性能优化和 Release Candidate。

## Requirements

Run:

- complete build
- lint
- unit tests
- integration tests
- two-device real-device tests
- background tests
- network recovery tests
- busy/force interrupt tests
- recording/playback tests
- ASR tests
- i18n tests

Performance measurement must include:

- startup time
- idle CPU
- PTT CPU
- memory
- battery
- network traffic
- PTT latency
- ASR processing time

Devices:

- low-end Android 11 / 4GB / MTK P22-class reference
- modern Android flagship
- Android versions available in the test matrix

Before release:

- verify Release logging is disabled
- remove mock/test UI
- verify privacy
- verify backup behavior
- verify versioning
- update README
- update CHANGELOG
- complete release checklist

## Acceptance Criteria

A Release Candidate may only be declared when all blocking core tests pass and no unresolved critical crash/data-loss/PTT reliability issue remains.

