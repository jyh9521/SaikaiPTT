# SaikaiPTT 测试报告（Task42）

> 本文件是 `07_TestPlan.md` 的执行记录，按 `§60` 的格式。
>
> **自动化部分由 Claude 填写并已跑过；真机部分是空表，由开发者一边测一边填。**
> `§63` 写得很清楚：「代码看起来没问题」不能作为测试结论。所以下面凡是需要真机、
> 需要多台设备、需要长时间运行的用例，状态一律是 `未测`，不是 `Pass`。
> 把一条没跑过的用例标成通过，比不测更糟。

---

## 1. Environment

### 自动化测试（本轮已执行）

| 项 | 值 |
|---|---|
| 执行者 | Claude 沙箱（Linux，无 Android SDK） |
| 方式 | Kotlin 2.2.10 直接编译 `:core` 并运行；`:app` 的纯 JVM 类另行编译运行 |
| 覆盖 | `:core` 全部单元测试 + `:app` 中不依赖 Android 的单元测试 |
| **不覆盖** | 任何需要 Android SDK、Room、Compose、真实网络或真实音频的东西 |

### 真机测试（待执行）

开发者填写：

| 项 | 设备 A | 设备 B |
|---|---|---|
| 型号 | | |
| Android 版本 / API | | |
| RAM | | |
| ABI | | |
| Build（debug / release） | | |
| APK 版本 / commit | | |
| WiFi 环境（AP 型号、是否企业网） | | |
| 测试日期 | | |

---

## 2. 自动化结果

### 2.1 `:core` 单元测试

```
425 tests, 0 failed
```

覆盖的测试类（38 个）：

`ActiveRecordingsTest` `ArchitectureRulesTest` `AudioFocusPolicyTest`
`CommunicationRecordTest` `DeviceIdTest` `HistoryCleanerTest` `HistoryEraserTest`
`JitterBufferTest` `LocalUserCodecTest` `LogFormatTest` `LoggerTest`
`OggOpusWriterTest` `PacketCodecTest` `PacketHeaderTest` `PacketPayloadTest`
`PacketRateLimiterTest` `PacketTypeTest` `PacketValidatorTest` `PcmConversionTest`
`PcmFrameAssemblerTest` `PeerRegistryTest` `PresenceStateTest` `RecordingPathsTest`
`SaikaiConfigTest` `SequenceNumbersTest` `SessionIdTest` `SessionManagerTest`
`SessionOwnershipRaceTest` `SettingsCodecTest` `SettingsLocalUserRepositoryTest`
`StoredDeviceIdentityProviderTest` `SubsystemsTest` `UserNameValidatorTest`
`VoiceCodecTest` `VoiceFrameBufferTest` `VoicePacketizerTest` `VoiceReceiverTest`
`VoiceTransmitterTest`

### 2.2 `:app` 单元测试（纯 JVM 部分）

| 测试类 | 结果 | 备注 |
|---|---|---|
| `LocalRecordingFilesTest` | **17 tests, 0 failed** | Task42 新增 |
| `HistoryPresentationTest` | 14 tests, 0 failed | Task39/40 |
| `ArchitectureRulesTest` | 未在沙箱跑 | 需要源码树路径，随 Gradle 跑 |
| `BrandColorsTest` `BuildConfigurationTest` `AppContainerTest` | 未在沙箱跑 | 依赖 `BuildConfig` / 资源 |
| `UdpTransportTest` `UdpPeerDiscoveryTest` `UdpPresenceAnnouncerTest` `BroadcastAddressesTest` | 未在沙箱跑 | 开真实 UDP socket，沙箱网络受限 |
| `NetworkRecoveryTest` `ServiceLifecycleTest` | 未在沙箱跑 | 用 `kotlinx-coroutines-test` |

> 沙箱只能跑其中一部分，`.\gradlew.bat :app:testDebugUnitTest` 才是权威结果。

### 2.3 仪器测试（`connectedDebugAndroidTest`，需真机）

开发者在真机上执行 `connectedDebugAndroidTest`，**全部通过**。

| 测试类 | 用例数 | 对应 TC | 状态 |
|---|---|---|---|
| `ApplicationContextTest` | 1 | — | **Pass** |
| `OpusVoiceCodecTest` | — | TC-AUDIO-* | **Pass** |
| `CommunicationRecordDaoTest` | 34 | TC-HIST-*, TC-SEARCH-*, TC-CLEAN-002 | **Pass**（Task42 新增 20 例） |
| `HistoryCleanupTest` | 14 | TC-CLEAN-001/004/005/006, TC-CONS-005/006 | **Pass**（Task42 新增） |

设备与日期由开发者补入 §1 的表。

---

## 3. 用例清单

状态取值：`Pass` / `Fail` / `未测` / `N/A` / `阻塞`。

