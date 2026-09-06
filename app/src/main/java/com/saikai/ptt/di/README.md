# app.di

依赖装配。手写轻量容器，不引入 DI 框架（`docs/02_Architecture.md` §7）。

**这是唯一允许同时引用接口与实现的 package。**

两级作用域：

- Application scope — Config、Logger、SettingsRepository、DeviceIdentityProvider、HistoryRepository
- Service scope — transport、discovery、presence、sessionManager、audio、overlay，
  随 Foreground Service 创建与销毁，避免服务停止后仍有对象持有 socket 或音频资源

容器在 **Task05** 随第一个可注入对象（Config）建立。
