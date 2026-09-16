# SaikaiPTT 性能测量手册与记录（Task43）

> `01_PRD §41` 给的是目标值，这份文件给的是**怎么量**和**量出来是多少**。
>
> Claude 没有设备，一个数也测不出来。所以下面每张结果表都是空的，
> 由开发者一边跑一边填。Task43 的验收条件写得很死：
> 「全部指标已实测并记录（**不是估算**）」。
> 估算填进去比空着更糟——空表至少还是诚实的。

---

## 0. 口径：在动手之前先把这条读完

`01_PRD §41.2`：**CPU 全部按单核占用百分比计。** 八核设备上「整机 12.5%」和
「单核 100%」是同一件事，差 8 倍。量错口径的数字比没有数字更有害，因为它看起来像结论。

各命令的输出口径：

| 来源 | 口径 | 换算 |
|---|---|---|
| `dumpsys cpuinfo` | **单核百分比**，多线程进程可以超过 100% | 直接用 |
| `top -b`（toybox） | **单核百分比**，同上 | 直接用 |
| Android Studio Profiler 的 CPU 图 | **整机百分比** | ×核心数 才是单核口径 |
| `/proc/<pid>/stat` 差分 | tick 数 | `(Δutime+Δstime) / HZ / 墙钟秒 × 100%` |

先把设备核心数记下来，后面每张表都要用到：

```
adb shell cat /proc/cpuinfo | findstr /C:"processor"
```

> Android 9 起 `/proc` 带 `hidepid`，`adb shell` 能不能读别的进程的 `/proc/<pid>/`
> 取决于 ROM。**先试一次**，读不到就全程用 `dumpsys cpuinfo`，并在结果表的
> 「来源」列里写明用的是哪个——两种口径虽然一致，但采样窗口不同，混着用会自相矛盾。

---

## 1. 环境

每台设备填一份。

| 项 | 低端机 | 旗舰机 |
|---|---|---|
| 型号 | | |
| SoC | | |
| 核心数 | | |
| Android 版本 / API | | |
| RAM | | |
| 构建类型（**必须 release**） | | |
| APK 版本 / commit | | |
| 电量（测量开始时） | | |
| 是否插电 | | |
| AP 型号 | | |
| 测量日期 | | |

> **必须用 release 构建量。** debug 构建开着完整日志（`CLAUDE.md §26`）、
> 没有 R8、Compose 带调试信息，CPU 和内存都不是要发布的那个东西的数字。

准备：

```
adb shell pm list packages | findstr saikai
adb shell pidof com.saikai.ptt
```

后面所有命令里的 `<PID>` 都是上面这条的输出。**服务重启后 PID 会变，每轮重新取。**

---

## 2. 待机 CPU（目标 < 1% of one core）

条件：服务运行、熄屏、无通信、**10 分钟均值**。

```
:: 1. 启动服务，确认状态
adb shell dumpsys activity services com.saikai.ptt

:: 2. 熄屏
adb shell input keyevent 26

:: 3. 等 10 分钟，然后采样
adb shell dumpsys cpuinfo | findstr saikai
```

`dumpsys cpuinfo` 报的是**自上次采样以来**的均值，第一次读到的窗口不一定是 10 分钟。
稳妥的做法是熄屏后先读一次丢掉（重置窗口），等 10 分钟再读第二次：

```
adb shell dumpsys cpuinfo > nul
:: 等 10 分钟
adb shell dumpsys cpuinfo | findstr saikai
```

如果 `/proc` 能读，这个更准，因为窗口由你自己定：

```
adb shell cat /proc/<PID>/stat
:: 等 600 秒
adb shell cat /proc/<PID>/stat
```

取第 14、15 个字段（utime、stime），差值相加除以 `HZ`（Android 上通常是 100），
再除以 600，乘 100 得到单核百分比。`HZ` 用 `adb shell getconf CLK_TCK` 确认。

| 设备 | 均值 | 峰值 | 来源 | 目标 | 判定 |
|---|---|---|---|---|---|
| 低端 | | | | < 1% | |
| 旗舰 | | | | < 1% | |

> 超标最可能的原因，按可能性排序：某个循环的 interval 被配错（`SaikaiConfig`）、
> 清理循环撞上了采样窗口（每 6 小时一次，`HistoryConfig.cleanupInterval`）、
> 或者 `MulticastLock` 下的广播接收线程在空转。

---

## 3. 发送 CPU（目标 ≤ 15% of one core）

条件：采集 + Opus 编码 + UDP 发送 + 录音写盘，即**按住通话键说话**。

一个人按不住键又敲命令，用一条组合命令：按住通话键持续说 30 秒，中途执行采样。
建议两人操作，或者用 `adb shell input swipe` 模拟长按（坐标要先用
「开发者选项 → 指针位置」量出来）：

```
:: 先清零窗口
adb shell dumpsys cpuinfo > nul
:: —— 这里按住通话键说 30 秒 ——
adb shell dumpsys cpuinfo | findstr saikai
```

