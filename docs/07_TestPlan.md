# SaikaiPTT Test Plan

## 1. Purpose

本文档定义 SaikaiPTT 的测试策略、测试层级、测试环境、测试用例、性能测试、兼容性测试、网络异常测试、后台测试、音频测试、存储测试、ASR 测试以及 Release Candidate 验收标准。

测试目标：

> 证明 SaikaiPTT 在 Android 11+、低端设备、现代旗舰设备以及典型 WiFi 局域网环境下，能够稳定、安全、低功耗地完成一对一 PTT 通信。

测试必须同时关注：

- 正确性
- 稳定性
- 实时性
- 生命周期
- 网络异常
- 低端设备性能
- 数据一致性
- 后台运行
- 国际化
- 隐私

---

# 2. Testing Principles

测试优先级：

1. PTT 核心通信
2. 后台接收
3. 网络恢复
4. Busy / Force Interrupt
5. 录音与播放
6. 数据一致性
7. 设备兼容性
8. 性能和功耗
9. ASR
10. UI 细节

核心功能存在阻塞问题时：

不得继续进行功能扩展。

---

# 3. Test Layers

测试分为：

```text
Static Analysis
↓
Unit Tests
↓
Integration Tests
↓
Component Tests
↓
Real Device Tests
↓
Long-running Tests
↓
Performance Tests
↓
Release Validation
```

---

# 4. Static Analysis

每个实现阶段至少执行：

- Gradle build
- Kotlin compilation
- Android Lint
- relevant unit tests

Release 前：

必须确保没有 blocker severity 问题。

---

# 5. Unit Test Scope

重点测试：

- Device ID
- LocalUser
- DataStore
- Protocol
- Packet validation
- Sequence
- Session state machine
- Busy logic
- Force Interrupt
- History rules
- Cleanup logic
- ASR state transitions

---

# 6. Device Identity Tests

## TC-ID-001

首次启动：

生成 Device ID。

Expected：

Device ID 非空。

格式：

有效 UUID v4。

---

## TC-ID-002

App 重启：

Device ID 不变。

---

## TC-ID-003

手机重启：

Device ID 不变。

---

## TC-ID-004

WiFi IP 改变：

Device ID 不变。

---

## TC-ID-005

删除 App 数据：

重新初始化后可以生成新的 Device ID。

---

# 7. Username Tests

## TC-USER-001

创建第一个用户名。

Expected：

成功。

自动成为 Active User。

---

## TC-USER-002

创建多个用户名。

Expected：

全部保存。

---

## TC-USER-003

切换用户名。

Expected：

Active User 正确变化。

Device ID 不变化。

---

## TC-USER-004

修改用户名。

Expected：

远程通信使用新名称。

---

## TC-USER-005

删除非当前用户名。

Expected：

成功。

---

## TC-USER-006

尝试删除唯一用户名。

Expected：

拒绝。

---

## TC-USER-007

删除当前用户名。

Expected：

要求先切换其它用户，或者拒绝操作。

---

# 8. Internationalization Tests

必须测试：

- ja
- zh-CN
- en
- my
- bn

检查：

- Home
- PTT
- Settings
- History
- Overlay
- Notification
- Errors
- Empty State
- Busy
- Force Interrupt
- Permissions

不得出现：

- missing translation
- hard-coded English
- hard-coded Chinese
- 截断
- layout overflow

---

# 9. Discovery Tests

至少两台设备。

## TC-DISC-001

同一 WiFi：

A 启动。

B 启动。

Expected：

互相发现。

---

## TC-DISC-002

第三台设备加入。

Expected：

自动出现。

---

## TC-DISC-003

设备离线。

Expected：

经过 heartbeat timeout 后：

变为 Offline。

---

## TC-DISC-004

设备重新上线。

Expected：

自动恢复 Online。

---

## TC-DISC-005

IP 变化。

Expected：

原 Device ID 不变。

Peer endpoint 更新。

---

## TC-DISC-006

重复 Discovery。

Expected：

不会产生重复 Peer。

---

# 10. Heartbeat Tests

## TC-HB-001

正常 heartbeat。

Expected：

Peer 保持 Online。

---

## TC-HB-002

连续 heartbeat 丢失。

Expected：

Peer 经过 timeout 后 Offline。

---

## TC-HB-003

