# SaikaiPTT Development Plan

## 1. Purpose

本文档定义 SaikaiPTT 从空仓库到可发布版本的完整开发路线。

开发必须采用增量方式进行。

禁止：

- 一次生成整个应用
- 多个核心模块同时开发
- 未验证前继续堆功能
- 用大量临时代码快速拼出 Demo

每个 Task 都具备：明确目标、明确输入、明确输出、明确验收标准、明确测试要求、明确完成条件。

完成一个 Task 后：

1. 编译
2. 测试
3. Review
4. 检查架构影响
5. Git commit
6. STOP

等待下一条指令。

---

# 2. 规范性 ADR

以下 ADR 是本计划的前置条件，**必须在 Task02 之前全部处于 Accepted 状态**。

| ADR | 主题 | 影响的 Task |
|---|---|---|
| ADR-001 | 设备发现策略（纯 UDP 广播） | 11, 16 |
| ADR-002 | 传输层与端口方案 | 05, 14 |
| ADR-003 | 协议二进制线格式 | 11, 12, 19, 24 |
| ADR-004 | 音频参数与 AudioFocus 策略 | 05, 20~24, 38 |
| ADR-005 | 前台服务、Android 兼容与电源锁 | 02, 15, 25, 30 |
| ADR-006 | 离线日语 ASR 引擎与模型分发 | 02, 03, 41 |

开发过程中还会新增：

| ADR | 由哪个 Task 产生 |
|---|---|
| ADR-007 | Task23（Opus 库选型与 16 KB 对齐验证） |
| ADR-008 | Task41（ASR 引擎与模型复核结论） |

**ADR 与 `docs/` 冲突时，以 ADR 为准。**

---

# 3. Development Strategy

```text
Phase 0   Task 01        文档与工程基线
Phase 1   Task 02 - 04   项目骨架
Phase 2   Task 05 - 10   基础核心（配置、日志、存储、身份、i18n）
Phase 3   Task 11 - 13   协议与领域模型
Phase 4   Task 14 - 18   网络栈与服务骨架
Phase 5   Task 19 - 24   会话状态机与音频基础
Phase 6   Task 25 - 30   PTT 收发、Busy、强插、后台服务
Phase 7   Task 31 - 36   UI 与系统集成
Phase 8   Task 37 - 40   通信记录
Phase 9   Task 41        离线 ASR
Phase 10  Task 42 - 44   集成、性能、发布
```

---

# 4. 编号变更说明（重要）

本计划经过一次文档一致性审查后重新编号。**编号即执行顺序**，不需要另外对照顺序表。

主要调整：

| 调整 | 原因 |
|---|---|
| DataStore 提前到 Task07（原 Task10） | Device Identity 与 LocalUser 都依赖 DataStore，否则两者会各自实现一遍持久化 |
| 网络接收循环提前到 Task14（原 Task17） | Discovery 与 Heartbeat 都要在这套 socket 与管线上收发 |
| **新增 Task15 前台服务骨架** | 让 socket 与生命周期从第一天起归 Service 所有，避免 PTT 完成后再整体迁移属主——这是原计划最大的返工来源 |
| 网络恢复后移到 Task18（原 Task16） | socket 在 Task14 才存在、属主在 Task15 才确定 |
| 最小 jitter buffer 并入 Task26 | 先「收到就播」再插入缓冲等于重写喂数据路径 |
| **新增 Task34 权限引导** | `01_PRD §24/§50` 要求的完整权限流程原先无人负责 |
| Overlay 后移到 Task36（原 Task31） | 悬浮窗点击需要打开的界面在 Task32 才存在 |
| 手动删除并入 Task40 | `01_PRD §54` 要求的删除能力原先无人负责 |
| ASR 后移到 Task41（原 Task38） | 默认关闭的可选功能，优先级低于核心通信与历史 |
| 原 Task40 拆为 Task42 / 43 / 44 | 集成、性能、发布是三件事，与「一个 Task 一个 commit」的纪律相符 |

新旧编号对照：

```text
新 01←01  02←02  03←03  04←04  05←05  06←06  07←10  08←07
   09←08  10←09  11←11  12←12  13←13  14←17  15←新增
   16←14  17←15  18←16  19←18  20←19  21←20  22←21  23←22
   24←23  25←24  26←25  27←26  28←27  29←28  30←29  31←30
   32←32  33←33  34←新增  35←34  36←31  37←35  38←36  39←37
   40←39  41←38  42←40a  43←40b  44←40c
```

