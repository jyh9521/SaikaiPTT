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

模型分发与用户界面：让用户能开启字幕、看到进度、看到结果。

## Required Reading

- **`docs/ADR/ADR-012-ASR-Reinstated.md`**
- `docs/ADR/ADR-006-ASR-Engine-And-Model-Delivery.md §2`（分发形态，在 ADR-012 下重新生效）
- `docs/04_UI_UX.md §29.1`（五种字幕状态的界面表现）

## Requirements

### 模型下载

- **不打进 APK**
- 默认关闭。用户在设置里首次开启时，显示明确提示后才开始下载（约 153 MiB）
- 下载到应用私有目录
- **校验 SHA-256 与预期大小**，任一不符即删除并要求重新下载
- 下载失败 / 用户取消：ASR 保持关闭，**不影响任何其它功能**
- 可中断、可续传或可重来；进度可见
- 状态写入 `asr_model_ready`
- 下载源是本项目自己的 GitHub Release 资产（`ADR-012 §2`）

### 界面

- 设置页：开关、下载确认、下载进度、模型占用空间、删除模型
- 详情页：五种字幕状态按 `04_UI_UX §29.1` 呈现
  - `NOT_REQUESTED` 不画字幕区
  - `FAILED` 显示失败说明 **+ 手动重新识别入口**（Task39 留的文案位置）
- 全部文案五语言

### 隐私与文档

`ADR-011` 让 v1 变成「不访问互联网，一次都不」，以下几处都照此写过，**必须改回来**：

- `README.md` 的「隐私」与「已知限制」
- `CHANGELOG.md` 的「不包含」与「隐私」
- `AndroidManifest.xml` 中 `INTERNET` 权限的注释
- `.claude/CLAUDE.md §18` 的段头

措辞以 `ADR-012 §Consequences` 为准：核心功能永不需要互联网，唯一例外是首次下载模型。

### 开源许可

设置页增加「开源许可」项，列出 libopus、sherpa-onnx、reazonspeech 及各自许可证
（`ADR-012 §5`）。

### 低端设备实测

`ADR-012 §Consequences` 定的门槛，测完记进 `docs/10_PerformanceMeasurement.md`：

| 指标 | 门槛 |
|---|---|
| 识别耗时 / 音频时长 | ≤ 1.0×，超过 2× 视为不可用 |
| 识别期间的 PTT | 必须完全不受影响 |
| 识别时的 PSS 增量 | 记录即可 |

**这一项必须真测**，不得以「看起来还行」结案（`CLAUDE.md §31.2`）。
测不过就把功能标为「仅现代设备可用」或撤回——默认关闭让这三个结果都不伤核心功能。

## Acceptance Criteria

- 断网状态下（模型已下载）可以生成日语字幕。
- 下载中断或校验失败后，ASR 保持关闭，其余功能完全不受影响。
- 五种字幕状态在界面上表现正确，FAILED 有可用的重新识别入口。
- 隐私措辞已在上述四处同步改回。
- 低端设备实测数据已记录。
