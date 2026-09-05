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

实现 AudioTrack 播放抽象与基础实现。

## Required Reading

- `docs/ADR/ADR-004-Audio-Params.md §3`、`§7`

## Requirements

- `AudioAttributes` = `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`，`PERFORMANCE_MODE_LOW_LATENCY`
- `AudioManager.mode` 保持 `MODE_NORMAL`（半双工，无回声路径）
- 音量走 `STREAM_VOICE_CALL`，默认扬声器，跟随系统路由
- 有界 buffer，start / stop / release / cancellation
- 初始化失败返回结构化错误
- **AudioFocus 策略**：会话开始申请 `AUDIOFOCUS_GAIN_TRANSIENT`，结束释放；`LOSS` 与 `LOSS_TRANSIENT` 均**立即终止会话**（不做暂停恢复）；`CAN_DUCK` 按 `LOSS_TRANSIENT` 处理

不接网络。

## Acceptance Criteria

- 真实设备可稳定播放测试音频。
- 来电时会话被正确终止且不 Crash。
- 释放后无泄漏。
