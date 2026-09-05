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

在真实设备上测量性能，对照目标判定。

## Required Reading

- `docs/01_PRD.md §41`（目标值与口径）

## Devices

- 低端：MTK P22 类 / Android 11 / 4 GB RAM
- 现代旗舰：Android 13 / 14 / 15 / 16（至少一台 Samsung Galaxy 旗舰）

## Measurements

全部按**单核占用百分比**计：

| 指标 | 目标 |
|---|---|
| 待机 CPU（熄屏，10 分钟均值） | < 1% of one core |
| 发送 CPU | ≤ 15% of one core |
| 接收 CPU | ≤ 12% of one core |
| Java heap（待机） | < 32 MB |
| 总 PSS（待机，不含 ASR 模型） | < 130 MB |
| 端到端延迟 | P50 ≤ 250 ms，P95 ≤ 400 ms |
| 待机网络 | ≤ 1 广播包 / 5 秒 / 设备 |

另需测量：启动时间、电量消耗（应用停止 / 空闲前台 / 空闲后台 / PTT / ASR 开启五种情形对比）、ASR 处理耗时。

### 长时间稳定性

低端设备后台运行 **8~24 小时**，期间周期性 discovery / heartbeat / PTT / history / ASR。

观察：crash、ANR、内存增长、socket 泄漏、音频资源泄漏。

### 内存泄漏

反复 前台 → 后台 → 前台 多次循环，观察 Activity / Service / AudioRecord / AudioTrack / DatagramSocket / Overlay / Coroutine，确认内存最终稳定。

## Acceptance Criteria

- 全部指标已实测并记录（不是估算）。
- 未达标项已定位原因并给出结论：优化、或修订目标值并说明理由。
- 长时间测试无 crash、无 ANR、无不受控的内存增长。