单次 heartbeat 丢失。

Expected：

不会立即 Offline。

---

## TC-HB-004

网络恢复。

Expected：

Heartbeat 自动恢复。

---

# 11. Protocol Tests

测试：

- valid packet
- invalid packet
- wrong version
- wrong length
- wrong target
- invalid Device ID
- oversized payload
- unknown packet
- malformed packet

Expected：

任何非法 Packet：

不得 Crash。

---

# 12. Sequence Tests

## TC-SEQ-001

正常：

1
2
3
4

Expected：

顺序处理。

---

## TC-SEQ-002

乱序：

1
3
2

Expected：

由 jitter buffer 处理。

---

## TC-SEQ-003

重复：

1
2
2
3

Expected：

重复 Packet 不重复播放。

---

## TC-SEQ-004

丢包：

1
2
4
5

Expected：

继续播放。

不得无限等待 3。

---

## TC-SEQ-005

Session mismatch。

Expected：

Packet 被丢弃。

---

# 13. PTT Sender Tests

## TC-PTT-S-001

选择在线目标。

按住：

开始发送。

---

## TC-PTT-S-002

松开：

发送结束。

---

## TC-PTT-S-003

没有 Active User。

Expected：

禁止发送。

---

## TC-PTT-S-004

没有 Target。

Expected：

禁止发送。

---

## TC-PTT-S-005

Target Offline。

Expected：

禁止发送。

---

## TC-PTT-S-006

Target Busy。

Expected：

收到 BUSY。

---

# 14. PTT Receiver Tests

## TC-PTT-R-001

收到 VOICE_START。

Expected：

自动进入 Receiving。

---

## TC-PTT-R-002

收到 VOICE_DATA。

Expected：

自动播放。

---

## TC-PTT-R-003

收到 VOICE_END。

Expected：

结束 Session。

---

## TC-PTT-R-004

没有用户操作。

Expected：

仍能自动收到。

---

# 15. One-to-One Tests

最重要的测试。

设备：

A

B

C

A → B。

C 不允许收到声音。

Expected：

只有 B 播放。

---

# 16. Target Validation

A → B。

如果 C 收到相同 UDP packet：

C 必须因为：

TargetDeviceId != C

而丢弃。

---

# 17. Busy Tests

A → B。

B 正在接收。

C → B。

Expected：

B 不打断 A。

C 收到 BUSY。

---

# 18. Force Interrupt Tests

开启 Force Interrupt。

A → B。

C → B。

Expected：

A → B Session 结束。

C → B Session 建立。

---

# 19. Force Interrupt Race Tests

C 和 D：

同时向 B 发起 Force Interrupt。

Expected：

只有一个请求获得 Session。

另一个：

BUSY / rejected。

不能同时播放两个人的声音。

---

# 20. Network Recovery Tests

测试：

- WiFi off
- WiFi on
- airplane mode
- access point reconnect
- IP address change

Expected：

服务不 Crash。

Peer 自动恢复。

---

# 21. Background Tests

测试：

- App foreground
- App background
- screen off
- device locked
- long idle

接收端：

后台运行。

发送端：

发送 PTT。

Expected：

接收端能够收到。

---

# 22. Foreground Service Tests

测试：

启动服务。

停止服务。

App UI 退出。

Expected：

核心后台状态符合设计。

停止服务后：

资源全部释放。

---

# 23. Notification Tests

检查：

- notification appears
- localized text
- no spam
- service remains active

不同 Android 版本：

验证行为。

---

# 24. Overlay Tests

测试：

权限关闭：

Overlay 不显示。

权限开启：

Overlay 显示绿色。

收到 PTT：

Overlay 红色。

通信结束：

恢复绿色。

点击：

正确进入 App。

---

# 25. Audio Tests

真实设备测试：

- microphone unavailable
- microphone permission denied
- AudioRecord initialization failure
- AudioTrack failure
- audio focus changes
- headset connected/disconnected
- speaker output

Expected：

不 Crash。

异常能够恢复。

---

# 26. Audio Latency Tests

至少测量：

发言开始：

到：

接收端听到：

的端到端延迟。

目标：

尽可能低。

建议记录：

P50

P95

最大值。

不要只测试最好的一次。

---

# 27. Audio Quality Tests

测试：

