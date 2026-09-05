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

集成本地日语语音识别。

> 顺序说明：本任务原为 Task38，现下沉到历史功能之后——ASR 是默认关闭的可选功能，优先级低于核心通信与历史。

## Required Reading

- **`docs/ADR/ADR-006-ASR-Engine-And-Model-Delivery.md`**

## Requirements

### 引擎选型复核（开始实现前必须完成）

- 是否提供 **16 KB page size 对齐**的 `.so`（Android 15+）
- ABI 覆盖 `arm64-v8a` + `armeabi-v7a`
- 引擎与模型的许可证与再分发条款
- MTK P22 / 4 GB 设备上的常驻内存与识别耗时

结论写入新的 ADR（ADR-008）。若复核不通过需更换引擎，同样记录 ADR，不得静默替换。

### 模型分发

- **不打进 APK**
- 用户首次开启时确认并下载（约 50 MB）到应用私有目录
- 记录预期大小与摘要，校验失败则删除并要求重新下载
- 下载失败 / 取消：ASR 保持关闭，不影响任何其它功能
- 状态写入 `asr_model_ready`
- **这是全应用唯一的联网路径**，必须在隐私说明中单独列出

### 识别

- PTT 结束后入队，后台处理
- 状态流转 `NOT_REQUESTED → PENDING → PROCESSING → COMPLETED / FAILED`
- 重试上限 3 次，之后置 FAILED，用户可手动重新识别
- **不得阻塞 PTT、AudioRecord、AudioTrack 或网络线程**
- 模型按需加载、复用、空闲时释放
- 资源不足时 **PTT 优先**
- 默认关闭

## Acceptance Criteria

- 断网状态下（模型已下载）可以生成日语字幕。
- 识别失败时录音仍可播放，历史记录仍有效。
- 识别过程中进行 PTT，语音通信不受影响。
- ADR-008 已创建。
