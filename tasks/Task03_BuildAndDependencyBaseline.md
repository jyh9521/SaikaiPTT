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

建立可重复、轻量的构建与依赖管理基线。

## Requirements

- Version Catalog（`gradle/libs.versions.toml`）
- **显式钉死** Gradle / AGP / Kotlin / compileSdk / targetSdk / 全部依赖版本
- Debug / Release 配置
- lint 与 test task 配置
- ABI 配置：`arm64-v8a` + `armeabi-v7a`
- 记录构建环境（Gradle / Kotlin / AGP / SDK 版本）到 README，支撑 `08_ReleaseChecklist §51` 的可重复构建要求
- 最小化依赖，移除未使用依赖

### 原生库约束

后续将引入 Opus（Task23）与 Vosk（Task41）。两者的 `.so` 必须 **16 KB page size 对齐**（Android 15+ 要求）。构建脚本需预留验证手段。

## Acceptance Criteria

- Clean / Debug / Release build 均成功。
- 所有版本号集中且显式。
- 依赖清单已文档化。
