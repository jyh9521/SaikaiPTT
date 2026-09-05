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

创建可编译的基础 Android 工程，并**一次性写对 Manifest 的服务类型与权限声明**。

## Required Reading

- `docs/ADR/ADR-005-Foreground-Service-And-Compatibility.md`
- `.claude/CLAUDE.md §4`

## Requirements

- Kotlin + Gradle Kotlin DSL
- `applicationId` / `namespace` = `com.saikai.ptt`
- `minSdk = 30`
- `compileSdk` / `targetSdk` 由 Task03 的 Version Catalog 钉死具体版本号，**不得使用「开发机上最新的 SDK」**
- Compose 基础环境
- 单元测试基础设施
- Debug / Release build types
- 预留 NDK / CMake / ABI 配置（`arm64-v8a`、`armeabi-v7a`），Opus 与 Vosk 都是原生库

### Manifest（按 ADR-005 §1）

声明以下权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`。

服务声明预留 `android:foregroundServiceType="connectedDevice|microphone"`（服务实现在 Task15）。

不实现 network / audio / Room 业务逻辑。

## Acceptance Criteria

- Debug build 成功。
- Release build 配置成功。
- 可启动一个最小占位 Activity。
- Manifest 的权限与服务类型声明与 ADR-005 一致。
- 未实现任何核心业务功能。