| 设备 | 均值 | 峰值 | 来源 | 目标 | 判定 |
|---|---|---|---|---|---|
| 低端 | | | | ≤ 15% | |
| 旗舰 | | | | ≤ 15% | |

> 超标先看 Opus 复杂度。ADR-004 定的是 complexity 3；ADR-007 里
> 「低端设备上的编码耗时」这一项从 Task23 起一直空着，**这轮就是填它的时候**。

编码耗时单独记（如果加了临时计时日志就填，没加就写「未测」）：

| 设备 | 单帧编码耗时均值 | 备注 |
|---|---|---|
| 低端 | | 20 ms 一帧，超过 20 ms 就是跟不上实时 |
| 旗舰 | | |

---

## 4. 接收 CPU（目标 ≤ 12% of one core）

条件：UDP 接收 + jitter buffer + 解码 + 播放 + 录音写盘，即**对端在讲、本机在放**。

方法同上，在接收端采样。

| 设备 | 均值 | 峰值 | 来源 | 目标 | 判定 |
|---|---|---|---|---|---|
| 低端 | | | | ≤ 12% | |
| 旗舰 | | | | ≤ 12% | |

---

## 5. 内存（Java heap < 32 MB，总 PSS < 130 MB）

条件：待机。

```
adb shell dumpsys meminfo com.saikai.ptt
```

看两行：

- `TOTAL PSS` → 总 PSS
- `Dalvik Heap` 的 `Heap Alloc`（KB）→ Java heap

```
adb shell dumpsys meminfo com.saikai.ptt | findstr /C:"TOTAL PSS" /C:"Dalvik Heap"
```

| 设备 | Java heap | 总 PSS | 目标 | 判定 |
|---|---|---|---|---|
| 低端 | | | < 32 MB / < 130 MB | |
| 旗舰 | | | < 32 MB / < 130 MB | |

> ASR 不计入——v1 根本没有（`ADR-011`），所以这一轮的 PSS 就是全部。

---

## 6. 端到端延迟（P50 ≤ 250 ms，P95 ≤ 400 ms）

`07_TestPlan §26` 定的方法，**不能用应用内时间戳代替**：两台设备的时钟没有同步，
算出来的差值没有意义。

方法：发送端播 1 kHz 短脉冲，用**第三个录音设备**同时录下两端的扬声器，
在波形上量起点间隔。至少 30 次，记 P50 / P95 / 最大值。

条件：同一 AP，无明显干扰；两个方向分别测。

| 方向 | 次数 | P50 | P95 | 最大 | 判定 |
|---|---|---|---|---|---|
| 低端 → 旗舰 | | | | | |
| 旗舰 → 低端 | | | | | |

参考构成：`采集缓冲 20ms + 编码 + 网络 + jitter buffer 起播 60ms + 解码 + 播放缓冲`。
显著超标先查 jitter buffer 深度和 `AudioTrack` buffer 大小。

---

## 7. 待机网络（目标 ≤ 1 广播包 / 5 秒 / 设备）

心跳间隔是 `SaikaiConfig.presence.heartbeatInterval`，先确认它的值——
目标就是「不超过配置值所隐含的速率」。

按 uid 统计收发包数：

```
adb shell dumpsys package com.saikai.ptt | findstr userId
adb shell dumpsys netstats detail --uid <UID>
```

取两次快照，间隔 10 分钟，算差值除以 600 秒。

| 设备 | 10 分钟发包数 | 折算速率 | 目标 | 判定 |
|---|---|---|---|---|
| 低端 | | | ≤ 1 / 5 s | |
| 旗舰 | | | ≤ 1 / 5 s | |

> **这项在两台设备上测不出规模问题。** `09_TestReport §3.4` 里挂着的
> 「至少 4 台验证心跳流量与 Peer 表」就是这一条的真正版本：单机发包速率是常数，
> 而每台设备都要**接收**其余所有设备的广播，接收侧负载随设备数线性增长。
> 两台机器量到的数字不能外推到十台。

---

## 8. 启动时间

```
adb shell am force-stop com.saikai.ptt
adb shell am start -W -n com.saikai.ptt/.ui.MainActivity
```

读 `TotalTime`（毫秒）。冷启动测 5 次取中位数，每次之前都 `force-stop`。

| 设备 | 冷启动中位数 | 热启动中位数 | 备注 |
|---|---|---|---|
| 低端 | | | |
| 旗舰 | | | |

> PRD 没给启动时间的目标值，所以这里**只记录不判定**。低端机上超过 2 秒值得看一眼
> ——`SaikaiApplication` 里有没有什么本该懒加载的东西被提前构造了。

---

## 9. 耗电

五种情形对比，每种至少 1 小时，全程不插电：

```
adb shell dumpsys batterystats --reset
:: 跑够时长
adb shell dumpsys batterystats com.saikai.ptt > batterystats.txt
```

