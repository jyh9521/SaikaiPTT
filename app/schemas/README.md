# Room 导出的 schema

这个目录的内容由**构建生成**，并且**必须提交进版本控制**。

`app/build.gradle.kts` 把 `room.schemaLocation` 指到这里，Room 在每次编译时把
`com.saikai.ptt.storage.history.SaikaiDatabase` 的当前版本写成一份 JSON：

```text
app/schemas/com.saikai.ptt.storage.history.SaikaiDatabase/1.json
```

**为什么必须提交：** 数据库故意没有 destructive fallback
（见 `SaikaiDatabase` 的注释、`05_DataModel` 与 Task37）。将来任何一次 schema 变更
都要写 migration，而 migration 需要知道**上一个版本长什么样**——这份 JSON 就是那个记录。
不提交它，第一次升级时就没有任何东西可以迁移。

它同时也是 `room-testing` 的 `MigrationTestHelper` 读取的输入。

第一次构建之后，把生成的 `1.json` 一起提交。之后每改一次 `version`，都会多出一份。
