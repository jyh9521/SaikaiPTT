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

建立 UDP socket 与接收管线。

> 顺序说明：本任务原为 Task17，现提前到 Discovery 与 Heartbeat 之前——两者都要在这套 socket 与管线之上发包收包。

## Required Reading

- `docs/ADR/ADR-002-Transport-And-Ports.md`

## Requirements

按 ADR-002 建立：

| Socket | 端口 | 线程 | 承载 |
|---|---|---|---|
| `controlSocket` | 45820（固定，占用时 +1 最多 4 次，`setBroadcast(true)`） | `control-rx` | 控制包 |
| `voiceSocket` | 45821（浮动） | `voice-rx` | VOICE_DATA |

统一管线：

```text
Datagram → Decode → Validate → DomainEvent
```

要求：

- 两个接收循环共用同一套 decode / validate 管线（Task11、Task12）。
- 预分配 2048 字节复用缓冲区，禁止每包分配。
- 结构化并发：明确 scope 与 cancellation，停止时能 join。
- 非法包隔离：单包异常不得终止接收循环。
- **不依赖任何 UI 类型**，不直接修改 UI 状态。
- 本任务不实现完整语音会话处理。

## Acceptance Criteria

- 两个 socket 可正常打开、接收、关闭，无泄漏。
- 控制包可被接收并通过可测试的事件接口分发。
- 取消 scope 后线程正确退出。
