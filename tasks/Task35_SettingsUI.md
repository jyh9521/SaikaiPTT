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

实现 Settings 页面，并**补齐五种语言的翻译**。

## Requirements

分组（按 `04_UI_UX §34`）：

- ユーザー：当前用户名、用户名管理
- 通話：**割り込みを許可**（`allow_interrupt`，接收方策略，默认关闭，附风险说明）、音频设置
- 通信履歴：保存期间、搜索入口、存储使用量、删除历史
- 字幕：ASR 开关、模型状态 / 下载、重新识别
- バックグラウンド：通知、悬浮窗、电池优化、权限状态
- 言語
- 情報：版本、已知限制

### ASR 开关的特殊处理

首次开启必须先弹确认框，说明「初回のみ約 50 MB のダウンロードが必要」，显示下载进度且可取消。失败或取消时开关保持 OFF，不影响其它功能。

### 语言

补齐 `zh-CN` / `my` / `bn` 翻译（`ja` / `en` 已在 Task10 完成）。

**在低端参考设备上实测缅甸语与孟加拉语的字体渲染**；若缺字或出现 Zawgyi 错乱，内置 Noto 字体子集并显式指定字体族，同时评估 APK 体积影响。

### Debug 信息

Device ID、当前 IP、协议版本、端口、Discovery / Heartbeat / Session 状态、ASR 状态、日志开关。**仅 Debug Build 可见。**

### 存储使用量

打开该分组时计算一次即可，不实时刷新。

## Acceptance Criteria

- 所有设置能保存并反映真实的服务 / 数据状态。
- 五种语言均可切换且无明显布局溢出或缺字。
- Release Build 中不存在任何 Debug 信息入口。
