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

- Device ID 生成与持久化
- LocalUser CRUD、Active User 自愈、名称长度校验（码位 + 字节双重上限）
- DataStore 默认值与**损坏值安全降级**
- Protocol encode / decode
- **协议头部逐字节布局断言**（固定字节数组比对，验证偏移与大端序）
- Packet validation 的 12 步顺序
- **Sequence 32-bit 回绕比较**
- Session state machine（合法与非法转换）
- **VOICE_ACCEPT 握手与超时重发**
- **并发 VOICE_START 的会话所有权唯一性**
- Busy logic
- Force Interrupt（接收方策略）
- Jitter buffer 排序、去重、丢帧、越界丢弃
- History rules
- Cleanup logic（含收藏保护、正在录制文件保护）
- ASR state transitions（五态）

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

必须测试：`ja` / `zh-CN` / `en` / `my` / `bn`

检查页面：Home、PTT、Settings、History、Overlay、Notification、Errors、Empty State、Busy、权限引导

不得出现：missing translation、hard-coded English、hard-coded Chinese、截断、layout overflow

## TC-I18N-001 语言切换机制

切换语言。

Expected：立即生效，无需手动重启。

## TC-I18N-002 切换后持久化

切换语言后重启 App。

Expected：仍为所选语言。

## TC-I18N-003 应用外组件同步

切换语言后，检查 **Foreground Service 通知** 与 **悬浮窗** 文本。

Expected：同步切换（这两者不走 Activity 的 Configuration，必须从 DataStore 的 `app_language` 取值）。

## TC-I18N-004 系统级语言设置（Android 13+）

在系统设置 → 应用 → SaikaiPTT → 语言 中修改。

Expected：应用内语言同步变化。

## TC-I18N-005 字体渲染

在**低端 Android 11 参考设备**上检查缅甸语与孟加拉语。

Expected：无缺字、无豆腐块、无 Zawgyi 错乱、无换行异常。

这是已知的高风险项，必须实测而非目视代码。

# 9. Discovery Tests

发现机制为**纯 UDP 广播**（ADR-001），至少两台设备。

## TC-DISC-001
同一 WiFi，A 启动，B 启动。Expected：互相发现。

## TC-DISC-002
第三台设备加入。Expected：自动出现（收到其 DISCOVERY 后立即单播应答）。

## TC-DISC-003
设备离线。Expected：经过 `peerTimeoutMs`（16 秒）后变为 Offline。

## TC-DISC-004
设备重新上线。Expected：收到任意有效包即刻恢复 Online。

## TC-DISC-005
IP 变化。Expected：Device ID 不变，Peer endpoint 更新，不产生新 Peer。

## TC-DISC-006
重复 Discovery。Expected：不产生重复 Peer。

## TC-DISC-007 回环过滤
Expected：设备不把自己的广播当作一个 Peer。

## TC-DISC-008 改名传播
A 切换 Active User。Expected：B 在下一次广播内看到新名称，Device ID 不变。

## TC-DISC-009 熄屏接收
B 熄屏、锁屏。A 启动。Expected：B 仍能收到广播并发现 A（验证 `MulticastLock` 生效）。

## TC-DISC-010 已知限制
在开启 AP Isolation 的 AP 上测试。Expected：无法发现——此结果符合预期，须与 README 的已知限制一致，不视为缺陷。

# 10. Heartbeat and Presence Tests

## TC-HB-001
正常心跳。Expected：Peer 保持 Online。

## TC-HB-002
连续错过 3 个周期。Expected：Peer 变为 Offline。

## TC-HB-003
单次心跳丢失。Expected：**不会**立即 Offline。

## TC-HB-004
网络恢复。Expected：心跳自动恢复。

## TC-HB-005 忙线状态通告
A 与 B 开始通话。Expected：C 在 **1 秒内**看到 B 显示「通話中」（依赖状态变化时的立即广播，而非等待 5 秒周期）。

## TC-HB-006 忙线状态解除
通话结束。Expected：C 在 1 秒内看到 B 恢复空闲。

## TC-HB-007 广播而非单播
4 台设备同时在线，抓包统计。Expected：每台设备每周期只发 **1 个**心跳包，而不是 3 个。

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
正常 1 2 3 4。Expected：顺序处理。

## TC-SEQ-002
乱序 1 3 2。Expected：由 jitter buffer 重排后正确播放。

