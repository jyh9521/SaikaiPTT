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

处理 WiFi 断开、重连与 IP 变化。

> 顺序说明：本任务原为 Task16。socket 在 Task14 才建立、属主在 Task15 才确定，因此恢复逻辑必须排在两者之后，否则会写两遍。

## Requirements

监听网络变化（`ConnectivityManager` 回调，不轮询），处理：

```text
CONNECTED → DISCONNECTED → RECOVERING → CONNECTED
```

恢复流程：

1. 终止活动语音会话，标记 `INTERRUPTED`
2. 关闭并重建两个 socket（控制端口固定，语音端口重新分配）
3. 清理失效 endpoint，Peer 全部置 OFFLINE
4. 重新 Discovery（announce ×3），通告新的 voicePort
5. 重启 Heartbeat
6. 更新 Peer endpoint

规则：

- **Device ID 永不因 IP 变化而改变。**
- 不尝试跨网络状态强行恢复实时语音会话。
- 恢复过程不得 Crash，不得泄漏 socket。

## Acceptance Criteria

- WiFi 关闭 → 打开后自动重新发现，无需重启 App。
- IP 变化后 Device ID 不变，Peer endpoint 正确更新。
- 反复断开 / 恢复多次后无 socket 泄漏。
