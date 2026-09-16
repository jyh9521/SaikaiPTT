# app.storage

DataStore 设置、Room 通信记录、录音文件读写。

实现 `core.domain` 中的仓储接口，向上层隐藏具体存储方式。

**约定**：解码、默认值与损坏数据的处理放在 `core`（如 `SettingsCodec`），
这里只做与 Android 存储 API 之间的搬运。这样"损坏数据不崩溃"能在 JVM 上被证明，
而不必依赖真机。

## history/

Room 通信记录（Task37）。字段表以 `05_DataModel §10` 为唯一定义，索引以 `§27` 为准。

**这里没有 destructive migration，且不得加。** Room 在缺少 migration 时默认抛异常，那
正是本产品想要的行为：另一种选择是在一次升级中静默删掉用户录下的全部通话，没有警告也
没有退路。schema 导出到 `app/schemas/` 并提交，将来的 migration 才有东西可迁。

**任何一个方法都不会抛给调用方。** `HistoryRepository` 每个方法都返回 `Outcome`，
`RoomHistoryRepository` 把磁盘满、文件损坏、migration 缺失统统变成
`HistoryError.Unavailable` 加一条 WARN。`05_DataModel §48` 要求历史故障不得导致发现、
心跳、语音停止——接口会抛异常的话，那就变成每个调用方都要靠记得写 try/catch 来维持的
承诺。`CancellationException` 是有意放行的：它不是故障，是调用方的 scope 要走了。

实体与领域模型是两份。领域模型 `CommunicationRecord` 在 `:core`，因为 `:core` 不许看见
`androidx`——正是这条边界让「方向、对端 id、未读初值、路径与格式必须成对」这些规则能在
JVM 上被证明（`CommunicationRecordTest`），而不必依赖真机。代价是这里多一份字段拷贝和
两个没有逻辑的映射函数。

DAO 与索引只能在真机上验（`app/src/androidTest/.../CommunicationRecordDaoTest.kt`）：
DAO 的实现是构建期生成的，它对话的是 Android 自己的 SQLite。

## 清理（Task40）

**「什么时候删、按什么顺序删」在 `core.history`，不在这里。** 那是全应用唯一一处删除
用户拿不回来的东西的逻辑，它需要被测试，而文件系统调用写在里面就没法测。这里只有两个
很薄的东西：

- `LocalRecordingFiles` — `core.history.RecordingFiles` 的实现。它是**唯一**一处把
  数据库里的相对路径（`05_DataModel §19`）拼成绝对路径用于清理的地方。
  `delete()` 会检查规范化后的路径是否仍在 `records/` 之下才动手——上游出一个 bug 的
  代价，不该是删掉私有目录里的别的东西。
- `HistoryMaintenance` — **什么时候跑**。启动时一次，之后每 6 小时一次，跑在前台服务
  的 scope 里。WorkManager 是另一个答案，但这件事没有 deadline、没有约束条件、也不需要
  活过进程（`CLAUDE.md` §32）。设置页的「立即整理」和这个循环共用同一个 mutex，
  两轮不会互相看到对方删了一半的状态。

保留期每轮重新读，不缓存：用户从 30 天改成 1 天，期待的是下一轮就照新的来。

---

- `DataStoreSettingsRepository` — Task07
- `history/` Room 通信记录 — Task37
- 录音文件 — Task38
- 搜索、批量删除、清理落盘 — Task40
