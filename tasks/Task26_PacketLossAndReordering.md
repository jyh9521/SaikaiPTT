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

增强实时语音流的网络鲁棒性。

## Requirements

Handle:

- packet loss
- out-of-order
- duplicates
- late packets
- session mismatch

Use a bounded jitter buffer.

Do not implement TCP-like retransmission for every audio frame.

## Acceptance Criteria

Simulated loss/reordering/duplicates do not crash or deadlock playback.

