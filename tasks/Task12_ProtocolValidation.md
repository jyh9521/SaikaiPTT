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

实现协议输入验证。

## Required Reading

- **`docs/ADR/ADR-003-Wire-Format.md §8`（校验顺序，必须按序实现）**
- `docs/03_Protocol.md §29`、`§30`、`§45`

## Requirements

按 ADR-003 §8 的 12 步顺序实现校验：长度 → Magic → Version → PayloadLength 一致性 → Reserved → PacketType → Sender 有效性与回环过滤 → Target 匹配 → SessionId → Payload 解析 → 会话与发送方匹配 → 类型专属大小上限。

以及：

- UserName ≤ 64 字节 UTF-8
- VOICE_DATA payload ≤ 400 字节
- 未知 PacketType 安全忽略
- 非法包计入速率统计（`03_Protocol §45` 的阈值）
- 非法包日志频率限制：每类每秒最多 1 条，Release 不打印

## Acceptance Criteria

- 任何非法输入都不抛异常、不进入业务层、不 Crash。
- 测试覆盖：Magic 错误、版本不符、长度不符、超限 payload、未知类型、错误 target、错误 sender、随机字节翻转。
- 有测试确认 Magic 错误时不会继续解析后续字段。
