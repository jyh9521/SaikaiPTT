# app.audio

AudioRecord 采集、AudioTrack 播放、Opus 编解码、jitter buffer、AudioFocus。

参数固定见 `docs/ADR/ADR-004-Audio-Params.md`：16 kHz 单声道 20 ms 帧。

由 Task20~Task23 填充。

## 采集（Task20）

`AndroidAudioRecorder` 实现 `core.domain.AudioRecorder`，完整落实 ADR-004 §2：
16 kHz 单声道 16-bit PCM、20 ms 帧、VOICE_COMMUNICATION 优先 MIC 回退、
buffer ≥ 4 帧、专用线程 `THREAD_PRIORITY_URGENT_AUDIO`、每帧零分配。

**专用线程而不是协程**。`AudioRecord.read` 是阻塞的，这个循环整场发送都堵在里面。
放在 dispatcher 上等于占着池线程整场通话，同时还骗运行时说自己是可中断的；而且
拿不到 `THREAD_PRIORITY_URGENT_AUDIO`——那正是低端设备上别的东西一醒来就丢帧
与不丢帧的分界。

**`PcmFrameAssembler` 单独抽出来测**（在 `core.domain`）。`AudioRecord.read` 允许返回
比请求更少的字节，而喂给编码器一个短帧会产出对端解不开的包。它造成的 bug 特别难看：
音频**大体正常**，只在某次读取恰好偏短的地方有一声咔哒——而且在开发者自己的手机上
复现不了。

**停止靠 `record.stop()` 让阻塞的 read 返回**，和关 socket 让 `receive()` 返回是同一个道理。

## 播放与 AudioFocus（Task21）

`AndroidAudioPlayer`：`USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`、
`PERFORMANCE_MODE_LOW_LATENCY`、系统路由（默认扬声器，插耳机/连蓝牙就跟着走）。

**`AudioManager.mode` 保持 `MODE_NORMAL`，这是刻意的**。语音场景的直觉选择是
`MODE_IN_COMMUNICATION`，在这里是错的：PTT 是半双工，麦克风与扬声器从不同时打开，
不存在需要消除的回声路径。那个模式真正会做的事是改变全局音量流、打扰设备上
其它所有应用——为了一个本产品并不存在的问题。

音量流是从 attributes 推出来的：`USAGE_VOICE_COMMUNICATION` 走 `STREAM_VOICE_CALL`，
正是 ADR 要求的，也是发送期间音量键会调的那一条。

**写入永不阻塞**。它坐在网络与扬声器之间，一次等待腾出空间的写入会把「一帧迟到」
变成「此后每一帧都迟到」。满了就丢尾巴：实时对话里，最新的音频是唯一值得留的音频。

`AndroidVoiceAudio` 把录音、播放、焦点组合成会话机需要的那个接口。**焦点在设备打开
之前申请、打不开就立刻归还**——被拒绝的麦克风绝不能让本应用攥着音频焦点，那等于
白白掐掉用户的音乐。

## 待实现

Opus 编解码（Task22/23）、发送与接收管线（Task24/25）、jitter buffer（Task26）。