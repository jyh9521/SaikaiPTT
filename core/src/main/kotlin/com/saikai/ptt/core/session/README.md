# core.session

PTT 会话状态机：发送端 IDLE→REQUESTING→TRANSMITTING→ENDING，
接收端 IDLE→RECEIVING→ENDING，以及 INTERRUPTED 与各 FAILED 分支。

会话所有权的原子转移（Busy 与强插的仲裁）也在这里，
见 `docs/03_Protocol.md` §33、§34。

由 **Task19** 填充。

## 已实现（Task19）

| 文件 | 职责 |
|---|---|
| `SessionState.kt` | 单一密封状态 + 失败原因 + 会话结局 |
| `SessionSignals.kt` | 控制包发送的接缝（机器永远看不见 socket） |
| `VoiceAudio.kt` | 音频设备的接缝，四个方法，附 `NoVoiceAudio` |
| `SessionManager.kt` | 状态机本体 |

**一个变量，一把锁**。`SessionState` 就是 `03_Protocol §34` 说的那个「会话所有权」
变量，对它的每一次读写都在同一个 Mutex 内。关键在于：**判定与应答在同一个临界区**
——两台设备同时发 VOICE_START，绝不能都看到空闲、都被告知可以。这个类里其余的一切
都是从这一条推出来的。

**REQUESTING 不是 TRANSMITTING**。只有 VOICE_ACCEPT 能推进（§19.5：没有肯定应答时，
发送端状态机根本没有任何合法事件可以离开 REQUESTING）。采集在整个等待期间一直运行
并本地缓冲，所以这段等待对用户是免费的。

**由被叫方决定**。忙线与强插都是接收方的策略（§33）。呼叫方永远只发 VOICE_START
然后等结果，绝不去查设备列表自行判断——那份列表最长滞后一个心跳，判断出来是猜的。

### 三个容易写错的地方

1. **重发的 VOICE_START 必须再回一次 VOICE_ACCEPT，不能回 BUSY**。发送方最多重发两次；
   如果第一次的 VOICE_ACCEPT 丢了而我们回 BUSY，一个丢包就变成了一次被拒绝的通话。
2. **发送中不能套用 idle 超时**。发送期间对端什么都不回（设计上就没有确认包），
   套用 3 秒 idle 超时会掐掉每一条超过 3 秒的话音。idle 超时只作用于接收方；
   `maxDuration` 两端都管。
3. **立即失败与延迟失败严格分开**：本机自己能判定的失败（已在会话中 / 没有名字 /
   麦克风打不开 / 包发不出去）从 `requestTalk` 返回，**绝不**发到 `outcomes`；
   依赖对端的失败（BUSY / 无应答）只走 `outcomes`。两边都报会让每个调用方要么
   重复处理，要么二选一。

### 待接入

历史记录消费 `outcomes`（Task27）、接收端音频与 jitter buffer（Task26）。

## 已实现（Task25）

| 文件 | 职责 |
|---|---|
| `DatagramSink.kt` | 发送管线与 socket 之间唯一的接缝 |
| `VoiceFrameBuffer.kt` | 等待应答期间捕获的帧，有界环形，满了丢最旧 |
| `VoiceRecording.kt` | 录音钩子的四个时刻，Task38 之前是 `NoVoiceRecording` |
| `VoiceTransmitter.kt` | 发送管线本体，同时也是状态机的 `SessionSignals` 实现 |

**发送管线放在 core，不放在 app**。它需要的东西——打包器、编解码器接口、配置——全在 core，
唯一属于平台的是「把这些字节发出去」，那就是 `DatagramSink`。这样「按下即录的音频是否按
捕获顺序发出」这类问题能在 JVM 上被断言，而不是靠两台手机在一个房间里试。

**`SessionSignals.voiceEnd` 去掉了两个参数**。VOICE_END 要带最后一帧的序号与帧数
（ADR-003 §4），而这两个数只有真正发出这些帧的对象知道：状态机不数帧，被编码器拒绝或被
socket 丢掉的帧从来没有变成过序号。从上面传下来只能是猜的——那正是这两个位置在发送管线
存在之前一直是 `0, 0` 的原因。

**一个 collector，不是四个订阅**。发送管线要知道对方何时接受、电源锁要知道会话是否存在、
前台服务类型要在讲话期间带上 `microphone`、UI 要看到状态。会话结束的路径有七条（松手、
BUSY、无应答、被强插、WiFi 掉线、来电抢焦点、服务停止），四个订阅就是四次漏掉其中一条的
机会，而漏掉留下的是一个没释放的 wake lock，或者一直亮着的麦克风指示灯。见
`app.session.VoiceSessionCoordinator`。

**每次发送新建编解码器**。`VoiceCodec` 在帧之间携带状态，而对端每接受一个会话都会新建
解码器；沿用上次发送的编码器等于用对端从未见过的历史做预测，而解错的恰恰是最前面几帧
——正是预滚缓冲要保护的那几帧。
