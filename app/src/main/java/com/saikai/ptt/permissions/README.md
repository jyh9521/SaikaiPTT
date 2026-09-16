# app.permissions

系统权限的**读取**与**跳转**，只有这两件事。

不含界面（在 `ui.permissions`），不含「是否已经引导过」这类持久状态（在 `AppSettings` 的
`permission_guidance_shown` / `first_launch_completed`）。

三条规则：

**一、永不缓存。** 用户可以在应用退到后台时改掉任何一项，缓存的答案会让权限状态页在它唯一
存在的意义上撒谎（`04_UI_UX §36`）。每次读都是几个系统调用。

**二、不硬编码任何厂商路径。** 这里出现的每一个 Intent 都是平台常量。`01_PRD §24.2` 与
`§36.1` 都明令禁止厂商路径，理由很实际：写死的厂商 Activity 在下一个 ROM 版本上就是
`ActivityNotFoundException`，在没导出它的设备上是 `SecurityException`。因此
`PermissionNavigator` 的契约是「返回一个**可能**能用的 Intent」，启动失败由调用方降级为
文字指引。

**三、不知道就说不知道。** 自启动没有任何 API 可查，所以它永远是 `UNKNOWN`，不是 `DENIED`。
在权限状态页上把猜测当事实显示，比什么都不说更糟。

通知那一项读的是 `NotificationManager.areNotificationsEnabled()` 而不是
`checkSelfPermission(POST_NOTIFICATIONS)`：后者回答的是「运行时权限授了没」，而真正该回答
的是「通知会不会显示」——用户在系统设置里把本应用的通知关掉时，后者同样为假，且在
Android 13 之前就已经如此。

由 **Task34** 建立。
