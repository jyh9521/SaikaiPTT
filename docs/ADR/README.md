# Architecture Decision Records

本目录记录 SaikaiPTT 的重大技术决策。

规则：

- 任何影响协议格式、数据库 schema、模块边界、公共接口、持久化标识或 Android 兼容性的决策，必须先有 ADR。
- ADR 一经 Accepted，不得静默修改。需要变更时新建一份 ADR 并将旧 ADR 标记为 Superseded。
- 每份 ADR 至少包含：Status / Context / Decision / Rationale / Consequences。

## 索引

| ADR | 主题 | Status | 阻塞的 Task |
|---|---|---|---|
| ADR-001 | 设备发现策略 | Accepted | Task11, Task16 |
| ADR-002 | 传输层与端口方案 | Accepted | Task05, Task14 |
| ADR-003 | 协议二进制线格式 | Accepted | Task11, Task12 |
| ADR-004 | 音频参数与 AudioFocus 策略 | Accepted | Task05, Task20-24 |
| ADR-005 | 前台服务、Android 版本兼容与电源锁 | Accepted | Task02, Task15, Task30 |
| ADR-006 | 离线日语 ASR 引擎与模型分发 | Accepted | Task02, Task03, Task41 |
