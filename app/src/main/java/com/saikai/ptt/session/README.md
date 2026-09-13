# app.session

把 `core.session` 的状态机接到这台设备的真实资源上：socket、麦克风、扬声器、
前台服务类型、电源锁。

**不含任何决策**。谁能讲话、忙线怎么仲裁、会话何时结束，全部在 `core.session`；
这里只负责把状态机的输出变成平台动作，把平台事件变成状态机的输入。这条线是
故意画在这里的——所有值得测的东西都在 core，而 core 能在 JVM 上跑。

由 **Task25**（发送）建立，**Task26**（接收与 jitter buffer）继续填充。

## 已实现（Task25）

| 文件 | 职责 |
|---|---|
| `SessionPacketListener.kt` | 收到的控制包 → 状态机的对应方法 |
| `VoiceSessionCoordinator.kt` | 一个 collector，把会话状态扇出到发送管线、电源锁、前台服务类型和 UI 镜像 |

**只有一个 collector**。发送管线要知道对方何时接受、电源锁要知道会话何时存在、
前台服务类型要在讲话期间加上 `microphone`、UI 要看到状态——这四件事订阅的是同一个
`SessionState`。写成四个订阅就是四次遗漏某条退出路径的机会，而退出路径恰恰很多：
BUSY、无应答、松手、被强插、WiFi 掉线、来电抢走音频焦点。

**麦克风类型的降级由状态驱动，不由按键驱动**。松手只是其中一条路径；剩下几条不经过
按键，而一个讲完话仍然声明 `microphone` 的服务会让状态栏的麦克风指示灯一直亮着——
那是在向用户明确宣称本应用正在听他说话。

## 接收（Task26）

`SessionPacketListener` 多做两件事：

- **VOICE_DATA** 喂给 `VoiceReceiver`。payload 是接收缓冲区上的视图，本次回调一返回
  就会被下一个数据报覆盖，所以 jitter buffer 在 `offer` 里立刻拷贝。
- **VOICE_END** 先 `receiver.flush(...)`，**再**把会话结束交给状态机。顺序是有意的：
  状态机的第一个动作就是把扬声器收走，而缓冲区里剩下的正是那句话的结尾。

`peerState` 与「立刻补一次心跳」不需要在这里做——`SessionManager.busy` 直接喂给
`UdpPresenceAnnouncer`（Task25 接好的），忙线标志一变它就补播一次（Task17）。