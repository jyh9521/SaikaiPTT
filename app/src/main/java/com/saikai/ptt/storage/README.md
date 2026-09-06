# app.storage

DataStore 设置、Room 通信记录、录音文件读写。

实现 `core.domain` 中的仓储接口，向上层隐藏具体存储方式。

**约定**：解码、默认值与损坏数据的处理放在 `core`（如 `SettingsCodec`），
这里只做与 Android 存储 API 之间的搬运。这样"损坏数据不崩溃"能在 JVM 上被证明，
而不必依赖真机。

- `DataStoreSettingsRepository` — Task07
- Room 通信记录 — Task37
- 录音文件 — Task38
