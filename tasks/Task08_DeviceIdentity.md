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

实现持久化的 UUID v4 Device ID。

## Requirements

- 首次初始化时生成一次。
- 通过 Task07 的 `SettingsRepository` 存储，**不自建持久化**。
- App 重启、设备重启、IP 变化、WiFi 变化均不改变。
- 不使用 MAC / IMEI / Serial。
- 提供 `DeviceIdentityProvider` 接口，Application scope 单例。
- 同时提供 16 字节二进制形式（协议使用，`ADR-003`）与字符串形式（存储与调试使用）。

## Acceptance Criteria

- 生成合法 UUID v4。
- 重复读取返回相同 ID。
- 二进制与字符串形式可互相转换且往返一致。
- 单元测试覆盖首次生成与持久化。
