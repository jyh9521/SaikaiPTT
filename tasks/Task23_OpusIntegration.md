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

集成 Opus 编解码。

## Required Reading

- **`docs/ADR/ADR-004-Audio-Params.md`**

## Requirements

参数（Config 中已定义）：

- `OPUS_APPLICATION_VOIP`，16 kHz 单声道，20 ms 帧
- 20 kbps CBR，complexity 3，inband FEC 开启，DTX 关闭

选型前必须核实并记录：

- 是否提供 **16 KB page size 对齐**的 `.so`（Android 15+ 要求）
- ABI 覆盖 `arm64-v8a` + `armeabi-v7a`
- Android 11 兼容性
- 维护状态与许可证
- APK 体积与内存开销

**结论必须写入新的 ADR**（ADR-007），包含所选库、版本、编译方式与验证结果。

若无法满足 Android 11 或 16 KB 对齐要求，回退方案为 `MediaCodec` AAC-LC，**并同步升 ProtocolVersion 至 2**；该回退同样需要 ADR，不得静默切换。

实现要求：生命周期安全，最小化分配。

## Acceptance Criteria

- PCM → Opus → PCM 往返测试通过。
- 编码输出长度稳定小于 400 字节。
- `.so` 的 16 KB 对齐已验证并记录。
- ADR-007 已创建。
