# core.domain

领域模型与对外接口：Peer、LocalUser、CommunicationRecord、错误类型，
以及 `PeerDiscovery`、`VoiceTransport`、`AudioRecorder`、`HistoryRepository`
等供实现层落地的接口（`docs/02_Architecture.md` §4.4、§37）。

接口在此、实现在 `app`，是子系统可替换的前提。

由 Task09 / Task13 起逐步填充。
