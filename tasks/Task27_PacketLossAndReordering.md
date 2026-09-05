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

增强实时语音流的网络鲁棒性。

## Requirements

在 Task26 的 jitter buffer 之上完善：

- 丢包：继续播放后续帧，**不得无限等待缺失帧**
- 乱序：按 sequence 重排；已越过播放点的迟到包直接丢弃
- 重复：同一 `SessionId + Sequence` 只处理一次
- Session mismatch：非当前会话的包丢弃且不记日志
- Sender mismatch：同一 SessionId 下发送方变化必须拒绝
- **回绕安全的 sequence 比较**（Task11 的 `isNewer`）
- 不对语音帧做 TCP 式重传

统计每次会话的丢包数（`frameCount` 与实收帧数之差），写入 Debug 日志与开发者信息页。

## Acceptance Criteria

- 模拟丢包 / 乱序 / 重复 / 会话不匹配，均不 Crash、不死锁。
- 长时间运行跨越 sequence 回绕边界时播放正常。
- 丢包统计准确。
