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

实现统一配置中心，消除 magic number。

## Required Reading

- `docs/ADR/ADR-002-Transport-And-Ports.md`
- `docs/ADR/ADR-003-Wire-Format.md`
- `docs/ADR/ADR-004-Audio-Params.md`

## Requirements

集中定义（不可变、带类型）：

| 组 | 值 |
|---|---|
| 协议 | `protocolVersion = 1`，magic，头部长度 72，payload 上限 1024，VOICE_DATA 上限 400，userName 上限 64 字节 |
| 端口 | `controlPort = 45820`（固定，占用时 +1 最多 4 次），`voicePort = 45821`（浮动） |
| 发现 | announce 重发时序 `0 / 300 / 900 ms` |
| 心跳 | `heartbeatIntervalMs = 5000`，`peerTimeoutMs = 16000`，`presenceEvaluationIntervalMs = 2000` |
| 会话 | `voiceStartRetryMs = 150`，`voiceStartMaxRetries = 2`，`voiceStartTimeoutMs = 500`，`preRollBufferMs = 500`，`sessionIdleTimeoutMs = 3000`，`sessionMaxDurationMs = 300000` |
| 音频 | `sampleRate = 16000`，`channels = 1`，`frameMs = 20`，Opus bitrate 20000 / complexity 3 / FEC on / DTX off |
| Jitter buffer | 起播 3 帧，目标 3 帧，最大 10 帧 |
| 限流 | 控制包 50/s，非法包 20/s，Peer 表上限 64 |
| 历史 | 默认保留 7 天，ASR 重试上限 3 |
| 日志 | 各 category 的开关与 Release 默认值 |

## Acceptance Criteria

- 配置可注入、可在测试中替换。
- 值不在多个模块中重复定义。
- 新建的 core 代码中无 magic number。
- 构建保持绿色。