## TC-SEQ-003
重复 1 2 2 3。Expected：重复包不重复播放。

## TC-SEQ-004
丢包 1 2 4 5。Expected：继续播放，不无限等待 3。

## TC-SEQ-005
Session mismatch。Expected：包被丢弃。

## TC-SEQ-006 Sender mismatch
同一 SessionId 下发送方变化。Expected：拒绝，防止第三方注入语音。

## TC-SEQ-007 回绕比较（单元测试）
`0xFFFFFFFE → 0xFFFFFFFF → 0x00000000 → 0x00000001`

Expected：`isNewer` 判定正确，播放顺序正确，不出现整段音频被误判为「过期」而丢弃。

这是长时间运行才会触发的缺陷，必须用单元测试覆盖，不能只依赖真机测试。

## TC-SEQ-008 VOICE_END 序号
Expected：VOICE_END 的 sequence == 最后一个 VOICE_DATA + 1，且与 payload 中的 `finalDataSequence` 一致。

# 13. PTT Sender Tests

## TC-PTT-S-001
选择在线目标，按住：开始发送。

## TC-PTT-S-002
松开：发送结束，生成历史记录。

## TC-PTT-S-003
没有 Active User。Expected：禁止发送。

## TC-PTT-S-004
没有 Target。Expected：禁止发送。

## TC-PTT-S-005
Target Offline。Expected：禁止发送或提示对方不在线。

## TC-PTT-S-006
Target Busy。Expected：收到 BUSY，不生成历史记录。

## TC-PTT-S-007 握手
Expected：`REQUESTING → TRANSMITTING` 由 `VOICE_ACCEPT` 触发，而不是超时后盲发。

## TC-PTT-S-008 VOICE_START 重发
人为丢弃第一个 VOICE_START。Expected：150ms 后重发，最多 2 次，仍无应答则 500ms 放弃。

## TC-PTT-S-009 按下即录（首音节不丢失）
按下按钮的**同时**立即说话（不等待任何提示）。

Expected：接收端能听到完整的第一个音节。

验证方式：发送固定测试音（如 1kHz 短促音），比对接收端波形起点。

## TC-PTT-S-010 通话中切换目标
正在 PTT 时尝试切换 Target。Expected：被拒绝，必须先结束当前会话。

## TC-PTT-S-011 通话中切换用户名
正在 PTT 时尝试切换 Active User。Expected：被拒绝并提示通话结束后再切换。

## TC-PTT-S-012 会话中途改名
会话进行中修改本地用户名。Expected：本次会话继续使用原名称，下一次 PTT 使用新名称。

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

`allow_interrupt = false`（默认）。

A → B 通话中，C → B。

Expected：

- B 不打断 A
- C 收到 BUSY
- **C 端不生成历史记录**
- A 与 B 的会话完全不受影响

## TC-BUSY-001 呼叫方不做本地拒绝
让 C 的 Peer 列表显示 B 为空闲（利用心跳滞后），此时 C 呼叫正在通话的 B。

Expected：C 实际发出 VOICE_START 并收到 BUSY，而不是在本地直接拒绝。

## TC-BUSY-002 无应答
目标设备断电。

Expected：C 在 500ms 后提示无响应，不生成历史记录。

# 18. Force Interrupt Tests

**Force Interrupt 是接收方开关。**

## TC-FI-001
**B 开启**「允许被打断」。A → B 通话中，C → B。

Expected：

- A 收到 `SESSION_TERMINATE`
- A 的会话结束，已采集音频保存，记录状态 `INTERRUPTED`
- C 收到 `VOICE_ACCEPT`，建立新 Session（新 SessionId）
- 属于旧 Session 的后续包被丢弃

## TC-FI-002 默认关闭
B 未开启该设置。

Expected：行为与 Busy Mode 完全一致，C 收到 BUSY。

## TC-FI-003 呼叫方无开关
检查 C 的全部 UI。

Expected：呼叫方**没有**任何强插相关的开关或界面；C 无法单方面获得打断能力。

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

测试：App foreground / background / screen off / device locked / long idle

## TC-BG-001 后台接收
接收端后台运行，发送端发起 PTT。

Expected：接收端收到并自动播放。

## TC-BG-002 熄屏接收
接收端熄屏。

Expected：仍能收到（验证 `MulticastLock` 与前台服务生效）。

## TC-BG-003 锁屏接收
Expected：同上。

## TC-BG-004 长时间空闲后接收
后台空闲 30 分钟后发起 PTT。