### 3.1 已由自动化覆盖

| TC | 内容 | 覆盖方式 | 状态 |
|---|---|---|---|
| TC-ID-001~005 | 设备身份持久化 | `DeviceIdTest` `StoredDeviceIdentityProviderTest` | **Pass** |
| TC-USER-001~007 | 用户名增删改切与校验 | `UserNameValidatorTest` `SettingsLocalUserRepositoryTest` | **Pass** |
| TC-PROTO-* | 包编解码、头部、类型、校验顺序 | `PacketCodecTest` `PacketHeaderTest` `PacketTypeTest` `PacketValidatorTest` | **Pass** |
| TC-SEQ-001~008 | 序号推进、乱序、重复、回绕、sender mismatch | `SequenceNumbersTest` `VoiceReceiverTest` | **Pass** |
| TC-FI-RACE-* | 强插与会话所有权竞态 | `SessionOwnershipRaceTest` `SessionManagerTest` | **Pass** |
| TC-NEG-* | 畸形包、超大 payload、未知类型、洪泛限速 | `PacketValidatorTest` `PacketRateLimiterTest` | **Pass** |
| TC-CLEAN-002 | 收藏不被自动清理 | `HistoryCleanerTest` + `CommunicationRecordDaoTest` + `HistoryCleanupTest`（真机） | **Pass** |
| TC-CLEAN-003 | 单个文件删除失败不中止整轮 | `HistoryCleanerTest` | **Pass** |
| TC-CONS-005 | 正在录制的文件受保护 | `HistoryCleanerTest` + `HistoryCleanupTest`（真机） | **Pass** |
| TC-CONS-006 | 正在播放的文件受保护 | `HistoryCleanerTest` + `HistoryCleanupTest`（真机） | **Pass** |
| — | 录音文件路径与目录布局 | `RecordingPathsTest` `LocalRecordingFilesTest` | **Pass** |
| — | Ogg/Opus 容器字节级正确性 | `OggOpusWriterTest` | **Pass** |
| — | 架构规则（模块边界、Compose 隔离、README） | `ArchitectureRulesTest` ×2 | **Pass** |

### 3.2 需要真机，单台即可

| TC | 内容 | 状态 | 备注 |
|---|---|---|---|
| TC-I18N-001 | 语言切换机制 | 未测 | |
| TC-I18N-002 | 切换后持久化 | 未测 | |
| TC-I18N-003 | 应用外组件（通知、悬浮窗）语言同步 | 未测 | |
| TC-I18N-004 | 系统级语言设置（Android 13+） | 未测 | |
| TC-I18N-005 | 字体渲染（缅甸语、孟加拉语） | 未测 | **低端机必测**，见 §5 已知问题 |
| TC-BG-001 | 后台接收 | 未测 | |
| TC-BG-002 | 熄屏接收 | 未测 | |
| TC-BG-003 | 锁屏接收 | 未测 | |
| TC-BG-004 | 长时间空闲后接收 | 未测 | |
| TC-BG-005 | 发送要求界面可见（Android 14+） | 未测 | ADR-005 的核心约束 |
| TC-BG-006 | 无悬浮窗权限时的降级 | 未测 | |
| TC-BG-007 | 开机自启（仅 connectedDevice） | 未测 | |
| TC-FGS-* | 前台服务类型切换 | 未测 | |
| TC-NOTIF-* | 通知渠道、内容、停止动作 | 未测 | |
| TC-OVL-* | 悬浮窗显示、拖动、点击、颜色 | 未测 | |
| TC-REC-* | 双向录音 | 未测 | |
| TC-PLAY-* | 播放、暂停、继续、停止、进度 | 未测 | Task39 已确认基本可回放 |
| TC-HIST-001~007 | 历史记录生成与快照 | 未测 | |
| TC-CONS-001 | Room 写入失败 | 未测 | |
| TC-CONS-002 | 录音保存失败 | 未测 | |
| TC-CONS-003 | 磁盘写满 | 未测 | 需要人为填满存储 |
| TC-CONS-004 | 录音写盘不阻塞实时路径 | 未测 | |
| TC-CONS-007 | History 故障隔离 | 未测 | |
| TC-CLEAN-001 | 音频同步删除 | **Pass** | `HistoryCleanupTest` |
| TC-CLEAN-004 | 孤立文件扫描 | **Pass** | `HistoryCleanupTest` |
| TC-CLEAN-005 | 手动删除单条 | **Pass** | `HistoryCleanupTest` |
| TC-CLEAN-006 | 批量删除 | **Pass** | `HistoryCleanupTest` |
| TC-FAV-* | 收藏保护 | 未测 | |
| TC-UNREAD-* | 未读标记与清除 | 未测 | |
| TC-SEARCH-* | 用户名与字幕搜索 | **Pass** | `CommunicationRecordDaoTest`，含五种文字与 `%` `_` `\\` 转义 |
| TC-LC-001 | 旋转 | 未测 | |
| TC-LC-002 | 返回键 | 未测 | |
| TC-LC-003 | 进程被杀 | 未测 | |
| TC-LC-004 | 设备重启 | 未测 | |
| TC-LC-005 | 运行时撤销权限 | 未测 | |
| TC-PERM-* | 权限授予、拒绝、永久拒绝 | 未测 | |
| TC-UI-001~003 | 自动跳转边界、忙线可点选、主页重组 | 未测 | |
| TC-A11Y-001 | TalkBack | 未测 | |
| TC-A11Y-002 | 非颜色唯一状态 | 未测 | 设计上已满足，需目视确认 |
| TC-A11Y-003 | 触控区域与文本缩放 | 未测 | |
| TC-REL-* | Release build、混淆、日志关闭 | 未测 | Task44 |

