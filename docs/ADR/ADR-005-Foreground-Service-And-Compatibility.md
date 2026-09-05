# ADR-005 — 前台服务、Android 版本兼容与电源锁

Status: Accepted
Date: 2026-09-05

## Context

原文档只写「使用 Foreground Service」，没有出现 Android 12~16 的任何相关限制。同时 `.claude/CLAUDE.md §13` 与 `01_PRD §42` 要求「禁止使用 WakeLock」，而后台熄屏接收 UDP 广播在物理上需要 `MulticastLock`。两者必须给出明确边界，否则要么后台收不到（违反核心需求），要么随手长期持锁（违反功耗要求）。

## Decision

### 1. Manifest 与权限

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".service.CommunicationService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|microphone" />
```

### 2. 前台服务类型的运行时切换

| 状态 | `startForeground` 使用的类型 |
|---|---|
| 服务常驻（发现 / 心跳 / 接收播放） | `connectedDevice` |
| 用户按住 PTT 发送期间 | `connectedDevice \| microphone` |

- 服务启动时只声明 `connectedDevice`；按下 PTT 时再次调用 `startForeground()` 加入 `microphone`，松开后调回 `connectedDevice`。
- **接收与播放不需要麦克风**，因此后台接收不受麦克风类型限制影响——这正是本产品最关键的后台能力。

### 3. 产品级约束（必须写入 PRD）

Android 14+ 禁止从后台启动或提升为 `microphone` 类型的前台服务。因此：

> **发送（按住 PTT 讲话）要求 SaikaiPTT 的界面处于可见状态。**
> 从悬浮窗点击时，先拉起 Activity，再允许发送。
> **接收始终不受此限制**，后台、熄屏、锁屏均可接收并播放。

这不是实现缺陷，是平台规则，必须在 UI 上表达清楚（悬浮窗点击 = 打开应用 = 才能回话）。

### 4. 开机自启

- `RECEIVE_BOOT_COMPLETED` → 以 **`connectedDevice` 类型**启动服务（Android 14+ 允许该类型由 `BOOT_COMPLETED` 启动；`microphone` / `camera` 类型不允许）。
- 因此开机后服务处于「可接收、不可发送」状态，用户首次打开应用后即恢复完整能力。
- 部分厂商 ROM 会拦截自启，`04_UI_UX §36` 的权限状态页必须提供引导入口，且不得硬编码厂商设置页路径。

### 5. 进程被杀后的恢复

Android 12+ 禁止从后台启动前台服务。因此：

- **不承诺**进程被系统杀死后自动恢复通信。
- 服务使用 `START_STICKY`；系统在允许的情况下重建服务，不允许时保持停止。
- UI 在下次打开时检测服务状态并恢复。
- `01_PRD §58` 的措辞必须相应放宽为「不损坏本地数据；下次打开应用时恢复」。

### 6. 电源锁策略（覆盖「禁止 WakeLock」的一般性禁令）

| 锁 | 何时持有 | 理由 |
|---|---|---|
| `WifiManager.MulticastLock` | 服务处于 READY 状态期间**常驻** | 熄屏后 WiFi 省电模式会丢弃广播帧；不持有则后台收不到 DISCOVERY / HEARTBEAT，核心需求失效 |
| `WifiManager.WifiLock(WIFI_MODE_FULL_LOW_LATENCY)` | **仅**在语音会话存在期间 | 降低语音抖动；会话结束立即释放 |
| `PowerManager.PARTIAL_WAKE_LOCK` | **仅**在语音会话存在期间，且设置超时上限 5 分钟 | 保证熄屏时音频线程不被挂起 |

禁止事项保持不变：不得为心跳、发现、日志或 UI 刷新持有任何 WakeLock；不得持有无超时的 WakeLock。

`.claude/CLAUDE.md §13` 与 `01_PRD §42` 的措辞据此修改为「除本 ADR 列出的三处外，禁止持有电源锁」。

### 7. Android 版本兼容红线（每个 Task 执行时对照）

| API | 版本 | 影响 |
|---|---|---|
| 30 | 11 | minSdk。分区存储；录音文件一律写应用私有目录 |
| 31 | 12 | 禁止后台启动 FGS；`PendingIntent` 必须显式指定 `FLAG_IMMUTABLE`；精确闹钟受限 |
| 33 | 13 | `POST_NOTIFICATIONS` 成为运行时权限；per-app language 由系统托管（用 `AppCompatDelegate.setApplicationLocales` + `localeConfig`） |
| 34 | 14 | FGS 必须声明类型并申请对应权限；`BOOT_COMPLETED` 不得启动 mic/camera 类型 FGS；隐式 Intent 限制 |
| 35 | 15 | 原生库必须 **16 KB page size 对齐**；`dataSync` 类型 FGS 有 6 小时上限（本项目不使用该类型） |
| 36 | 16 | 沿用 15 的约束；发布前需在真机复测 FGS 与通知行为 |

## Consequences

- `Task AndroidProjectInitialization` 必须一次性写对 Manifest 的服务类型与权限声明。
- 新增独立的「前台服务骨架」Task，置于网络接收循环之后、发现/心跳之前，使 socket 与生命周期从第一天起由 Service 持有，避免后期属主重构。
- `01_PRD §57 / §58`、`.claude/CLAUDE.md §13`、`02_Architecture §25/§26` 需按本 ADR 修订。