Expected：仍能收到（验证 Doze 下前台服务的网络豁免）。

## TC-BG-005 发送要求界面可见
App 在后台，尝试通过悬浮窗直接发起发送。

Expected：先拉起 Activity，界面可见后才允许按 PTT。

这是 Android 14+ 的平台规则（麦克风类型 FGS 不能从后台提升），**不是缺陷**。必须验证提示清晰、路径顺畅，用户感知上仍是「点一下就能回话」。

## TC-BG-006 无悬浮窗权限的降级
关闭悬浮窗权限，App 在后台接收。

Expected：Foreground Service 通知内容更新为「受信中：<名前>」，通信结束后恢复常态，且每次会话最多更新 2 次。

## TC-BG-007 开机自启
设备重启后不打开应用。

Expected：服务以 `connectedDevice` 类型启动，**可接收但不可发送**；打开应用后恢复完整能力。

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

测量「发言开始」到「接收端实际出声」的端到端延迟。

## 测量方法

发送端播放固定测试音（如 1 kHz 短脉冲），同时录制两端音频，比对波形起点。

**至少测量 30 次**，记录 P50 / P95 / 最大值。不要只测最好的一次。

## 判定标准

| 指标 | 目标 | 判定 |
|---|---|---|
| P50 | ≤ 250 ms | 超出需调查 |
| P95 | ≤ 400 ms | 超出需调查 |
| 最大值 | — | 记录，异常尖峰必须定位原因 |

原文档只要求「尽可能低」并记录数值，没有阈值——那样的验收项无法判定通过或失败。现给出明确目标值。

## 测量条件

- 同一 AP，无明显干扰
- 分别在低端设备与旗舰设备上测量
- 分别测量 低端→旗舰、旗舰→低端 两个方向

## 延迟构成参考

```text
采集缓冲(20ms) + 编码 + 网络 + jitter buffer 起播(60ms) + 解码 + 播放缓冲
```

若实测显著超标，优先检查 jitter buffer 深度与 AudioTrack buffer 大小。

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

验证每条记录的字段（以 `05_DataModel §10` 为准）：

id / sessionId / senderDeviceId / receiverDeviceId / remoteDeviceId / localUserId / remoteUserName / direction / timestamp / durationMs / audioPath / audioFormat / transcript / transcriptStatus / isRead / isFavorite / status / createdAt / updatedAt

## TC-HIST-001 双向记录
一次 A → B 的 PTT。

Expected：A 生成 direction = SEND 的记录，B 生成 direction = RECEIVE 的记录，两端各自独立。

## TC-HIST-002 用户名快照
通信后远程用户改名。

Expected：历史记录仍显示通信发生时的名称。

## TC-HIST-003 请求失败不生成记录
被 BUSY 拒绝、以及 500ms 无应答。

Expected：**两种情况都不生成任何历史记录**。

## TC-HIST-004 中断记录
发送过程中对方设备断电 / WiFi 断开。

Expected：

- 立即结束通信
- **已采集的有效部分被保存**
- 记录状态为 `INTERRUPTED`
- 录音可播放
- 不 Crash

## TC-HIST-005 强插中断记录
被强插打断的一方。Expected：记录状态 `INTERRUPTED`，录音可播放。

## TC-HIST-006 会话超时
收到 VOICE_START 后对端失联。Expected：`sessionIdleTimeoutMs` 后结束，记录 `INTERRUPTED`（**不引入 TIMEOUT 状态**）。

## TC-HIST-007 默认字幕状态
ASR 关闭时完成 PTT。Expected：`transcriptStatus = NOT_REQUESTED`，详情页不显示字幕区域。

# 31. History and Storage Consistency

## TC-CONS-001 Room 写入失败
录音成功，人为让 Room insert 失败。

Expected：

- 不产生「成功历史 + 不存在音频」的错误记录
- 音频文件被标记为 orphan candidate
- 后续 cleanup 可以处理
- 不 Crash

## TC-CONS-002 录音保存失败
人为制造磁盘写入失败。

Expected：

- **实时 PTT 继续进行不中断**
- 记录 `status = FAILED`，`audioPath = null`
- UI 显示存储错误
- 不 Crash

## TC-CONS-003 磁盘写满
把存储填至接近写满，然后进行 PTT。

Expected：同 TC-CONS-002。语音通信优先于录音。

