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

实现 AudioRecord 采集抽象与基础实现。

## Required Reading

- `docs/ADR/ADR-004-Audio-Params.md §1`、`§2`

## Requirements

- 16 kHz / 单声道 / PCM 16-bit / 20 ms 帧（320 samples）
- `AudioSource` 优先 `VOICE_COMMUNICATION`，初始化失败回退 `MIC`（两者在 Config 中可切换）
- buffer = `max(minBufferSize, 4 × frameBytes)`
- 专用线程，优先级 `THREAD_PRIORITY_URGENT_AUDIO`
- 缓冲区预分配复用，禁止每帧分配
- 麦克风权限感知；初始化或 `startRecording` 失败返回 `MICROPHONE_UNAVAILABLE`
- 生命周期安全的初始化与释放，支持 cancellation
- 无 UI 依赖

不接 UDP。

## Acceptance Criteria

- 真实设备可稳定采集音频。
- 正常与失败初始化后资源都能正确释放。
- 反复 start / stop 多次无泄漏。
