# core.common

工具类型、Result 与结构化错误模型（`docs/02_Architecture.md` §34）。

被 core 内其它 package 依赖，自身不依赖它们。

由 Task06 起逐步填充。

## Subsystems（Task30）

`subsystemScope` / `subsystemFailures` / `repeatEvery`。

放在这里而不是放在服务旁边，理由与 `LifecycleStep` 相同：需要它的那些组件（传输、发现、
心跳、会话机）所在的层，服务可以知道，而它们不允许知道服务。

存在的理由只有一条 Android 事实：**协程里未捕获的异常会结束进程**。`SupervisorJob`
不能阻止这件事——它只阻止失败在兄弟之间传播。本应用每一个子系统都跑在 supervisor 里，
所以在加上 `CoroutineExceptionHandler` 之前，任何一处抛异常都会带走整个进程。
