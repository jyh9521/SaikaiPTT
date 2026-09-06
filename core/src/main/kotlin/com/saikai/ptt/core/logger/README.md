# core.logger

统一日志抽象。core 不依赖 Android，因此这里只有接口与等级/分类定义，
Android 侧的输出实现位于 `app`，由 DI 注入。

由 **Task06** 填充。
