# ADR-008 — 播放路由：改用 `USAGE_MEDIA`

Status: Accepted
Date: 2026-09-13
Amends: ADR-004 §3（该 ADR 其余部分不变，仍为 Accepted）

## Context

`ADR-004 §3` 一次性钉死了四件事：

1. `AudioAttributes` 用 `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`
2. `AudioManager.mode` 保持 `MODE_NORMAL`
3. **默认输出扬声器**，插耳机/蓝牙时跟随系统路由
4. 音量走 `STREAM_VOICE_CALL`

真机上第 1、2、4 条与第 3 条互相排斥。

`USAGE_VOICE_COMMUNICATION` 承载在 `STREAM_VOICE_CALL` 上，而那是打电话那条流：
**任何带听筒的设备，平台默认把它路由到听筒**。这对通话是对的，对对讲机是错的——
对讲机举在面前，不贴在耳朵上。

实测（一台平板 + 一台手机）：平板外放正常，手机走听筒。平板"正常"只是因为它**没有
听筒可选**，不是因为配置对。

先尝试过在 `AudioTrack` 上设 `preferredDevice = 内置扬声器`，无效——voice-call 流的
路由由音频策略决定，track 级的偏好压不过它。

其余可选项都不成立：

- **`AudioManager.setCommunicationDevice()`**（API 31+）是这件事的官方 API，但本项目
  `minSdk = 30`，而**参考低端设备正是 Android 11**。API 30 上的等价物
  `isSpeakerphoneOn` 只在 `MODE_IN_COMMUNICATION` 下生效——那个模式正是 ADR-004 §3
  第 2 条明确拒绝的（它会改变全局音量流并打扰设备上其它所有应用）。所以这条路在
  最需要它的那台设备上不通。
- **进入 `MODE_IN_COMMUNICATION`**：直接推翻 ADR-004 的既有理由。PTT 是半双工，
  麦克风与扬声器不同时开，不存在需要消除的回声路径。

## Decision

**播放侧的 `AudioAttributes` 改为 `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`。**

`ADR-004 §3` 的第 1 条与第 4 条据此作废，第 2 条（`MODE_NORMAL`）与第 3 条
（默认扬声器、跟随插入设备）保持不变——**改这一条正是为了让第 3 条成立**。

连带结果：

- 默认路由变成扬声器，在每个 API 级别上都成立，不需要任何强制手段。
- 音量流变成 `STREAM_MUSIC`。
- 内置扬声器仍会被设成 track 的 `preferredDevice`，但**仅在没有接入任何输出设备时**，
  作为双保险；接了耳机/USB/蓝牙就交回系统路由。
- 实际路由到的设备记一条 INFO 日志。路由问题在别人的机器上只能靠这个发现。

## Rationale

**`USAGE_MEDIA` 是对这段音频的诚实描述。** ADR-004 用「半双工、没有回声路径、不是一通
电话」为由拒绝了 `MODE_IN_COMMUNICATION`；同一条推理同样适用于 usage。本产品播放的是
一段几秒的语音片段，外放给房间里的人听——那就是媒体播放的形状，不是通话的形状。

**音量流的改变是个改进，不是让步。** `STREAM_VOICE_CALL` 的音量在多数 ROM 上**只能在
通话进行中调整**，在本应用里意味着「只有别人正在对你讲话的那几秒才能调」。仓库、工地
这类场景下，用户按音量键期望调的就是媒体音量，而且它通常更响、更好设。

**采集侧不变。** `MediaRecorder.AudioSource.VOICE_COMMUNICATION` 与播放的 usage 是两套
独立机制，ADR-004 §2 选它是为了平台的回声消除、降噪与增益控制，那些理由一条都没变。

**AudioFocus 请求仍用 `USAGE_VOICE_COMMUNICATION`。** 焦点属性决定的是**别的应用**
怎么反应：要求它们暂停而不是压低音量，因为有人正在对用户说话。播放属性决定的是路由与
音量流。两者服务于不同的问题，各自取各自正确的值。

## Consequences

- `ADR-004 §3` 的 usage 与音量流条目以本 ADR 为准；ADR-004 其余部分（采样率、帧长、
  Opus 参数、`MODE_NORMAL`、AudioFocus 策略、jitter buffer）不受影响。
- 播放期间音量键调的是媒体音量。这需要在 UI 上体现（Task32/Task35）。
- 本产品的目标场景不含耳机与蓝牙，但跟随插入设备的行为予以保留：它是免费的，而
  移除它需要在每次路由决策处写一条「已知不支持」。