- normal speech
- quiet speech
- loud speech
- rapid speech
- short speech
- long speech
- background noise

检查：

- intelligibility
- clipping
- gaps
- distortion

---

# 28. Recording Tests

每次 PTT：

Expected：

生成录音。

检查：

- audio file exists
- duration correct
- format correct
- path valid
- database reference valid

---

# 29. Playback Tests

测试：

- play
- pause
- resume
- stop
- incomplete file
- deleted file

如果文件不存在：

UI 应显示合理错误。

不得 Crash。

---

# 30. History Tests

验证：

- send record
- receive record
- timestamp
- duration
- remote username
- Device ID
- session ID
- audio path
- transcript state
- read state
- favorite state

---

# 31. History Consistency

场景：

录音成功。

Room 写入失败。

Expected：

不会产生“成功历史 + 不存在音频”的错误记录。

同时：

后续 cleanup 可以处理 orphan file。

---

# 32. Cleanup Tests

测试：

1 day
3 days
7 days
30 days
forever

验证：

过期记录删除。

未过期保留。

---

# 33. Favorite Protection

Favorite = true。

自动 cleanup：

Expected：

不会删除。

用户明确全部删除：

根据 UI confirmation 再删除。

---

# 34. Unread Tests

新接收：

Unread。

打开：

Read。

历史列表：

状态正确。

---

# 35. Search Tests

搜索：

用户名。

搜索：

日语 transcript。

Expected：

只返回匹配项。

空搜索：

显示全部历史。

---

# 36. ASR Tests

ASR 默认：

Disabled。

启用：

PTT 完成后进入：

PENDING。

然后：

PROCESSING。

最终：

COMPLETED / FAILED。

---

# 37. ASR Offline Test

测试期间：

禁用互联网。

Expected：

仍然能够：

- 保存录音
- 处理 ASR
- 生成日语 transcript

不得出现：

网络连接错误。

---

# 38. ASR Failure Tests

模拟：

模型加载失败。

磁盘不足。

音频格式异常。

识别失败。

Expected：

PTT 不受影响。

历史记录保留。

录音仍可播放。

状态变为：

FAILED。

---

# 39. ASR Resource Tests

在低端设备：

启用 ASR。

测量：

- CPU
- memory
- processing time
- battery

Expected：

ASR 不影响：

实时 PTT。

如果资源不足：

PTT 优先。

---

# 40. Low-End Device Tests

参考设备：

MTK P22-class

Android 11

4 GB RAM

运行：

至少：

2 小时。

测试期间：

- App 后台
- heartbeat 正常
- 偶尔进行 PTT
- 屏幕关闭

记录：

- CPU
- memory
- battery
- crashes
- ANR

---

# 41. Modern Device Tests

现代旗舰 Android：

验证：

- Android 13+
- Android 14+
- Android 15+
- Android 16+

至少一台：

Samsung Galaxy 旗舰设备。

---

# 42. Long-Running Stability Test

最低建议：

8~24 小时。

后台持续运行。

有周期性：

Discovery

Heartbeat

PTT

history

ASR

Expected：

- 无 crash
- 无 ANR
- 无无限内存增长
- 无 socket 泄漏
- 无 audio resource 泄漏

---

# 43. Memory Leak Tests

重点观察：

- Activity
- Service
- AudioRecord
- AudioTrack
- DatagramSocket
- Overlay
- Coroutine

重复：

启动 → 后台 → 前台 → 再后台。

多次循环。

Expected：

内存最终稳定。

---

# 44. Battery Tests

重点比较：

1. App 未运行
2. App 运行但无通信
3. App 后台运行
4. PTT 使用
5. ASR 开启

记录：

battery drain。

目标：

后台空闲消耗尽量接近最低。

---

# 45. CPU Tests

测量：

Idle。

Heartbeat。

Discovery。

PTT。

ASR。

要求：

后台空闲时：

尽量接近 0。

不应出现：

长期高 CPU。

---

# 46. Network Traffic Tests

测量：

Idle：

heartbeat traffic。

Discovery：

broadcast/mDNS traffic。

PTT：

voice traffic。

Expected：

待机网络包数量合理。

不能持续高速发送数据。

---

# 47. Permission Tests

分别拒绝：

- microphone
- notification
- overlay

Expected：

应用：

不 Crash。

UI：