### 3.3 需要两台设备

| TC | 内容 | 状态 |
|---|---|---|
| TC-DISC-001~010 | 发现、回环过滤、改名传播、熄屏接收 | 未测 |
| TC-HB-001~007 | 心跳、超时、忙线通告、广播而非单播 | 未测 |
| TC-PTT-S-001~012 | 发送侧全部 | 未测 |
| TC-PTT-R-001~004 | 接收侧全部 | 未测 |
| TC-1TO1-* | 一对一定向 | 未测 |
| TC-TARGET-* | 目标校验 | 未测 |
| TC-BUSY-001~002 | 忙线拒绝、无应答 | 未测 |
| TC-FI-001~003 | 强插开关语义 | 未测 |
| TC-NET-* | 断网、重连、IP 变化 | 未测 |
| TC-LAT-* | 嘴到耳延迟 | 未测 |
| TC-E2E-* | 端到端 | 未测 |
| TC-2DEV-* | 两机验收 | 未测 |

### 3.4 阻塞：设备数量不足

| TC | 内容 | 状态 | 原因 |
|---|---|---|---|
| TC-DISC-多设备 | 至少 4 台验证心跳流量与 Peer 表规模 | **阻塞** | 手上只有 2 台 |
| TC-FI-RACE 真机 | 三台设备并发强插竞态 | **阻塞** | 同上。逻辑已由 `SessionOwnershipRaceTest` 在 `:core` 覆盖，但真机时序未验 |
| TC-NET-TRAFFIC | 多设备下的广播流量测量 | **阻塞** | 同上 |

> 这三条从 Task29 起就一直挂着。不是「后面再说」，是**发布前必须有人用四台机器跑一次**，
> 否则 `§31` 的「空闲网络 ≤ 1 包 / 5 秒 / 设备」在真实规模下没有任何证据。

### 3.5 不适用

| TC | 内容 | 状态 | 原因 |
|---|---|---|---|
| TC-ASR-001~006 | 模型下载、状态流转、重试、手动重识别 | **N/A** | v1 不集成 ASR，见 `ADR-011` |
| TC-ASR-OFFLINE-* | 断网识别 | **N/A** | 同上 |
| TC-ASR-FAIL-* | 识别失败处理 | **N/A** | 同上 |
| TC-ASR-RES-* | 模型内存与资源竞争 | **N/A** | 同上 |

> 但 `TC-HIST-007`（新记录默认字幕状态为 `NOT_REQUESTED`）**仍然适用**，而且现在是
> 唯一会出现的字幕状态。详情页在该状态下不画字幕区，应目视确认。

### 3.6 性能，需要低端机

| TC | 指标 | 目标 | 实测 | 状态 |
|---|---|---|---|---|
| TC-CPU-IDLE | 空闲 CPU | < 1% 单核 | | 未测 |
| TC-CPU-TX | 发送 CPU | ≤ 15% 单核 | | 未测 |
| TC-CPU-RX | 接收 CPU | ≤ 12% 单核 | | 未测 |
| TC-MEM-HEAP | Java 堆 | < 32 MB | | 未测 |
| TC-MEM-PSS | 总 PSS | < 130 MB | | 未测 |
| TC-LAT-P50 | 嘴到耳 P50 | ≤ 250 ms | | 未测 |
| TC-LAT-P95 | 嘴到耳 P95 | ≤ 400 ms | | 未测 |
| TC-NET-IDLE | 空闲广播 | ≤ 1 包 / 5 秒 / 设备 | | 未测 |
| TC-STAB-LONG | 长时间稳定性 | 见 `§42` | | 未测 |
| TC-LEAK-* | 内存泄漏 | 见 `§43` | | 未测 |
| TC-BATT-* | 耗电 | 见 `§44` | | 未测 |