| 情形 | 时长 | 掉电百分比 | 折算 %/小时 | 备注 |
|---|---|---|---|---|
| 应用已停止（基线） | | | | |
| 空闲前台 | | | | |
| 空闲后台（熄屏） | | | | |
| 持续 PTT | | | | |
| ASR 开启 | — | — | — | **N/A，v1 无 ASR（ADR-011）** |

> 有意义的是**差值**，不是绝对值：「空闲后台 − 应用已停止」才是这个 app 自己的开销。
> 系统本身的待机耗电跟设备和 ROM 有关，混在一起就没法比较。

---

## 10. 长时间稳定性（8~24 小时）

低端设备后台运行，期间周期性 discovery / heartbeat / PTT / history。

每小时采一次：

```
adb shell dumpsys meminfo com.saikai.ptt | findstr /C:"TOTAL PSS" /C:"Dalvik Heap"
adb shell ls /proc/<PID>/fd | find /c /v ""
adb shell dumpsys activity services com.saikai.ptt | findstr /C:"CommunicationService"
```

| 小时 | PSS | Java heap | fd 数 | 服务存活 | crash / ANR |
|---|---|---|---|---|---|
| 0 | | | | | |
| 1 | | | | | |
| … | | | | | |
| 24 | | | | | |

结束后：

```
adb logcat -d | findstr /C:"FATAL" /C:"ANR" /C:"saikai"
```

判定：**无 crash、无 ANR、PSS 与 fd 数最终趋于平稳**（可以波动，不能单调上升）。

> fd 数是 socket 泄漏的直接证据。这个应用只该有两个 UDP socket
> （ADR-002：control 和 voice），长跑之后 fd 数应该回到起点附近。
> 读不到 `/proc/<PID>/fd` 的话用 `adb shell dumpsys netstats` 里的 socket 列表，
> 或者在结果表里写明未测。

---

## 11. 内存泄漏

前台 → 后台 → 前台，循环 20 次，每 5 次采样一次。

```
adb shell am start -n com.saikai.ptt/.ui.MainActivity
adb shell input keyevent 3
:: 重复
adb shell dumpsys meminfo com.saikai.ptt
```

采样前先强制 GC：`adb shell am broadcast -a com.android.internal.intent.action.REQUEST_SHUTDOWN` **不要用**，
用 Android Studio Profiler 的 GC 按钮，或者直接多测几轮看趋势。

重点看这些有没有累积：Activity、Service、`AudioRecord`、`AudioTrack`、
`DatagramSocket`、悬浮窗 View、协程。

| 循环次数 | PSS | Java heap | Activity 实例数 | 备注 |
|---|---|---|---|---|
| 0 | | | | |
| 5 | | | | |
| 10 | | | | |
| 15 | | | | |
| 20 | | | | |

Activity 实例数：

```
adb shell dumpsys activity com.saikai.ptt | findstr /C:"MainActivity"
```

> 旋转屏幕那一类泄漏在这里也会露出来。`MainActivity` 的实例数必须回到 1。

---

## 12. 结果汇总

全部填完之后，这张表进 `09_TestReport.md §7`：

| 指标 | 目标 | 低端实测 | 旗舰实测 | 判定 |
|---|---|---|---|---|
| 待机 CPU | < 1% 单核 | | | |
| 发送 CPU | ≤ 15% 单核 | | | |
| 接收 CPU | ≤ 12% 单核 | | | |
| Java heap | < 32 MB | | | |
| 总 PSS | < 130 MB | | | |
| 延迟 P50 | ≤ 250 ms | | | |
| 延迟 P95 | ≤ 400 ms | | | |
| 待机网络 | ≤ 1 包 / 5 s | | | |
| 长时间稳定性 | 无 crash / ANR / 内存增长 | | | |

---

## 13. 没达标怎么办

Task43 的验收条件给了两条路，**两条都是合法的**：

1. **优化**，然后重测。
2. **修订目标值并说明理由**，写进新的 ADR。

第二条不是认输。`01_PRD §41.1` 本身就是这么来的——原来那个「约 30 MB」的目标
诱导了无效优化，于是被拆成两个可测量的口径。一个测不达标又改不动的目标，
留着只会让每一轮测试都挂一个假的红灯。

但有一条底线，`CLAUDE.md §31.2`：**不得为了达到数字而牺牲稳定性**，
而且「代码看起来没问题」不能当成测量结果。改目标要有实测数据支撑，不能靠推理。

---

## 附：这份手册目前的状态

**一个数都还没测。** 上面全部表格为空，不是因为漏填，是因为 Task43 的执行部分
需要两台真机、一个第三方录音设备和最长 24 小时的连续运行，这些都不在 Claude 这一侧。

跑完第一轮之后，把 `dumpsys cpuinfo`、`dumpsys meminfo`、`dumpsys netstats` 的
**原始输出**贴回来，我可以据此写一个采样脚本，把重复的部分自动化掉——
但要先看过真实输出的格式，照着猜写出来的脚本和没写差不多。