## TC-CONS-004 录音写盘不阻塞实时路径
人为让文件 I/O 变慢（大量并发写入）。

Expected：有界队列满后丢弃录音帧并记 WARN，**语音发送与播放的延迟不受影响**（对比 TC-26 的延迟数据）。

## TC-CONS-005 正在录制的文件受保护
PTT 进行中触发一次自动清理。

Expected：`records/.tmp/` 下正在写入的文件**不被删除**，本次通信正常完成。

## TC-CONS-006 正在播放的文件受保护
播放某条历史录音时触发清理，且该记录已过期。

Expected：播放不中断或有明确处理，不出现「文件被删导致播放崩溃」。

## TC-CONS-007 History 故障隔离
人为让 Room 完全不可用。

Expected：Discovery、Heartbeat、Voice **全部继续正常工作**（`05_DataModel §48`）。

# 32. Cleanup Tests

测试保留期：1 day / 3 days / 7 days / 30 days / forever

验证：过期记录被删除，未过期保留。

## TC-CLEAN-001 音频同步删除
Expected：数据库记录与对应音频文件**同时**被删除，不留孤立文件。

## TC-CLEAN-002 收藏保护
`isFavorite = true` 的过期记录。Expected：**不被自动清理删除**。

## TC-CLEAN-003 单个文件删除失败
人为让某个音频文件删除失败。

Expected：记录日志并**继续处理其余记录**，不中止整个清理任务；该记录进入待清理状态供下次重试。

## TC-CLEAN-004 孤立文件扫描
人为制造数据库无引用的音频文件。

Expected：被识别并清理，但**不误删正在创建、正在播放、正在进行 PTT 涉及的文件**。

## TC-CLEAN-005 手动删除单条
Expected：二次确认后，记录与音频同时删除。

## TC-CLEAN-006 批量删除
Expected：明确确认；收藏记录默认保留；选择连同收藏删除时需要单独的二次确认。

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

ASR 默认 **Disabled**。

## TC-ASR-001 首次开启与模型下载
在设置中首次开启 ASR。

Expected：

- 弹出确认框，说明需要约 50 MB 下载
- 显示下载进度，可取消
- 下载完成后校验通过，`asr_model_ready = true`

## TC-ASR-002 下载失败 / 取消
中断网络或取消下载。

Expected：开关保持 OFF，**PTT、录音、历史完全不受影响**，无残留的半个模型文件。

## TC-ASR-003 状态流转
模型就绪后完成一次 PTT。

Expected：`PENDING → PROCESSING → COMPLETED`（或 `FAILED`）。

## TC-ASR-004 关闭时的状态
ASR 关闭时完成 PTT。Expected：`transcriptStatus = NOT_REQUESTED`。

## TC-ASR-005 重试上限
人为让识别失败。Expected：最多重试 3 次后置 `FAILED`，不无限重试。

## TC-ASR-006 手动重新识别
在 History Detail 点击重新识别。Expected：重新入队，状态回到 `PROCESSING`。

# 37. ASR Offline Test

**前提**：模型已下载完成（TC-ASR-001）。

测试期间**完全禁用互联网**（关闭移动数据，AP 断开外网）。

Expected：

- 仍能保存录音
- 仍能完成 ASR 处理
- 生成日语 transcript
- **不出现任何网络连接错误**

同时验证核心通信：

- Discovery、Heartbeat、PTT、录音、回放、历史**全部正常**

这是产品定位的核心验证：除 ASR 模型的一次性下载外，任何功能都不依赖互联网。

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

- 屏幕旋转
- 前后台切换
- 进程重建
- App 重启
- 设备重启
- 权限运行时变更

## TC-LC-001 旋转
PTT 进行中旋转屏幕。Expected：PTT 状态不丢失，网络服务不停止，当前用户不消失。

## TC-LC-002 返回键
从 PTT 页返回。Expected：只退出 UI，**不停止 Foreground Service**。

## TC-LC-003 进程被杀
用系统设置强制停止应用，然后重新打开。

Expected：

- DataStore / Room / 音频文件**未损坏**
- Device ID、用户名、设置、历史全部恢复
- 重新 Discovery
- **不承诺**自动恢复后台通信（Android 12+ 禁止后台启动 FGS），此为预期行为

## TC-LC-004 设备重启
Expected：服务以可接收状态启动；Device ID 与用户名不变。

## TC-LC-005 运行时撤销权限
在系统设置中撤销麦克风权限后回到应用。

