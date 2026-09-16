# app.usecase

一个业务动作 = 一个类型（`docs/02_Architecture.md` §4.3）。

存在的理由只有一条：**ViewModel 不得直接访问 network / audio / storage 的实现**
（Task32 的硬性要求，`§4.2` 的禁止清单）。这一层就是那条边界。它上面是「用户想做什么」，
下面是「这台设备怎么做到」，而 UseCase 本身不知道下面用的是 UDP 还是别的什么。

**观察类的 UseCase 返回 Flow，不返回快照。** 设备列表、服务状态、会话状态都是持续变化的
事实，返回一次性的值意味着调用方要自己决定何时再问一次——而它没有任何依据可以决定。

**选中目标不是 UseCase。** `§4.3` 的清单里有 `SelectPeer`，但在本实现里它不触碰任何
系统资源，只是改一个 ViewModel 里的字段。给它包一层只会多一个需要被读懂的间接层。

**每个页面一个 bundle，不是一个全局 bundle。** `HomeUseCases` 与 `UserUseCases` 是分开的：
Home 不删用户，名前页不开会话，合成一个只会让两边都拿到对方的权限。

**规则写在下面，不写在这里。** 用户名的校验、最后一个不能删、当前用户不能删，全部在
`core.domain.SettingsLocalUserRepository` 里，这一层不复述。唯一的例外是
`SwitchActiveUser`：「通话中不能换名字」（`04_UI_UX §54`）需要同时看到用户仓库和会话状态，
而仓库不该知道会话存在——它是纯 Kotlin，给它这个依赖就等于「存一个名字需要服务在跑」。

**只放已经能做的事。** `HistoryUseCases` 里没有搜索、没有删除、没有清理——那是 Task40 的，
写一个转手就返回空的 `SearchHistory` 比不写更糟（`CLAUDE.md` §38）。

由 **Task32** 建立，**Task33** 加入用户名相关动作，**Task34** 权限，**Task35** 设置，
**Task39** 历史读取与已读/收藏，后续任务按需扩充（历史管理、ASR）。
