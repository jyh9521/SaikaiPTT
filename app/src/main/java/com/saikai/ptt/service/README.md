# app.service

前台通信服务：生命周期、组件编排、前台服务类型的运行时切换、电源锁。

**不承担协议解析、音频处理或业务逻辑**，只做编排（`docs/02_Architecture.md` §25）。

由 **Task15** 建立骨架，**Task30** 完善。

## 已实现（Task15）

| 文件 | 职责 |
|---|---|
| `ServiceLifecycle.kt` | 有序启动、倒序回滚、幂等停止（纯 Kotlin，可 JVM 测试） |
| `LifecycleSteps.kt` | 三个具体步骤：前台、MulticastLock、传输 |
| `ServiceNotifications.kt` | 通知渠道与常驻通知（占位内容，正式版 Task31） |
| `ServiceStatus.kt` | 服务状态与上次失败原因，**应用级作用域** |
| `CommunicationService.kt` | Android Service 外壳 |

启动顺序写成**一个列表**，停止就是这个列表倒过来（`02_Architecture §26`）。写成列表
而不是一个八行的方法，是为了让回滚可被证明：只有一个正向循环和一个反向循环。后续
任务加音频、加会话，是往列表里插一项，而不是去改两段互为镜像的代码、然后漏改一段。

失败的那一步**也会被回滚**。一个步骤拿了两个资源、在第二个上失败，只有它自己知道
拿到哪儿了——所以接口写明 `stop()` 必须容忍「从没 start 过」。

`ServiceStatus` 放在**应用级**而不是服务级：ADR-005 §5 说进程被杀后不承诺自动恢复，
那么下次打开的界面必须能问「现在是什么状态」——而那恰恰是没有服务可以 bind 的时候。

**前台声明调用了两次**。Android 只给 `startForegroundService` 之后几秒钟时间调用
`startForeground`，而解析 Device ID 要读 DataStore。所以 `onStartCommand` 里先同步占住
前台，`ForegroundStep` 再在它应有的位置幂等地调一次——倒序停止仍然在正确的位置释放它。

**不导入 `com.saikai.ptt.ui`**。通知的点击意图走 `getLaunchIntentForPackage`，不点名
Activity 类：服务必须在没有任何 Activity 存活时照常工作，那正是后台接收的前提。
`ArchitectureRulesTest` 会为此让构建失败。

## 网络恢复（Task18）

`NetworkRecovery` 在**生命周期之上**，不在里面。恢复流程不是在它里面重写一遍，
而是 `ServiceLifecycle` 那张步骤表的**后缀**：倒序释放，正序重启。这正是启动顺序
写成列表的全部理由——后面的任务往表里插音频、插会话机，它们跨越一次 WiFi 掉线
的拆卸与重建是免费的，而且不需要知道这个类存在。

一个「会重启自己所属列表」的步骤，必须小心永远不要重启自己；而「必须小心永远不要」
不是一个资源序列该依赖的性质。

新增状态 `DEGRADED`：前台通知与 MulticastLock 还在，socket / 发现 / 心跳已释放，
因为没有网络可跑。说 READY 是撒谎，说 STOPPED 会招来一次并不需要的重启。

三条来自协议、每条都是「决定不要耍聪明」：

- **Device ID 永不因 IP 改变**。地址不是身份。在接入点之间移动的手机，对所有正在
  和它通话的人来说还是同一台电台。
- **语音会话不跨网络续接**，直接结束并标记 INTERRUPTED。跨网络状态存活的实时音频
  是一个兑现不了的承诺，硬试的结果是一通听起来坏掉的通话，而不是一通结束了的通话。
- **换 IP 也算一次恢复**，即使没有任何东西「丢失」。

## 发送端 PTT（Task25）

| 文件 | 职责 |
|---|---|
| `Ptt.kt` | `PttController`（按下的前置检查与前台服务类型提升）+ `PttGateway`（UI 的入口） |
| `VoiceSessionPowerLocks.kt` | `WifiLock(FULL_LOW_LATENCY)` + 带超时的 `PARTIAL_WAKE_LOCK`，仅会话期间 |

**提升在告知状态机之前**。Android 14 不允许后台把前台服务提升为 `microphone` 类型，
所以「讲话要求界面可见」是平台规则而不是选择（ADR-005 §2、§3）。先问可见性、再提升、
最后才 `requestTalk`：被拒绝时代价为零，麦克风也不会被一个注定失败的请求打开。

平台的答复才算数。可见性检查与提升之间应用可能已经切到后台，所以提升抛出的
`ForegroundServiceStartNotAllowedException` / `SecurityException` 不是异常路径，
是同一个答案的另一种说法，统一变成 `SendFailure.APP_NOT_VISIBLE`。

**降级由会话状态驱动，不由按键驱动**（见 `app.session.VoiceSessionCoordinator`）。
松手只是七条结束路径之一，而一个讲完话仍声明 `microphone` 的服务会让状态栏的麦克风
指示灯一直亮着。

电源锁同理，而且 wake lock 带 5 分钟超时。超时不是保险丝而是最后一道防线：一个进程
如果没跑完自己的释放路径，能注意到的只有平台。

## 待实现

- 正式通知与操作按钮（Task31）
- 开机自启（`BOOT_COMPLETED` → `connectedDevice`）
