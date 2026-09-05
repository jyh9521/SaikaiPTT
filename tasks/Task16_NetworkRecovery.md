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

处理 WiFi 断开、重连和 IP 变化。

## Requirements

Support:

- disconnect detection
- socket cleanup/recreation
- discovery restart
- heartbeat restart
- endpoint refresh
- peer state recovery

Never change Device ID because IP changes.

Active real-time sessions may terminate as Interrupted when network continuity is lost.

## Acceptance Criteria

WiFi off → on results in automatic peer rediscovery without app restart.