明确告诉用户如何恢复。

---

# 48. Android Lifecycle Tests

测试：

- rotation
- background
- foreground
- process recreation
- app restart
- device reboot
- permission changes

Expected：

状态正确恢复。

---

# 49. Negative Tests

主动制造：

- malformed UDP
- wrong target
- wrong sender
- oversized packet
- unknown packet
- duplicate packet
- invalid sequence
- broken audio

Expected：

App：

继续正常运行。

---

# 50. Compatibility Tests

至少验证：

Android 11

Android 12

Android 13

Android 14

Android 15

Android 16

如果特定版本存在：

行为差异。

必须记录。

---

# 51. Locale Tests

每种语言至少测试：

- Home
- Settings
- PTT
- History
- Permission
- Notification
- Overlay
- error

特别注意：

缅甸语和孟加拉语：

字体显示。

换行。

布局。

---

# 52. UI Test Categories

测试：

- button states
- list states
- loading
- empty state
- error state
- busy
- receiving
- transmitting
- history
- search
- settings

---

# 53. Release Build Tests

Debug Build：

日志开启。

Release Build：

Debug 日志关闭。

Release：

不得出现：

- Debug panel
- test buttons
- mock data
- fake implementation

---

# 54. Security Validation

验证：

恶意/异常设备发送：

大量：

INVALID packet。

Expected：

App：

CPU 不会无限增长。

Memory 不会无限增长。

日志不会无限刷屏。

---

# 55. Crash and ANR

Release Candidate 前：

必须检查：

- crash logs
- ANR
- fatal exceptions
- uncaught coroutine exceptions

任何核心通信路径 Crash：

必须阻塞 Release。

---

# 56. Acceptance Metrics

这些是目标而不是绝对硬编码的数字。

## Idle

CPU：

尽可能 <1%。

## PTT

CPU：

目标 <10%。

## Memory

普通运行：

尽量保持低内存。

约 30 MB 是优化参考值，不包括：

- Android Runtime
- 大型第三方运行时
- ASR 模型

实际必须以真实设备 profiling 为准。

---

# 57. End-to-End Test

完整流程：

```text
Device A
↓
create user
↓
discover B
↓
select B
↓
PTT
↓
B receives
↓
B recording
↓
history
↓
ASR
↓
transcript
↓
playback
↓
cleanup
```

完整跑通。

---

# 58. Two-Device Acceptance

设备：

低端 A

旗舰 B

测试：

A → B

B → A

后台：

A → B

B → A

Busy：

A → B

C → B

Force Interrupt：

C → B

WiFi：

断开 → 恢复。

必须全部通过。

---

# 59. Final Regression

任何新的代码修改：

必须至少运行：

- compile
- affected unit tests
- affected integration tests
- core PTT regression

核心功能：

必须持续回归。

---

# 60. Test Report

每个阶段完成后：

Claude 应报告：

## Environment

设备 / Android / build。

## Tests

测试内容。

## Result

Pass / Fail。

## Failed Tests

失败项目。

## Known Issues

已知问题。

## Performance

如果相关：

CPU / memory / latency。

## Conclusion

是否可以进入下一 Task。

---

# 61. Release Candidate Checklist

Release Candidate 必须满足：

- build success
- lint clean / accepted findings
- tests pass
- two-device PTT pass
- background receive pass
- reconnect pass
- busy pass
- force interrupt pass
- recording pass
- playback pass
- ASR pass
- i18n pass
- low-end device pass
- modern device pass
- no blocking crash
- no blocking ANR
- release logging disabled
- no test/mock code

---

# 62. Test Priority Rule

如果测试发现：

PTT：

不稳定。

后台：

收不到。

网络：

无法恢复。

即使：

UI 已经完成。

也不能进入下一阶段。

核心通信优先。

---

# 63. Test Completion Principle

不要通过：

“代码看起来没问题”

作为测试结论。

必须尽可能：

- 编译
- 自动测试
- 真实设备测试
- 长时间测试
- 性能测试

再得出结论。

---

# 64. Final Testing Principle

SaikaiPTT 的测试目标不是：

> 证明代码可以运行。

而是：

> 证明 SaikaiPTT 在真实 Android 设备、真实 WiFi、后台运行和网络异常情况下，仍然可以像一台可靠的局域网数字对讲机一样工作。