---

# 5. Task Rules

当收到：

```text
Execute TaskXX
```

必须：

1. 阅读 `.claude/CLAUDE.md`
2. 阅读相关 `docs/`
3. **阅读该 Task 的 Required Reading 中列出的 ADR**
4. 阅读当前 Task
5. 检查现有代码
6. 明确依赖
7. 只实现当前 Task
8. 运行必要测试
9. 汇报修改内容与测试结果
10. 停止

不得主动执行后续 Task。

---

# 6. Task 清单

## Phase 0 — 基线

| # | Task | 产出 |
|---|---|---|
| 01 | ProjectRepositoryBaseline | 仓库、`.gitignore`、文档与 ADR 结构确认，工作区干净 |

## Phase 1 — 项目骨架

| # | Task | 产出 |
|---|---|---|
| 02 | AndroidProjectInitialization | 可编译工程；**Manifest 的 FGS 类型与权限一次写对**；NDK/ABI 预留 |
| 03 | BuildAndDependencyBaseline | Version Catalog，**版本全部显式钉死**，可重复构建 |
| 04 | ModuleSkeleton | `app` + `core` 两个 module，其余为 package；手写 DI 容器骨架 |

## Phase 2 — 基础核心

| # | Task | 产出 |
|---|---|---|
| 05 | ConfigurationSystem | 集中配置，含协议 / 端口 / 心跳 / 会话 / 音频 / 限流全部数值 |
| 06 | LoggingSystem | 分类日志、Release 禁用 debug、频率限制 |
| 07 | DataStoreSettings | `SettingsRepository`，全部 key 与默认值，损坏值安全降级 |
| 08 | DeviceIdentity | UUID v4，二进制与字符串双形式 |
| 09 | LocalUserManagement | 用户名 CRUD、Active User 自愈、长度校验 |
| 10 | InternationalizationFoundation | i18n 基础设施；**本阶段只做 `ja` / `en`** |

## Phase 3 — 协议与领域模型

| # | Task | 产出 |
|---|---|---|
| 11 | ProtocolDataStructures | 72 字节头部编解码，**逐字节断言测试** |
| 12 | ProtocolValidation | 12 步校验顺序，非法输入零 Crash |
| 13 | PeerDomainModel | Peer 状态机，`BUSY` 由心跳驱动 |

## Phase 4 — 网络栈与服务骨架

| # | Task | 产出 |
|---|---|---|
| 14 | NetworkReceiveLoop | 两个 socket、两个接收线程、统一 decode/validate 管线 |
| 15 | ForegroundServiceSkeleton | **服务持有 socket 与 MulticastLock**，启停可回滚 |
| 16 | LanDiscovery | UDP 广播发现 + 即时应答，两台真机互相发现 |
| 17 | HeartbeatPresence | 广播心跳、超时判定、**忙线状态通告** |
| 18 | NetworkRecovery | 断线重连、IP 变化、socket 重建 |

## Phase 5 — 会话与音频基础

| # | Task | 产出 |
|---|---|---|
| 19 | SessionManager | 发送/接收状态机，**VOICE_ACCEPT 闭合**，原子会话所有权 |
| 20 | AudioCapture | AudioRecord 抽象，16k/20ms |
| 21 | AudioPlayback | AudioTrack 抽象 + AudioFocus 策略 |
| 22 | VoiceCodecInterface | 可替换 codec 接口 + fake 实现 |
| 23 | OpusIntegration | Opus 集成，**16 KB 对齐验证**，产出 ADR-007 |
| 24 | VoicePacketization | 语音包打包解析，Sequence 规则 |

## Phase 6 — PTT 与后台

| # | Task | 产出 |
|---|---|---|
| 25 | BasicPttSender | 发送端 PTT，**按下即录**，FGS 类型运行时提升 |
| 26 | BasicPttReceiver | 接收端 PTT，**含最小 jitter buffer** |
| 27 | PacketLossAndReordering | 丢包/乱序/重复/回绕 |
| 28 | BusyMode | 被叫方判定，返回 BUSY，不产生记录 |
| 29 | ForceInterrupt | **接收方开关**，原子所有权转移，竞态处理 |
| 30 | ForegroundServiceCompletion | 完整组件编排、电源锁复核、开机启动 |

