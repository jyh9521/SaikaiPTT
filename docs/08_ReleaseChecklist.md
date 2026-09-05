# SaikaiPTT Release Checklist

## 1. Purpose

本文档定义 SaikaiPTT 在 Release Candidate 和正式版本发布之前必须完成的检查项。

发布目标：

> 只发布已经经过构建、自动化测试、真实设备测试、网络测试、后台测试、性能测试和数据完整性验证的版本。

不得因为“功能已经完成”就直接发布。

---

# 2. Release Principles

Release 前必须优先确认：

1. 核心 PTT 稳定
2. 后台接收可靠
3. 网络自动恢复正常
4. 录音和历史记录可靠
5. 低端设备可运行
6. Android 11+ 兼容
7. 隐私和本地数据要求满足
8. 国际化完整
9. Debug 功能不会泄漏到 Release
10. 没有阻塞性 Crash / ANR

---

# 3. Version Information

Release 前确认：

- versionName / versionCode
- applicationId / namespace = `com.saikai.ptt`
- Application display name = `西海PTT`
- minSdk = 30
- **compileSdk / targetSdk 为 Version Catalog 中显式钉死的具体版本**（不得是「开发机上最新的」）
- release signing configuration
- dependency versions

## 3.1 前台服务声明复核

- `android:foregroundServiceType="connectedDevice|microphone"` 已声明
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` 与 `FOREGROUND_SERVICE_MICROPHONE` 权限已申请
- 运行时类型切换正确：常驻 `connectedDevice`，发送期间加入 `microphone`
- `BOOT_COMPLETED` 只以 `connectedDevice` 类型启动

## 3.2 权限清单复核

确认已声明且**没有多余权限**：

`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`、`RECEIVE_BOOT_COMPLETED`、`WAKE_LOCK`

# 4. Build Validation

必须成功：

```text
Debug build
Release build
```

检查：

- Kotlin compilation
- resources
- AndroidManifest
- Gradle configuration
- dependencies
- packaging
- signing configuration

Release build：

不得依赖：

- 本地绝对路径
- 开发机文件
- Debug-only dependency
- 测试服务
- 未提交资源

---

# 5. Static Analysis

Release 前执行：

- Android Lint
- Kotlin compiler checks
- Gradle checks
- unit tests

所有 blocker / fatal 问题：

必须解决。

Warnings：

必须评估并记录是否接受。

---

# 6. Dependency and Native Library Review

## 6.1 依赖

检查所有 dependencies：

- 是否仍然需要
- 是否兼容 Android 11+
- 是否存在已知严重问题
- 是否增加不必要的 APK 体积
- 是否增加明显内存负担
- 是否存在更轻量替代方案

删除未使用依赖。

## 6.2 原生库（阻塞项）

本项目包含 Opus（ADR-007）与可选的 Vosk（ADR-008）原生库。

必须验证：

- [ ] **所有 `.so` 为 16 KB page size 对齐**（Android 15+ 要求）
- [ ] ABI 清单符合预期（`arm64-v8a` + `armeabi-v7a`），无意外的多余 ABI
- [ ] 许可证与再分发条款已确认并记录
- [ ] 在 Android 11 与 Android 15/16 真机上均已实测加载成功

任何一项不通过：**阻塞发布**。

## 6.3 体积

记录并评估：

- APK / AAB 体积（分 ABI）
- 与上一版本的体积差异

ASR 模型不打进包内（ADR-006），若发现包内出现模型文件，视为打包配置错误。

# 7. Network Validation

验证：

## Discovery

至少两台设备：

同一 WiFi。

可以互相发现。

## Heartbeat

在线状态稳定。

离线状态可以正确检测。

## Endpoint

IP 改变：

Device ID 不变。

Peer endpoint 正确更新。

---

# 8. PTT Core Validation

验证：

- target selection
- PTT press
- PTT hold
- PTT release
- VOICE_START
- VOICE_DATA
- VOICE_END

必须：

一对一。

必须：

只有目标设备收到语音。

---

# 9. PTT Latency

测量「发言开始」到「接收端实际播放」之间的端到端延迟。

方法：发送固定测试音，两端同时录音，比对波形起点，**至少 30 次**。

## 判定标准

| 指标 | 目标 | 结果 |
|---|---|---|
| P50 | ≤ 250 ms | |
| P95 | ≤ 400 ms | |
| 最大值 | 记录 | |

超出目标：必须调查并给出结论（优化，或修订目标并说明理由）。

原文档只要求记录数值而没有阈值——那样的检查项无法判定通过或失败，现给出明确目标。

测量条件：同一 AP；低端设备与旗舰设备各测；两个方向各测。

# 10. PTT Audio Quality

测试：

- 安静环境
- 普通室内
- 背景噪声
- 轻声
- 正常说话
- 大声
- 快速讲话
- 短语音
- 长语音

确认：

- 无严重失真
- 无持续爆音
- 无明显卡顿
- 无持续断音
- 无严重延迟

---

# 11. Busy Mode

测试：

A → B。

B 正在通信。

C → B。

Expected：

C：

收到 BUSY。

A → B：

继续。

---

# 12. Force Interrupt

**这是接收方开关**（`allow_interrupt`，默认关闭）。

## 12.1 开启

**B 开启**「割り込みを許可」。

测试：A → B 通话中，C → B。

Expected：

- A 收到 `SESSION_TERMINATE`，会话结束
- A 的历史记录状态为 `INTERRUPTED`，录音可播放
- C 收到 `VOICE_ACCEPT`，建立新 SessionId
- 旧 Session 的后续包被丢弃

## 12.2 默认关闭

B 未开启。

Expected：行为与 Busy Mode 完全一致，C 收到 BUSY。

## 12.3 呼叫方无开关

Expected：呼叫方 UI 中**不存在**任何强插开关；没有设备能单方面获得打断全网通话的能力。

# 13. Force Interrupt Race

C / D：

同时强插 B。

Expected：

最终：

只有一个有效 Session。

不得：

多人同时播放。

---

# 14. Background Validation

测试：

- App foreground
- App background
- screen off
- device locked
- long idle

后台：

仍能接收 PTT。

---

# 15. Foreground Service

检查：

服务启动。

服务停止。

App 进入后台。

App 回到前台。

资源：

正确创建。

正确释放。

---

# 16. Notification

检查：

- notification exists
- localized
- low-frequency update
- no notification spam

不同 Android 版本：

行为正确。

---

# 17. Overlay

权限：

关闭。

Expected：

不能显示。

权限：

开启。

Expected：

绿色。

收到：

红色。

结束：

恢复绿色。

点击：

打开 App。

---

# 18. Network Recovery

测试：

1. WiFi off
2. WiFi on
3. IP change
4. router reconnect
5. airplane mode

Expected：

- no crash
- Peer state recovery
- discovery restart
- heartbeat restart

---

# 19. Device Identity

验证：

- First install → generate UUID
- App restart → same UUID
- device reboot → same UUID
- IP change → same UUID

---

# 20. Username

验证：

- first user creation
- multiple users
- switching
- editing
- deletion
- last-user protection

远程 Peer：

能够收到：

正确用户名。

---

# 21. Internationalization

必须验证：

- ja
- zh-CN
- en
- my
- bn

包括：

- Home
- PTT
- Settings
- History
- Overlay
- Notification
- Errors
- Permission guidance

检查：

- text overflow
- broken layout
- untranslated strings
- hard-coded strings

---

# 22. History

验证：

每次完成 PTT：

正确生成：

CommunicationRecord。

检查：

- ID
- SessionId
- sender
- receiver
- direction
- username
- timestamp
- duration
- audio path
- transcript state
- read
- favorite
- status

---

# 23. Recording

检查：

- file creation
- correct format
- duration
- path
- database relation

异常情况：

- storage full
- interrupted PTT
- network disconnect

不得：

导致 App Crash。

---

# 24. Playback

测试：

- Play
- Pause
- Resume
- Stop

文件不存在：

必须：

显示错误。

不得：

Crash。

---

# 25. ASR

## 25.1 默认状态

Expected：Disabled。

## 25.2 模型下载

首次开启 ASR。

Expected：

- 弹出确认框，说明需要约 50 MB 一次性下载
- 显示进度，可取消
- 完成后校验通过
- 下载失败或取消：开关保持 OFF，**不影响任何其它功能**，无残留半个文件

## 25.3 识别状态流转

模型就绪后完成 PTT。

Expected：`PENDING → PROCESSING → COMPLETED` 或 `FAILED`。

ASR 关闭时完成的 PTT：`NOT_REQUESTED`。

## 25.4 重试

Expected：最多 3 次，之后 `FAILED`；用户可手动重新识别。

# 26. ASR Offline

**前提**：模型已下载完成。

关闭互联网（移动数据关闭，AP 断开外网）。

Expected：

- 仍能保存录音
- 仍能识别并保存日语 transcript
- **无任何网络连接错误**

同时确认核心通信全部正常：Discovery、Heartbeat、PTT、录音、回放、历史。

这是产品定位的最终验证：除 ASR 模型的一次性下载外，任何功能都不依赖互联网。

# 27. ASR Failure

模拟：

- 模型加载失败
- 文件损坏
- 存储不足
- 识别失败

Expected：

PTT：

继续正常。

录音：

仍能播放。

---

# 28. History Search

测试：

用户名搜索。

Transcript 搜索。

空搜索。

特殊字符。

中文。

日语。

缅甸语。

孟加拉语。

---

# 29. Favorite

收藏：

不被普通 cleanup 删除。

取消收藏：

恢复普通生命周期。

---

# 30. Unread

新接收：

Unread。

打开：

Read。

状态：

重启后保持。

---

# 31. Cleanup

测试：

- 1 day
- 3 days
- 7 days
- 30 days
- Forever

确认：

数据库：

删除。

音频：

删除。

Orphan：

处理。

---

# 32. Storage Consistency

制造：

- database failure
- file deletion failure
- file creation failure

确认：

不会产生大量永久 orphan。

Cleanup 可以：

后续修复。

---

# 33. Low-End Device

至少测试：

MTK P22-class。

Android 11。

4 GB RAM。

后台运行：

长时间。

测试：

- discovery
- heartbeat
- PTT
- recording
- history
- overlay

---

# 34. Modern Device

至少测试：

现代旗舰 Android。

建议：

Samsung Galaxy flagship。

验证：

Android 13+

Android 14+

Android 15+

Android 16+

---

# 35. Long-Term Stability

建议：

8~24 小时。

后台。

期间：

周期性：

- discovery
- heartbeat
- PTT
- history
- ASR

Expected：

- no crash
- no ANR
- no uncontrolled memory growth
- no socket leak
- no audio resource leak

---

# 36. Memory

观察：

- Activity
- Service
- Socket
- AudioRecord
- AudioTrack
- Overlay
- ASR

重复：

foreground → background

多次。

Expected：

内存最终稳定。

---

# 37. CPU

测量：

- idle
- heartbeat
- discovery
- PTT
- ASR

后台空闲：

尽量接近最低水平。

不得：

长期高 CPU。

---

# 38. Battery

比较：

- App stopped
- App idle foreground
- App idle background
- PTT usage
- ASR enabled

记录：

单位时间电量消耗。

发现异常：

必须调查。

---

# 39. Network Traffic

测量：

Idle：

heartbeat。

Discovery：

发现流量。

PTT：

voice traffic。

确认：

待机不会出现：

持续高流量。

---

# 40. Permission

分别拒绝：

- microphone
- notification
- overlay

Expected：

不会 Crash。

UI：

给出本地化指导。

---

# 41. Lifecycle

测试：

- rotation
- background
- foreground
- process recreation
- app restart
- device reboot

核心服务：

不应因为 Activity 重建而意外停止。

---

# 42. Security Validation

测试：

非法 packet。

异常 Device ID。

错误 Target。

巨大 payload。

未知 PacketType。

重复 packet。

异常 sequence。

Expected：

安全丢弃。

不 Crash。

不产生无限资源消耗。

---

# 43. Release Logging

Release build：

必须确认：

DEBUG log：

关闭。

详细 packet logging：

关闭。

测试面板：

隐藏。

Mock：

移除。

Fake implementation：

移除。

Test button：

移除。

---

# 44. Privacy Review

确认没有：

- cloud audio
- cloud ASR
- cloud history
- unexpected telemetry
- analytics backend

录音、字幕、历史：**只保存在应用私有目录**。

## 44.1 唯一的联网路径

抓包确认：应用产生的网络流量只有两类：

1. 局域网内的 SaikaiPTT 协议包（广播发现、心跳、语音）
2. **ASR 模型的一次性下载**（仅在用户主动开启字幕功能时）

除此之外的任何外部连接：**阻塞发布**。

## 44.2 用户可见的说明

README 与应用内说明必须如实写明这一例外，不得笼统宣称「完全离线」。

# 45. File System Review

检查：

应用私有目录。

确认：

- 不写入不必要的公共目录
- 文件命名稳定
- 没有大量临时文件
- orphan cleanup 正常

---

# 46. Database Review

检查：

Room schema。

确认：

Migration：

正确。

禁止：

生产版本依赖 destructive migration。

---

# 47. Backup Review

确认：

Android 系统 backup 行为。

根据产品隐私要求：

决定是否排除：

- communication history
- recordings
- transcripts

---

# 48. Accessibility

检查：

- TalkBack
- content descriptions
- touch target size
- readable text
- non-color-only states

PTT：

必须可理解。

---

# 49. UI Review

确认：

- layout
- typography
- button state
- loading
- empty state
- error state
- busy
- receiving
- transmitting
- history
- settings

无明显：

- overflow
- clipping
- broken navigation

---

# 50. Production Environment

Release：

不得依赖：

开发电脑。

开发 WiFi。

开发 IP。

本地服务器。

测试数据库。

Mock network。

---

# 51. Reproducible Build

尽可能确保：

相同代码和配置：

可以稳定生成 Release APK。

记录：

- Gradle version
- Kotlin version
- Android Gradle Plugin
- compile SDK
- dependencies

---

# 52. README

Release 前：

README 必须包含：

- 项目介绍
- 核心功能
- Android 要求
- 安装方法
- 权限说明
- 使用方法
- 已知限制
- 开发方法

---

# 53. CHANGELOG

Release：

必须记录：

- 新功能
- 修复
- 兼容性变化
- 已知问题

格式建议：

```text
## x.x.x

