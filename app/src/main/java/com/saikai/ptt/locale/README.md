# app.locale

应用内语言切换。

**不使用 AppCompat**：`androidx.appcompat` 会引入 AppCompat 主题体系（与本项目的
平台主题冲突），而本产品只需要"设定语言"这一件事。见 `docs/04_UI_UX.md` §35.1。

两条路径：

- **Android 13+**：`LocaleManager.applicationLocales`。系统托管，用户能在
  系统设置 → 应用 → 语言里看到并修改，资源解析由系统完成。
- **Android 11~12**：自行包装 `Configuration`，在 `attachBaseContext` 生效。

DataStore 的 `app_language` 是权威值；另有一份 SharedPreferences 缓存，只为
在第一个 Activity attach 之前**同步**读到语言——DataStore 是异步的，而
`attachBaseContext` 不能等待。