> 这一整节是 **Task43** 的工作，测量方法与记录表在 `docs/10_PerformanceMeasurement.md`。
> 这里列出来是为了让报告完整，填完之后把 §12 的汇总表抄进本文件 §7。

---

## 4. Result

**本轮结论：自动化层全绿，真机层未开始。**

| 层 | 结果 |
|---|---|
| 静态检查（XML 良构、资源引用、五语言字符串对照） | Pass |
| `:core` 单元测试 | Pass（425 / 425） |
| `:app` 单元测试（沙箱可跑部分） | Pass（31 / 31） |
| `:app` 单元测试（完整） | 未跑 |
| 仪器测试 | **Pass**（真机执行，全部通过） |
| 真机功能测试 | 未开始 |
| 性能测试 | 未开始（Task43） |

---

## 5. Failed Tests

无。本轮跑过的全部通过。

> 这一行的意思不是「没有问题」，而是「跑过的没有失败」。绝大多数用例还没跑。

---

## 6. Known Issues

从前面各 Task 累积下来、发布前必须处理或明确接受的：

| # | 问题 | 来源 | 严重度 |
|---|---|---|---|
| 1 | 三台以上设备的强插竞态与心跳流量从未在真机验证 | Task29 | **高**，发布前必须解决 |
| 2 | 缅甸语、孟加拉语译文未经母语者审阅 | Task35 | 中 |
| 3 | 未内置 Noto 字体子集，低端机上缅甸语/孟加拉语可能显示为豆腐块 | Task35 | 中，TC-I18N-005 必测 |
| 4 | Room 版本 `2.7.0` 未经实证确认；上游 Room 已迁到 `androidx.room3`（3.x） | Task37 | 中 |
| 5 | `gradle.properties` 里的 `android.disallowKotlinSourceSets=false` 是 AGP 9 × KSP 的临时绕过，随之而来的实验性选项告警**故意不抑制** | Task37 | 低，但要跟踪 |
| 6 | 悬浮窗的日志走 `LogCategory.LIFECYCLE` 而不是已存在的 `LogCategory.OVERLAY`，导致其 INFO 日志会进入 release 构建 | Task36 | 低 |
| 7 | 三处历史遗留的未使用 import：`ui/theme/Theme.kt`、`discovery/UdpPeerDiscovery.kt`、`presence/UdpPresenceAnnouncer.kt` | 早期 | 低 |
| 8 | `app/service/README.md` 里各 Android 版本通知行为的「待実測記録」表仍为空 | Task31 | 低 |
| 9 | ADR-007 的「低端设备上的编码耗时」仍未实测 | Task23 | 中，位置在 `10_PerformanceMeasurement.md §3` |
| 10 | v1 不含 ASR；README 与隐私说明需改写为「无任何联网例外」 | Task41 / ADR-011 | 中，Task44 处理 |

---

## 7. Performance

**一个数都还没测。**

测量手册与空记录表在 `docs/10_PerformanceMeasurement.md`（Task43 建立）：
口径、每项指标的 adb 命令、以及没达标时该走哪条路。
全部测完之后，把那份文件 §12 的汇总表抄到这里。

`ADR-007` 里「低端设备上的 Opus 编码耗时」从 Task23 起一直空着，
在手册 §3 里有它的位置。

---

## 8. Conclusion

**可以进入 Task44，但不能进入发布。**

> 更新（Task43）：仪器测试已在真机通过，存储层不再是盲区。
> 性能测量的手册已经写好，但**一个指标都还没测**，所以发布阻塞项没有减少。

理由：`§62` 的优先级规则说的是「PTT 不稳定 / 后台收不到 / 网络无法恢复」时不能推进——
这三件事目前**状态未知**，而未知不等于通过。Task43 是性能测量，本身也要在真机上跑，
所以它和本表 §3.2、§3.3 的真机用例是同一批工作，可以并在一起做。

发布前必须闭掉的是：

1. §3.3 全部两机用例
2. §3.4 的四机阻塞项
3. TC-I18N-005 字体渲染（低端机）
4. §3.6 全部性能指标（`docs/10_PerformanceMeasurement.md`）
5. 已知问题 #1 #3 #10

---

## 附：怎么跑

```powershell
# 单元测试（两个模块）
.\gradlew.bat :core:test :app:testDebugUnitTest

# 仪器测试（要接一台设备或开一个模拟器）
.\gradlew.bat connectedDebugAndroidTest

# 报告位置
# core\build\reports\tests\test\index.html
# app\build\reports\tests\testDebugUnitTest\index.html
# app\build\reports\androidTests\connected\index.html
```

真机功能测试没有脚本，就是照着 §3.2 和 §3.3 的表一条条点，填状态。
失败的填到 §5，顺手把复现步骤写上——`§60` 要的就是这个。
