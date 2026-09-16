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

**`PttController` 负责离开调用者的线程**。它下面没有任何东西会挂起——`SessionSignals`
是在会话所有权那把锁里被调用的，实现不允许挂起，测试也在断言这一点。代价是整条链跑在
谁调用它就跑在谁的线程上，而链里有一次 datagram 发送；讲话键是在主线程上按下的，
Android 会因此杀掉进程。所以 dispatcher 定在这里而不是各个调用点：这是 UI 唯一的那扇门
（今天是调试界面，Task32 是正式按钮，Task36 是悬浮窗），交给它们等于把同一个陷阱布三遍。

同时是 `NonCancellable`，这不是保险起见。press 中途被取消时麦克风已经打开、会话还没建立，
之后没有任何东西会去关它；release 被取消则留下开着的麦克风和一个还在等音频的对端。
两者都够得着——`LaunchedEffect` 的 key 一变或者 composable 一离开就会取消。这两个调用里
都没有无界等待，跑完只要几毫秒。

**降级由会话状态驱动，不由按键驱动**（见 `app.session.VoiceSessionCoordinator`）。
松手只是七条结束路径之一，而一个讲完话仍声明 `microphone` 的服务会让状态栏的麦克风
指示灯一直亮着。

电源锁同理，而且 wake lock 带 5 分钟超时。超时不是保险丝而是最后一道防线：一个进程
如果没跑完自己的释放路径，能注意到的只有平台。

## 生产化（Task30）

| 文件 | 职责 |
|---|---|
| `BootReceiver.kt` | 开机后以 `connectedDevice` 类型拉起服务 |

**子系统失败不得杀死服务，而 `SupervisorJob` 并不能做到这件事。** 这是本任务改动
最大的一处，也是最容易漏的一处：supervisor 只阻止一个子协程的失败去取消它的兄弟，
对异常本身什么都不做——异常照样走到线程的 uncaught handler，在 Android 上那就是**进程**。
于是一次心跳循环抛异常会带走整个应用，包括一通与它毫无关系的通话。

`core.common.subsystemScope` 补上 `CoroutineExceptionHandler`，服务、发现、心跳、传输
四处全部换用它（传输保留自己的 Job 以便 `stop()` join，所以单独取 handler）。抛异常的
那个协程仍然会死，因为没有可恢复的语义；它的兄弟和进程活着。

**周期性循环则连死都不该死。** `core.common.repeatEvery` 让心跳与超时扫描在 work 抛
异常时只损失一个周期。心跳循环一旦死掉，三个周期后这台设备就从全网的设备列表里消失，
而它自己的屏幕上一切正常——那是最坏的一种失败。

**开机自启不预先检查设备是否配置过。** 检查要读 DataStore，而在 `startForegroundService`
之前让 BOOT_COMPLETED 广播结束，正好放弃了「允许从后台启动前台服务」的那个豁免。
拿确定性换体面是反的：没设名字的设备什么都不广播，只是浪费一条通知，不是故障。

## 常驻通知（Task31）

`ServiceNotifications` 说两件事：常态「動作中です」，以及接收中「受信中：<名前>」
（`04_UI_UX §18.2`）。

**每次会话最多改两次**（`01_PRD §22`）。做法是由会话状态的**边沿**驱动，而不是由任何
会在过程中滴答的东西驱动——`VoiceSessionCoordinator` 只在进入和离开 `Receiving` 时各
调一次。`apply()` 里「没变化就什么都不做」让这个上限由构造保证，而不是靠调用方自觉。

**文字与前台服务类型是同一个状态，走同一个调用。** 这两者独立变化：提升到 microphone
类型会重新 post 一次通知，如果那次调用不带上当前文字，就会把「受信中」悄悄抹回常态。

**接收提示是降级路径。** `04_UI_UX §18.2` 说悬浮窗在屏幕上时由悬浮窗负责，通知不该
重复说同一件事。悬浮窗是 Task36，所以今天没有别的东西在说，通知一律出声；Task36 落地
时那个条件加在 `ServiceContainer.announceReceiving` 一处，别处不用动。§18.2 明令禁止的
是**完全没有可见反馈**。

**通知权限被拒不是错误。** Android 13 起 `POST_NOTIFICATIONS` 是运行时权限，用户可以
拒绝。`startForeground` 照常工作、服务照常运行，只是通知不显示——接收仍然可用，而用户
没有办法看到它可用。这是平台的取舍，本类不把它当失败处理。

### 待实测记录（各 Android 版本的差异）

`Task31` 要求实测记录版本差异，以下需在真机上核对后补进本节：

| 版本 | 需要确认 |
|---|---|
| 11 / 12 | 低重要性渠道下通知是否被折叠进「静默」区；`FOREGROUND_SERVICE_IMMEDIATE` 是否消除了 Android 12 的 10 秒延迟 |
| 13 | 拒绝 `POST_NOTIFICATIONS` 后服务是否仍在运行、能否接收 |
| 14 / 15 / 16 | 讲话期间麦克风指示灯是否随类型切换点亮与熄灭；接收文案的两次更新是否都出现 |

（One UI 会把低重要性通知放进「サイレント」分组，Task15 时已确认过一次。）

## 待实现

- 通知上的操作按钮（停止服务、回话）——本任务只做文案与状态，按钮等 UI 定稿
