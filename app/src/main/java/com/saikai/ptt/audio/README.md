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

## 待实现

播放（Task21）、Opus 编解码（Task22/23）、发送与接收管线（Task24/25）、
AudioFocus 策略（ADR-004 §5）。