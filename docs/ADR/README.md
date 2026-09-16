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
| ADR-006 | 离线日语 ASR 引擎与模型分发 | **Superseded by ADR-011** | Task02, Task03 |
| ADR-007 | Opus 编解码库选型与集成 | Accepted | Task22, Task23, Task26 |
| ADR-008 | 播放路由：改用 `USAGE_MEDIA` | Accepted | Task21, Task26 |
| ADR-009 | 页面导航：手写状态，不引入 Navigation 库 | Accepted | Task33, Task35, Task36, Task37 |
| ADR-010 | 导航加入返回栈（仍不引入 Navigation 库） | Accepted | Task35, Task36, Task37 |
| ADR-011 | ASR 引擎复核（Vosk 不合格、sherpa-onnx 对齐已验证） | **部分被 ADR-012 取代** | Task41, Task44 |
| ADR-012 | 重新引入 ASR：sherpa-onnx + 自行分发 int8 模型 | Accepted | Task45, Task46 |

`ADR-008` 修订 `ADR-004 §3` 的两条（usage 与音量流），其余部分仍以 `ADR-004` 为准。

`ADR-010` 修订 `ADR-009` 的「没有返回栈」一条，其余部分仍以 `ADR-009` 为准。

`ADR-011` 取代 `ADR-006 §1`（引擎）与 `§2/§4`（「约 50 MB」的体积假设）。

`ADR-012` 推翻 `ADR-011` 的 Decision（v1 不集成），理由是后者的体积论证口径错了。
`ADR-011` 对 Vosk 的判定与对 sherpa-onnx 对齐的验证仍然有效，`ADR-012` 直接引用。
`ADR-006 §2` 的模型分发形态（不打进 APK、首次启用时下载、校验失败即删除重下）
在 `ADR-012` 下重新生效。
