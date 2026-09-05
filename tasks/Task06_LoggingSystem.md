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

建立统一日志系统。

## Requirements

提供 `Logger.d/i/w/e`，支持 category：

```text
lifecycle, network, discovery, presence, protocol, session,
audio, service, storage, permission, asr, overlay
```

要求：

- Debug 构建可输出详细诊断。
- Release 构建默认禁用 verbose / debug。
- **禁止记录原始音频 payload**。
- 提供频率限制能力（供协议层的非法包日志使用，`03_Protocol §45`：每类每秒最多 1 条）。
- Logger 位于 `core.logger`，不依赖 Android UI 类型；Android 输出通过实现类注入。
- Logger 本身不得成为性能瓶颈：Release 下被禁用的调用不得构造字符串。

## Acceptance Criteria

- 单元测试覆盖等级过滤与频率限制。
- core 代码可在无 Android 依赖下使用 Logger。
- Release 配置确实禁用了 debug 日志。
