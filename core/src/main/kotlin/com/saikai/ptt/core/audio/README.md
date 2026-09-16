# core.audio

音频**文件格式**。不含采集、播放、编解码——那些是接口在 `core.domain`、实现在
`app.audio`。

这里目前只有一样东西：`OggOpusWriter`，把已经编码好的 Opus 帧套进 Ogg 容器。

**为什么在 `:core` 而不是 `:app`。** `ADR-004 §6` 禁止二次编码：发给网络的那些帧就是写进
文件的那些帧，接收端也是收到什么写什么。于是容器逻辑变成纯粹的字节拼装——页头、分段表、
CRC——不依赖任何 Android API，因此可以在 JVM 上逐字节证明它对。这跟
`02_Architecture §5.1` 对 `:core` 里其它东西的论证是同一条。

**为什么不用 `MediaMuxer`。** 平台从 API 29 起支持 Ogg 输出，minSdk 是 30，够用。不用它
有两个理由：一是通过 MediaMuxer 复用 Opus 要把 codec-specific data 交给它再由它重新推导，
而各家 ROM 的行为不一致；二是写在这里的东西能被单元测试逐字节验证，而 MediaMuxer 只能在
真机上试。Ogg 的分页规则大约两百行，且不会再变。

**注意 CRC 不是常见的那个。** Ogg 用多项式 0x04c11db7、MSB 优先、输入输出都不反转、末尾
不取反。`java.util.zip.CRC32`（zlib/PNG 那个）两头都反转并且末尾取反，拿它来算出来的文件
所有 Ogg 播放器都会拒绝。

由 **Task38** 建立。
