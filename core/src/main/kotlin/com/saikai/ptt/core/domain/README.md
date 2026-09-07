# core.domain

领域模型与对外接口：Peer、LocalUser、CommunicationRecord、错误类型，
以及 `PeerDiscovery`、`VoiceTransport`、`AudioRecorder`、`HistoryRepository`
等供实现层落地的接口（`docs/02_Architecture.md` §4.4、§37）。

接口在此、实现在 `app`，是子系统可替换的前提。

## Peer 与在线状态（Task13）

| 文件 | 职责 |
|---|---|
| `PresenceState.kt` | 五个状态 + 声明式转换表 |
| `PeerEndpoint.kt` | 地址与浮动语音端口（字符串地址，core 不碰 socket 类型） |
| `Peer.kt` | 领域模型；身份只由 `deviceId` 决定 |
| `PeerObservation.kt` | 一个 presence 包翻译成的四个事实，不含协议类型 |
| `PeerRegistry.kt` | Peer 表 + 状态机 |

状态是**推导出来的，不是赋值的**。每个 peer 只存三个事实——上次听到它是什么时候、
它自己的 presence 包说它忙不忙、本机是否正在和它通话——任何一个变化后重算全表。
赋值式状态会被乱序事件留在陈旧值上，而三个异步输入下乱序是常态而非边角情况。

重算覆盖全表而不只是变化的那个：和 B 建立会话必须同时把 A 移出 `COMMUNICATING`，
指望每个调用点都记得这件事是靠不住的。表最多 64 项、每秒变化个位数，代价可以忽略。

`DISCOVERED` 与 `ONLINE` 的区别是**证据强度**（`03_Protocol §14`「Heartbeat 主要决定
ONLINE / OFFLINE」）：DISCOVERY 证明设备此刻存在，只有 HEARTBEAT 证明它的周期性
存活通道能到达本机。一个卡在 DISCOVERED 直到超时的 peer，正是 `§10.6` 那种
「转发单播但过滤广播」的 AP 的可见症状。设备列表把两者画成一样。

由 Task09 / Task13 起逐步填充。
