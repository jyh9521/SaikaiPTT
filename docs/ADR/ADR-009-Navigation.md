# ADR-009 — 页面导航：手写状态，不引入 Navigation 库

Status: Accepted
Date: 2026-09-16

## Context

Task32 之前全应用只有一个页面，导航问题不存在。Task33 引入了第二个和第三个：

- **欢迎页**（首次运行，`04_UI_UX §6`、`§7`）
- **名前管理页**（`§8`）

后面还会有设置页（Task35）、通信记录列表与详情（Task37、Task39）。也就是说，这里选定
的机制会被之后至少四个 Task 继承，属于 `CLAUDE.md §36` 说的「模块边界与公共接口」范畴，
所以先记 ADR。

`04_UI_UX §5` 给出了建议的一级结构（Home / History / Settings），`§51` 只要求单向数据流，
**没有指定任何导航实现**。

## Decision

**用一个 `enum class Destination` 存在 `MainActivity` 的 `rememberSaveable` 里，不引入
`androidx.navigation:navigation-compose`。**

配套三条规则：

1. **「有没有用户名」不是导航，是闸门。** 没有用户名时欢迎页就是整个应用——那不是一个
   用户「导航到」的地方，也没有可以返回的上一页。它由 `NameGate` 决定，与 `Destination`
   正交。
2. **返回键由页面自己用 `BackHandler` 处理**，从内到外：先关对话框，再关编辑器，最后
   才离开页面。`§46` 的未保存提示只有在这一层才能实现——导航库拦不住一个只存在于
   ViewModel 里的草稿。
3. **从 Home 按返回键就是退出界面，不停止服务**（`§45`）。前台服务是 started 而非
   bound，Activity 结束不影响它，这条是现状而非新增代码。

## Rationale

**页面数量不构成引入导航库的理由。** v1 全部页面加起来是 Home / 名前 / 设置 / 记录列表 /
记录详情，五个，其中四个是从 Home 出发的单层跳转。`navigation-compose` 解决的是深层
嵌套图、类型安全参数传递、深链接与多返回栈——这个应用一个都没有。`CLAUDE.md §32` 要求
逐条论证依赖：平台已提供的能力（`rememberSaveable` + `BackHandler`）足够，那么这个依赖
就论证不过去。

**进程被杀后的恢复由闸门负责，不由导航负责。** `ADR-005 §5` 已经写明本应用不承诺在进程
被杀后恢复。用户回来时看到 Home 是正确行为，不是缺陷——真正必须正确的是「这台设备叫
什么名字」，而那来自 DataStore，与当时停在哪一页无关。

**`rememberSaveable` 覆盖了真正常见的那次状态丢失：旋转。** enum 是 `Serializable`，
自动保存器直接支持。

**被否决的方案：**

- **`navigation-compose`**：见上。APK 体积与一个需要被读懂的额外抽象层，换来一个此处
  不存在的问题的解法。
- **每个页面一个 Activity**：多个 Activity 意味着多份 `attachBaseContext` 的语言包装
  （`app/locale/AppLocale.kt`）、多份生命周期、以及 Android 11 上真实的启动开销。
- **把当前页面放进 ViewModel**：ViewModel 里放「现在显示哪一页」会让它同时持有界面
  状态与界面结构，`§4.2` 的职责表里没有这一条。

## Consequences

- 新增一个页面 = 在 `Destination` 里加一个值，在 `when` 里加一个分支。编译器会指出
  所有需要处理的地方。
- **没有返回栈。** 目前每个页面都只能回到 Home，这是真实的结构。哪天出现「A → B → C
  且必须逐级返回」，就需要一个栈；到那时应当新建 ADR 重新评估，而不是在 `Destination`
  上叠加特例。
- **深链接需要单独处理。** Task36 的悬浮窗与 Task31 的通知都要能拉起界面
  （`§18.1`、`§18.3`）。它们通过 Intent extra 指定落地页，由 `MainActivity` 在
  `onCreate` / `onNewIntent` 翻译成 `Destination` 初值——这在有没有导航库时都要写。
- 导航不产生任何可测状态，因此没有对应的单元测试。页面切换属于 Task42 的手工回归项。
