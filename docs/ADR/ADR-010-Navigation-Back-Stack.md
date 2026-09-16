# ADR-010 — 导航加入返回栈（仍不引入 Navigation 库）

Status: Accepted
Date: 2026-09-16
Amends: ADR-009（其余部分不变，仍为 Accepted）

## Context

`ADR-009` 明确写过：

> **没有返回栈。** 目前每个页面都只能回到 Home，这是真实的结构。哪天出现「A → B → C
> 且必须逐级返回」，就需要一个栈；到那时应当新建 ADR 重新评估，而不是在 `Destination`
> 上叠加特例。

Task35 就是那一天。设置页出现后，结构变成：

```text
Home → 設定 → 名前の管理
Home → 設定 → 権限の状態
Home → 設定 → 言語
Home → 権限の状態        （Home 上的权限提示行，Task34）
```

同一个页面（名前 / 権限）现在有两个入口，返回目标取决于**从哪来**。一个 `Destination`
变量无法表达这件事：把返回写死成 Home，从设置进去的用户会被弹回主页；写死成设置，从
Home 的提示行进去的用户会被丢进一个他没打开过的页面。

## Decision

**把当前页面从一个 `Destination` 换成一个 `List<Destination>`，存在同一个
`rememberSaveable` 里。仍然不引入 `androidx.navigation:navigation-compose`。**

```kotlin
var stack by rememberSaveable { mutableStateOf(listOf(Destination.HOME)) }
val destination = stack.last()
val open: (Destination) -> Unit = { stack = stack + it }
val goBack: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
BackHandler(enabled = stack.size > 1) { goBack() }
```

全局 `BackHandler` 在所有页面**之前**注册，因此页面自己的 `BackHandler`（编辑器、
确认框）注册更晚、优先级更高——先关掉页面内打开的东西，再离开页面。`04_UI_UX §46`
的未保存提示依赖这个顺序。

## Rationale

**ADR-009 的论证成立的部分没有变化。** 那份 ADR 拒绝导航库的理由是：没有嵌套图、没有
类型化参数、没有深链接、没有多返回栈。这四条今天依然成立。变的只有第五条——「每个页面
都只能回到 Home」——而它对应的是四行代码，不是一个依赖。

**换成列表没有引入新概念。** `rememberSaveable` 与 `Serializable` 的处理方式不变
（`ArrayList` 与枚举都是 `Serializable`），旋转恢复的路径不变，新增页面仍然是「在
`Destination` 里加一个值、在 `when` 里加一个分支」。区别只是「当前是哪一页」从
`destination` 变成 `stack.last()`。

**明确拒绝的替代方案：在 `Destination` 上加 `returnTo` 字段。** 这正是 ADR-009 说的
「在 `Destination` 上叠加特例」。它在深度为 2 时能用，在深度为 3 时就要开始想
`returnTo` 的 `returnTo`，而那时它已经是一个写坏了的栈。

**仍然拒绝 `navigation-compose`。** 它解决的问题依旧不存在，而它带来的抽象层（路由
字符串、NavGraph、NavController 的生命周期）要求读代码的人先理解一套框架才能回答
「按返回键会发生什么」。现在这个问题的答案是四行 Kotlin。

## Consequences

- 返回栈没有去重：从设置进名前页、再从名前页（未来）进设置，会叠出重复项。今天不存在
  这样的环路；出现时用「入栈前先把已有的同一项移除」处理，仍然是一行。
- 栈不跨进程恢复。`ADR-005 §5` 已写明本应用不承诺进程被杀后恢复，重新打开落在 Home
  是正确行为。`rememberSaveable` 覆盖的是旋转，那才是真正常见的那次丢失。
- Task36 的悬浮窗与 Task31 的通知做深链接时，用 Intent extra 指定落地页，
  `MainActivity` 把它翻译成初始栈（例如 `[HOME, SETTINGS]`），这样按返回键的行为和
  用户自己点进去时一致。
- 导航依旧不产生可测状态，页面切换与返回属于 Task42 的手工回归项。