Expected：不 Crash；发送被禁用并给出可操作提示；**接收仍然正常**。

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

# 52. UI and Accessibility Tests

## UI 状态

测试：button states / list states / loading / empty state / error state / busy / receiving / transmitting / history / search / settings

## TC-UI-001 自动跳转边界
在**用户名编辑页**（有未保存输入）时收到 PTT。

Expected：**不强制跳转**，顶部显示可点击状态条，语音照常播放，输入不丢失。

在 Home 页收到 PTT。Expected：自动切换到通信界面。

## TC-UI-002 忙线设备可点选
Peer 列表中显示「通話中」的设备。

Expected：仍可点选，PTT 按钮不被禁用（最终判定权在被叫方）。

## TC-UI-003 主页重组
持续接收心跳 5 分钟。

Expected：心跳更新**不导致整页重组**（用重组计数验证）。

## 无障碍

## TC-A11Y-001 TalkBack
开启 TalkBack 遍历 Home、PTT、History、Settings。

Expected：所有交互元素有内容描述；PTT 按钮的 label 明确（如「Push To Talk，目标：山田」）。

## TC-A11Y-002 非颜色唯一状态
用灰度模式（或色觉模拟）查看设备列表与未读标记。

Expected：在线 / 忙线 / 离线、已读 / 未读**均可分辨**（图标 + 文本）。

## TC-A11Y-003 触控区域与文本
Expected：触控目标不小于 48dp；开启系统大字体后无截断与布局破损。

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

以下为**可判定的目标值**，在 MTK P22 类参考设备上测量，全部按**单核占用百分比**计。

| 指标 | 目标 | 测量条件 |
|---|---|---|
| 待机 CPU | < 1% of one core | 服务运行、熄屏、无通信，10 分钟均值 |
| 发送 CPU | ≤ 15% of one core | 采集 + Opus 编码 + UDP 发送 + 录音写盘 |
| 接收 CPU | ≤ 12% of one core | UDP 接收 + jitter buffer + 解码 + 播放 + 录音写盘 |
| Java heap | < 32 MB | 待机 |
| 总内存 PSS | < 130 MB | 待机，**不含 ASR 模型** |
| 端到端延迟 P50 | ≤ 250 ms | 同一 AP |
| 端到端延迟 P95 | ≤ 400 ms | 同一 AP |
| 待机网络 | ≤ 1 广播包 / 5 秒 / 设备 | 心跳 |
| 启动时间 | 记录并对比，无硬性上限 | 冷启动 |

## 56.1 关于内存口径

原「约 30 MB」的表述容易诱导无效优化。含 Compose、Room、DataStore 与前台服务的应用，Android 11 上实际 PSS 通常在 60~120 MB。

因此拆为两个可测量口径：**Java heap < 32 MB**（应用自身分配的有效约束）与**总 PSS < 130 MB**（异常增长的告警线）。

## 56.2 关于 CPU 口径

必须统一为**单核百分比**。八核设备上的「整机百分比」与单核百分比相差 8 倍，口径不一致会让测试结论不可比。

## 56.3 判定规则

未达标项必须定位原因，并给出明确结论：优化，或修订目标值并说明理由。

不得为了达到数字而牺牲稳定性，也不得以「这是优化目标」为由跳过测量。

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

- build success（Debug + Release）
- lint clean / accepted findings
- unit tests pass
- **协议头部逐字节测试通过**
- **sequence 回绕测试通过**
- two-device PTT pass
- **VOICE_ACCEPT 握手 pass**
- **按下即录（首音节不丢失）pass**
- background receive pass（含熄屏、锁屏、长时间空闲）
- **无悬浮窗权限时的通知降级 pass**
- reconnect pass
- busy pass
- **force interrupt pass（接收方开关）**
- **并发强插竞态 pass**
- recording pass（双向）
- playback pass
- history / search / favorite / unread / delete / cleanup pass
- **正在录制文件的保护 pass**
- ASR pass（含模型下载与离线识别）
- i18n pass（五种语言，含缅甸语 / 孟加拉语字体）
- accessibility pass
- low-end device pass
- modern device pass（Android 13 / 14 / 15 / 16）
- 长时间稳定性 pass（8~24 小时）
- 性能指标已实测并对照 §56 判定
- **原生库 16 KB page 对齐验证通过**
- no blocking crash
- no blocking ANR
- release logging disabled
- no test/mock/fake code

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
