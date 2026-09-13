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

**usage 是 `USAGE_MEDIA`，不是 `USAGE_VOICE_COMMUNICATION`（`docs/ADR/ADR-008`）。**
后者承载在 `STREAM_VOICE_CALL` 上，凡是有听筒的设备都默认路由到听筒——对通话是对的，
对对讲机是错的。实测手机走听筒、平板外放，而平板"正常"只是因为它没有听筒可选。

track 级的 `preferredDevice` 压不过 voice-call 流的策略路由；平台给的正解
`setCommunicationDevice` 是 API 31 起才有的，而参考低端设备是 Android 11，它的前身
`isSpeakerphoneOn` 只在 `MODE_IN_COMMUNICATION` 下生效——那个模式本类刻意不进。
所以改的是 usage。媒体流在每个 API 级别上都默认走扬声器，不需要任何强制手段；
音量键随之调的是媒体音量，而那恰恰是用户真正调得到的那一条（通话音量在多数 ROM 上
只有通话进行中才露出来）。

内置扬声器仍会被设成 `preferredDevice` 作为第二道保险，**且仅在没有接入任何输出设备时**。
路由在 track 打开时选定，每次接收都会新开一个 track，所以两次通话之间拔耳机跟得上，
一次通话中间拔跟不上，代价最多几秒。

实际路由到哪儿会记一条 INFO 日志（`routed to ...`）——路由问题在别人的机器上只能靠它发现。

**写入永不阻塞**。它坐在网络与扬声器之间，一次等待腾出空间的写入会把「一帧迟到」
变成「此后每一帧都迟到」。满了就丢尾巴：实时对话里，最新的音频是唯一值得留的音频。

`AndroidVoiceAudio` 把录音、播放、焦点组合成会话机需要的那个接口。**焦点在设备打开
之前申请、打不开就立刻归还**——被拒绝的麦克风绝不能让本应用攥着音频焦点，那等于
白白掐掉用户的音乐。

## 接入发送管线（Task25）

`AndroidVoiceAudio` 的 `frames` 直接就是 `core.session.VoiceTransmitter::onPcmFrame`
——采集线程与编码器之间没有队列，也就没有需要调的参数。一帧是被缓冲还是被发送由
发送管线决定，因为那是会话的性质，不是麦克风的性质。

编解码器**每次发送新建一个**（`ServiceContainer.newCodec`）。理由写在
`core.session.VoiceTransmitter` 与 `core.session/README.md` 里。

## 接入接收管线（Task26）

`AndroidVoiceAudio.startPlayback` 除了开扬声器，还要开 `core.session.VoiceReceiver`
——**在会话机的锁里、在 VOICE_ACCEPT 发出之前**。所以不存在「帧已经到了但还没地方放」
的那一瞬间。解码器开不出来就跟扬声器开不出来一样拒绝会话：开着扬声器却什么都放不出来，
是比拒绝更糟的那个答案。

**`AndroidAudioPlayer.stop()` 现在会等已经排队的音频放完。** `AudioTrack.stop()`
在 stream 模式下会把缓冲区里的放完再停，`pause() + flush()` 则直接扔掉。发送结束的
那一刻，jitter buffer 的垫底加设备自己的缓冲总有几百毫秒在路上，扔掉它等于**每一句话
都被切掉最后一个词**——这种毛病发出去以后会被算在网络头上。等待时长按「写入帧数减去
播放头位置」算，并以设备缓冲本身为上限，因为那是它最多能攥住的量，也因为这个等待会
发生在服务关闭的过程中。