### Added
### Changed
### Fixed
### Known Issues
```

---

# 54. Known Issues

任何已知：

- Crash
- ANR
- PTT failure
- background failure
- data loss
- serious audio issue

必须明确记录。

Release blocker：

不可隐藏。

---

# 55. Release Candidate Rule

RC 构建必须：

通过：

核心 PTT。

后台。

网络恢复。

录音。

播放。

历史。

ASR。

i18n。

低端设备。

旗舰设备。

---

# 56. Final Release Gate

以下任何一项失败，**不得正式发布**：

- One-to-one PTT（语音只到达目标设备）
- VOICE_ACCEPT 握手与按下即录
- Background Receive（含熄屏、锁屏）
- Network Recovery
- Busy
- **Force Interrupt（接收方开关）**
- **并发强插竞态：只有一个会话被接受**
- Recording（双向）
- Playback
- Data Integrity（含正在录制文件的保护、Room 失败隔离）
- Android 11 compatibility
- Android 14/15/16 前台服务行为
- **原生库 16 KB page 对齐**
- Low-end device stability
- **i18n（五种语言，含缅甸语 / 孟加拉语字体）**
- Release logging disabled，无 mock / fake / debug 面板
- 无阻塞性 Crash / ANR

修订说明：原 §56 的清单遗漏了 Force Interrupt 与 i18n，与 §55 的 RC 规则口径不一致，现已补齐。

ASR 不在阻塞项内——它是默认关闭的可选功能。但 ASR 若已启用而导致 PTT 不稳定，则属于 PTT 阻塞项。

# 57. Final Sign-Off

Release 前必须明确记录：

## Build

Build successful.

## Tests

All required tests passed.

## Devices

Tested devices listed.

## Known Issues

Documented.

## Performance

Measured.

## Privacy

Reviewed.

## Release Logging

Disabled.

## Version

Confirmed.

---

# 58. Definition of Release Ready

只有同时满足：

```text
Build
+
Tests
+
Real Devices
+
Network
+
Audio
+
Background
+
Storage
+
ASR
+
i18n
+
Performance
+
Privacy
```

才能标记：

```text
RELEASE READY
```

---

# 59. Final Principle

SaikaiPTT 的正式发布标准不是：

> “大部分功能都能用。”

而是：

> “核心通信在真实 Android 设备和真实 WiFi 环境中可靠工作，并且异常情况下不会轻易崩溃或破坏用户数据。”

稳定性永远优先于发布日期。
