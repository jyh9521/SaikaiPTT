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

实现协议数据结构与编解码。

## Required Reading

- **`docs/ADR/ADR-003-Wire-Format.md`（规范来源，逐字节）**
- `docs/03_Protocol.md §7`、`§8`、`§17`

## Requirements

严格按 ADR-003 实现：

- 72 字节大端头部：Magic `SKPT` / ProtocolVersion / PacketType / Flags / PayloadLength / Reserved / SenderDeviceId / TargetDeviceId / SessionId / SequenceNumber / Timestamp
- Device ID 与 Session ID 为 **16 字节二进制 UUID**
- PacketType 枚举，含 v1 实现的 11 种与 4 种保留值
- 各类型的 Payload 编解码（DISCOVERY 系列 / VOICE_START / VOICE_ACCEPT / VOICE_DATA / VOICE_END / BUSY / SESSION_TERMINATE / PING / PONG）
- Sequence 规则（VOICE_START = 0，VOICE_DATA 从 1，VOICE_END = 最后一帧 + 1）
- 回绕安全比较 `isNewer(a, b)`
- 序列化不依赖任何 socket 类型，位于 `core.protocol`
- 编解码使用**预分配可复用缓冲区**，禁止每包分配

## Acceptance Criteria

- 每种已实现 PacketType 的 encode / decode 往返一致。
- **头部布局有逐字节断言测试**（固定字节数组比对），确认字段偏移与大端序与 ADR-003 完全一致。
- Sequence 回绕比较测试覆盖 `0xFFFFFFFE → 0xFFFFFFFF → 0x00000000 → 0x00000001`。
- 协议模型中无 socket 依赖。
