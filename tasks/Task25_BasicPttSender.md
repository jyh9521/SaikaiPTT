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

实现发送端 PTT。

## Required Reading

- `docs/ADR/ADR-003-Wire-Format.md §6`
- `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md §2`、`§3`
- `docs/01_PRD.md §10`

## Flow

```text
PTT press
→ 本地前置检查（Active User / Target / Online / 无其它会话 / 麦克风权限 / 界面可见）
→ 生成 SessionId，REQUESTING
→ 发送 VOICE_START
→ 立即开始采集并本地缓冲（上限 500ms），暂不发数据
→ 收到 VOICE_ACCEPT → TRANSMITTING → 补发缓冲帧 → 实时发送 VOICE_DATA
```

松开：

```text
停止采集 → VOICE_END → finalize recording hook
```

## Requirements

- **按下即录**：采集与请求同时开始，不丢失首个音节
- 收到 BUSY → `FAILED(TARGET_BUSY)`，丢弃缓冲，**不生成历史记录**
- 500ms 无应答 → `FAILED(NO_RESPONSE)`，同样不生成记录
- **不做本地忙线拒绝**：即使列表显示对方忙线也要实际发出请求
- **麦克风类型 FGS 的运行时提升**：按下 PTT 时 `startForeground()` 加入 `microphone`，松开后调回 `connectedDevice`
- 发送期间持有 `WifiLock(FULL_LOW_LATENCY)` 与带 5 分钟超时的 `PARTIAL_WAKE_LOCK`，结束立即释放
- 录音在存储任务完成前可用抽象 / fake 实现

## Acceptance Criteria

- 两台真实设备可完成 A → B 的 PTT。
- 首个音节不丢失。
- 目标忙线时收到 BUSY 且不产生历史记录。
- 电源锁在会话结束后确实释放。
