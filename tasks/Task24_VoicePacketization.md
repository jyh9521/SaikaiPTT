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

实现 VOICE_START / VOICE_ACCEPT / VOICE_DATA / VOICE_END 的打包与解析。

## Required Reading

- `docs/ADR/ADR-003-Wire-Format.md §4`、`§5`

## Requirements

- 按 ADR-003 的 Payload 布局实现各类型
- Sequence：VOICE_START = 0，VOICE_DATA 从 1 递增，**VOICE_END = 最后一帧 + 1**
- VOICE_END payload 携带 `finalDataSequence` 与 `frameCount`
- VOICE_DATA payload ≤ 400 字节，整包 ≤ 472 字节，**设计上不产生 IP 分片**
- 复用缓冲区，禁止每帧分配

## Acceptance Criteria

- 连续音频帧可打包并正确还原。
- 超限 payload 被拒绝。
- Sequence 规则有测试覆盖，含 VOICE_END 的取值。
- 有测试断言最大包长小于 MTU。
