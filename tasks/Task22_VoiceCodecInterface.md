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

建立可替换的音频 Codec 接口。

## Requirements

```kotlin
interface VoiceCodec {
    val frameSizeSamples: Int
    val maxEncodedBytes: Int
    fun encode(pcm: ShortArray, out: ByteArray): Int
    fun decode(encoded: ByteArray, len: Int, out: ShortArray): Int
    fun release()
}
```

- 领域层与会话层不得依赖任何具体 codec 的 API。
- 接口设计为**调用方提供输出缓冲**，避免每帧分配。
- 提供 fake 实现（直通 PCM）供上层测试。

## Acceptance Criteria

- 可用 fake 实现完成上层单元测试。
- 接口中无 Opus 专有类型。
