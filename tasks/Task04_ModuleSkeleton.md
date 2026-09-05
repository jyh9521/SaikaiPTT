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

按 `02_Architecture §5` 建立模块与 package 边界。

## Requirements

**只拆两个 Gradle module：**

```text
app     Android 应用、UI、Service、DI 装配
core    纯 Kotlin/JVM，业务与领域逻辑
```

其余边界用 package 表达，不建独立 Gradle module：

```text
core/  common, config, logger, protocol, domain, session
app/   network, discovery, presence, audio, storage, service, overlay, asr, ui, di
```

### 强制约束

- `core` **不得**依赖任何 `android.*` / `androidx.*` 类型。
- 任何 package **不得**反向依赖 `ui`。
- 只有 `app.di` 可以同时引用接口与实现。
- 无循环依赖。

### DI

按 `02_Architecture §7`：**手写轻量容器 `AppContainer`，不引入 DI 框架**。本任务只建立容器骨架与 scope 划分（Application scope / Service scope），不装配具体实现。

## Acceptance Criteria

- 依赖方向正确，无循环依赖。
- `core` 可用纯 JVM 单元测试运行。
- 工程编译通过。
- 未添加任何业务实现。