## Phase 7 — UI

| # | Task | 产出 |
|---|---|---|
| 31 | Notification | 持续通知 + **无悬浮窗时的降级提示** |
| 32 | MainUI | Home、Peer 列表、目标选择、PTT 按钮 |
| 33 | UsernameUI | 首启创建与用户名管理 |
| 34 | PermissionOnboarding | **分步权限引导 + 权限状态页** |
| 35 | SettingsUI | 设置页 + **补齐五种语言与字体实测** |
| 36 | Overlay | 悬浮窗，绿/红状态 |

## Phase 8 — 通信记录

| # | Task | 产出 |
|---|---|---|
| 37 | HistoryDatabase | Room schema、索引、migration |
| 38 | AudioRecordingHistory | **不做二次编码**，有界队列隔离，失败不影响 PTT |
| 39 | AudioPlaybackUI | History Detail 与播放 |
| 40 | HistoryManagement | 搜索、收藏、未读、**手动/批量删除**、清理 |

## Phase 9 — ASR

| # | Task | 产出 |
|---|---|---|
| 41 | OfflineJapaneseASR | 引擎复核（ADR-008）、模型下载、队列与重试 |

## Phase 10 — 发布

| # | Task | 产出 |
|---|---|---|
| 42 | IntegrationAndRegression | 完整集成回归 + 测试报告 |
| 43 | PerformanceMeasurement | 真机性能实测，对照 `01_PRD §41` 判定 |
| 44 | ReleasePreparation | 发布检查、README、CHANGELOG、Sign-off |

---

# 7. Task Dependency Rules

核心依赖顺序：

```text
Project baseline
 ↓
Build / Modules
 ↓
Config / Logging / DataStore
 ↓
Identity / LocalUser / i18n
 ↓
Protocol
 ↓
Peer model
 ↓
Network stack  ──►  Foreground Service skeleton
 ↓
Discovery / Heartbeat / Recovery
 ↓
Session state machine
 ↓
Audio / Codec / Packetization
 ↓
PTT send / receive
 ↓
Busy / Force Interrupt
 ↓
Service completion
 ↓
UI / Permissions / Overlay
 ↓
History / Recording / Playback / Management
 ↓
ASR
 ↓
Integration → Performance → Release
```

禁止跳过核心依赖直接实现高级功能。

**Task11 是硬门槛**：在 ADR-001 / 002 / 003 全部 Accepted 之前不得开始协议编码。协议头部与包类型集合一旦写错，会连锁影响 Task12、13、19、24~29。

---

# 8. Git Strategy

每个 Task 一个 commit：

```text
feat: implement device identity
feat: implement LAN discovery over UDP broadcast
feat: implement heartbeat and busy-state advertisement
feat: implement PTT sender with pre-roll buffering
feat: implement PTT receiver with jitter buffer
feat: implement communication history
```

文档与决策：

```text
docs: add protocol specification
docs: record ADR-007 opus library selection
```

修 Bug：

```text
fix: recover discovery after wifi reconnect
```

不混合无关任务，不提交密钥。

---

# 9. Task Completion Report

每次 Task 完成后必须报告：

## Summary
完成什么。

## Files Changed
哪些文件。

## Tests
运行了什么。

## Result
成功 / 失败。

## Known Issues
尚未解决的问题。

## Architecture Impact
是否修改公共接口、协议、数据库、模块边界。**如有重大变化，必须创建 ADR。**

## ADR
本 Task 是否产生或引用了 ADR。

最后：

> Task completed. Waiting for next instruction.

然后停止。

---

# 10. Definition of Done

一个 Task 只有在以下条件全部满足后才算完成：

- implementation complete
- compilation successful
- relevant tests pass
- no known blocking error
- architecture remains consistent
- **相关 ADR 已阅读并遵循；如有偏离，已创建新 ADR**
- documentation updated if necessary
- no unrelated feature added

---

# 11. Final Rule

开发过程中不要追求「尽快把所有功能写出来」，而要追求「每完成一个阶段，就得到一个可信赖的系统」。

```text
Correct → Stable → Tested → Optimized → Extended
```

而不是：

```text
Feature → Feature → Feature → Bug → Rewrite
```
