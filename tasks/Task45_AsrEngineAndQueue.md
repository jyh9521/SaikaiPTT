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

接入 sherpa-onnx 与识别队列：让一条录音能变成日语字幕。

> 范围说明：本任务与 Task46 是 `ADR-012` 推翻 `ADR-011` 之后新增的，
> 不在原 Task01~44 的计划内。拆成两个的理由与其它任务一样——
> 一个 Task 一个 commit，而「能识别」与「用户能开启并看到」是两件可以分别验收的事。

## Required Reading

- **`docs/ADR/ADR-012-ASR-Reinstated.md`**
- `docs/ADR/ADR-011-ASR-Engine-Review.md`（Vosk 判定与对齐验证仍然有效）
- `docs/ADR/ADR-004-Audio-Params.md`（采样率、帧长、禁止重复编码）
- `docs/05_DataModel.md §22 §23 §35 §36 §37`

## Requirements

### 依赖

- `settings.gradle.kts` 增加 JitPack 仓库，依赖 sherpa-onnx 的 Android AAR
- 版本钉在 `gradle/libs.versions.toml`，不浮动
- 确认 `abiFilters` 生效：APK 里只剩 arm64-v8a 与 armeabi-v7a

### OggOpusReader（`:core`）

Task38 那个 writer 的逆操作。识别要 PCM，而录音是 Ogg 容器里的 Opus。

- 解析 page header、segment table、OpusHead、OpusTags
- 还原出逐帧的 Opus packet
- 校验 CRC；坏页跳过而不是整文件失败
- 处理 `PRE_SKIP_SAMPLES`
- **必须有对着 `OggOpusWriter` 的往返测试**：写进去的帧要原样读出来

不使用 `MediaExtractor`：纯 Kotlin 可在 JVM 上测，且没有低端 ROM 的厂商差异。

### 识别队列（`:core`）

- 状态流转 `NOT_REQUESTED → PENDING → PROCESSING → COMPLETED / FAILED`
- 重试上限 `HistoryConfig.asrMaxRetries`（现为 3），耗尽置 FAILED
- 队列有界；串行处理，同一时刻只识别一条
- 纯逻辑，可在 JVM 上测：状态机、重试计数、边界

### 引擎绑定（`:app`）

- `OfflineRecognizer` + `OfflineTransducerModelConfig`，从**文件**创建（不是 asset）
- `numThreads = 1`；识别线程优先级低于音频线程
- **模型按需加载、复用、空闲时释放**，释放策略要有明确的触发条件
- 识别过程不得阻塞 PTT、`AudioRecord`、`AudioTrack` 或网络线程
- 资源不足时 PTT 优先：正在通话时不启动新的识别

### 仓储

- 按 `transcriptStatus` 查待处理记录（索引 Task37 已建）
- 写回 transcript 与状态
- **不改 schema**

## Acceptance Criteria

- 一条已有录音能产出日语字幕文本。
- 识别失败时录音仍可播放，历史记录仍有效。
- 识别过程中进行 PTT，语音通信不受影响。
- `OggOpusReader` 与 `OggOpusWriter` 的往返测试通过。
- 不产生 schema 迁移。
