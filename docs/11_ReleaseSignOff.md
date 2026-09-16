# SaikaiPTT 发布前签核（Task44）

> 按 `08_ReleaseChecklist §57` 的八项逐条记录。
>
> **结论写在最前面：这不是 Release Candidate。**
> 不是因为发现了缺陷，而是因为 `§55` 要求通过的那批测试**还没有人跑过**，
> 而没跑过不等于通过。下面每一项都注明了是「已验证」「待开发者执行」还是「阻塞」。

---

## 0. 结论

| | |
|---|---|
| 版本 | `0.1.0` / versionCode `1` |
| 判定 | **不是 Release Candidate** |
| 原因 | 核心阻塞测试未执行；性能指标一个未测 |
| 可以做的 | 打包一个内部测试用的构建，发给测试者跑 §9 的清单 |

`08_ReleaseChecklist §55` 的规则是：核心阻塞测试全部通过、无未解决的严重
Crash / 数据丢失 / PTT 可靠性问题、已知问题已记录且未被隐藏。
第三条成立，前两条**状态未知**。

---

## 1. Build

| 项 | 状态 | 依据 |
|---|---|---|
| Debug 构建 | **待执行** | `.\gradlew.bat :app:assembleDebug` |
| Release 构建 | **待执行** | `.\gradlew.bat :app:assembleRelease` |
| 不依赖开发机路径 | **已验证** | 构建脚本里唯一的绝对路径是 `OPUS_SOURCE_DIR`，由 `rootProject.layout` 推导，不是硬编码 |
| 不依赖开发 WiFi / 本地服务 / Mock | **已验证** | 全仓库无 `TODO`、`FIXME`、mock、fake、stub（Task44 静态扫描） |
| 签名配置 | **本次补上** | 见下 |
| 可重复构建 | **已验证（配置层）** | 所有版本钉死在 `gradle/libs.versions.toml` 与 wrapper，见 README「工具链」表 |

### 签名

本次新增 `app/build.gradle.kts` 的 `signingConfigs`，从 `keystore.properties` 读取。
该文件与 `*.jks` / `*.keystore` 均已在 `.gitignore` 中（`CLAUDE.md §43.1` 禁止提交）。
模板见 `keystore.properties.example`。

**文件不存在时 `assembleRelease` 仍然成功，产出未签名 APK。** 这是故意的：
因为缺一个 keystore 就构建失败，会让人跑不了 Lint 和 release 独有的压缩规则，
而在还没有东西要发布的阶段，那两样恰恰是 release 构建最有用的部分。

> 发布前必须确认：生成了正式 keystore，`keystore.properties` 已填，
> 且 **keystore 与密码存在了这台机器之外的地方**——丢了就意味着所有已安装的版本
> 永远无法升级。

### 版本

| 项 | 值 | 位置 |
|---|---|---|
| versionName | `0.1.0` | `app/build.gradle.kts` |
| versionCode | `1` | 同上 |
| applicationId | `com.saikai.ptt` | 同上 |

---

## 2. Tests

| 层 | 结果 |
|---|---|
| `:core` 单元测试 | **Pass**，425 / 425 |
| `:app` 单元测试 | **待执行完整版**；沙箱可跑部分 31 / 31 Pass |
| 仪器测试 | **Pass**，真机执行 |
| Android Lint | **待执行**（`abortOnError = true`，blocker 会直接断构建） |
| 真机功能测试 | **未开始** |
| 性能测试 | **未开始** |

明细见 `docs/09_TestReport.md`。

---

## 3. Devices

| 项 | 状态 |
|---|---|
| 低端参考机（MTK P22 / Android 11 / 4 GB） | **未测** |
| 现代旗舰 | **未测** |
| 两机联调 | **未测** |
| 四机规模验证 | **阻塞**，手上只有 2 台 |

仪器测试跑过的那台设备型号由开发者补入 `09_TestReport.md §1`。

---

## 4. Known Issues

十条，全部记录在 `docs/09_TestReport.md §6`，没有隐藏项。其中本次处理掉两条：

| # | 问题 | 本次 |
|---|---|---|
| 6 | 悬浮窗日志走 `LogCategory.LIFECYCLE`，INFO 会进 release 构建 | **已修**，改为 `LogCategory.OVERLAY`（不在 `RELEASE_DEFAULT` 集合里） |
| 7 | 三处未使用的 import | **已修**，全仓库扫描后为 0 |
| 10 | README / 隐私说明仍写着「唯一联网例外是字幕模型下载」 | **已修**，改为「不访问互联网，一次都不」 |

剩余的发布阻塞项：

| # | 问题 | 为什么必须在发布前解决 |
|---|---|---|
| 1 | 三台以上设备的强插竞态与心跳流量从未在真机验证 | 接收侧负载随设备数线性增长，两台机器量到的数不能外推 |
| 3 | 未内置 Noto 字体子集，低端机上缅甸语/孟加拉语可能是豆腐块 | 五种语言是产品承诺，不是可选项 |
| 2 | 缅甸语、孟加拉语译文未经母语者审阅 | 同上 |

非阻塞但应跟踪：#4（Room 版本未实证）、#5（`disallowKotlinSourceSets` 绕过）、
#8（通知行为实测表为空）、#9（Opus 编码耗时未测）。

---

## 5. Performance

**一个指标都没测。**

手册与空记录表在 `docs/10_PerformanceMeasurement.md`。八项目标全部空白：
待机 CPU、发送 CPU、接收 CPU、Java heap、总 PSS、延迟 P50 / P95、待机网络。
长时间稳定性与内存泄漏测试同样未做。

`Task43` 的验收条件是「实测并记录，不是估算」，所以这里不填任何推算值。

---

## 6. Privacy

| 检查项 | 结果 | 依据 |
|---|---|---|
| 无云端音频 | **通过** | 语音只发往用户选中的对端的单播地址 |
| 无云端 ASR | **通过** | v1 没有 ASR（`ADR-011`） |
| 无云端历史 | **通过** | Room 数据库在应用私有目录，无同步代码 |
| 无非预期遥测 | **通过** | 无分析 SDK，无崩溃上报，依赖里没有任何网络客户端库 |
| 录音只存本地 | **通过** | `RecordingPaths` 全部相对于 `filesDir`；`LocalRecordingFiles.delete` 拒绝解析到 `records/` 之外的路径 |
| 唯一联网路径 | **不适用** | 原检查项假设存在一条模型下载路径。v1 **没有任何联网路径**——`ADR-011` 取消了它 |
| 系统 backup 行为 | **通过** | `allowBackup="false"` + `dataExtractionRules`，通信历史与录音不会被带到别的设备 |

`INTERNET` 权限保留，且**不构成联网证据**：Android 开任何 socket 都要它，
包括本应用赖以工作的局域网 UDP。Manifest 的注释已改写说明这一点。

---

## 7. Release Logging

| 检查项 | 结果 | 依据 |
|---|---|---|
| DEBUG 日志关闭 | **已验证（配置层）** | `LoggingConfig.release()` 的 `minLevel` 高于 DEBUG；`SaikaiApplication` 按 `BuildConfig.DEBUG` 选择 |
| 详细 packet logging 关闭 | **已验证（配置层）** | `NETWORK` / `PROTOCOL` / `AUDIO` 不在 `LogCategory.RELEASE_DEFAULT` 里，该集合只有 `LIFECYCLE` `SERVICE` `PERMISSION` `STORAGE` |
| Debug 面板不进 release | **已验证** | 诊断页由 `BuildConfig.DEBUG` 控制（`MainActivity` 与 `SettingsScreen` 各一处） |
| 无测试按钮 / Mock / Fake | **已验证** | 静态扫描为 0 |
| ERROR 不被分类过滤 | **设计如此** | `LoggingConfig.isEnabled` 让 ERROR 绕过类别过滤——现场报告「它不工作了」时需要看得见原因 |

> 「配置层已验证」的意思是代码逻辑正确，**不等于在 release APK 上看过 logcat**。
> 发布前应当装一次 release 构建，跑一遍 PTT，确认 logcat 里没有逐包日志。

---

## 8. 还需要做什么

按顺序：

1. `.\gradlew.bat :app:assembleRelease` —— 确认能构建、Lint 无 blocker、记录 APK 体积
2. 用 `readelf` 确认打进 APK 的 `libopus.so` 是 16 KB 对齐（命令见下）
3. 装 release 构建，跑一遍 logcat 确认无逐包日志
4. 两台设备跑完 `09_TestReport §3.2` 与 `§3.3`
5. 低端机跑完 `10_PerformanceMeasurement` 的八项指标
6. 找齐四台设备，闭掉已知问题 #1
7. 找母语者审阅缅甸语与孟加拉语，并在低端机上确认字体（已知问题 #2 #3）
8. 长时间稳定性测试（8~24 小时）
9. 回到本文件，把「待执行」全部换成实际结果，重新判定

### 16 KB 对齐的验证命令

```
unzip -o app\build\outputs\apk\release\app-release.apk -d build\apk-check
readelf -lW build\apk-check\lib\arm64-v8a\libopus.so | findstr LOAD
readelf -lW build\apk-check\lib\armeabi-v7a\libopus.so | findstr LOAD
```

每个 LOAD 段的 Align 必须是 `0x4000`（16384）。`0x1000` 是 4 KB，不合格。

源头是 `app/src/main/cpp/CMakeLists.txt` 里已经设了
`-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384`，
但**链接参数设了不等于产物对了**——ADR-011 里判 Vosk 不合格用的就是这条标准，
对自己的产物要用同一把尺子。

### ABI 清单

`abiFilters = ["arm64-v8a", "armeabi-v7a"]`。不含 x86 / x86_64：
本产品依赖麦克风与局域网 UDP，不是模拟器或 ChromeOS 的目标场景，
多打一个 ABI 只会增大安装包（lint 的 `ChromeOsAbiSupport` 已据此关闭）。

APK 体积：**待记录**。

---

## 附：本次签核里 Claude 能确认和不能确认的

**能确认的**（静态可查，已全部查过）：签名配置、版本号、日志配置、调试面板的
构建类型门禁、Mock / TODO 扫描、备份配置、数据库无破坏性迁移、录音只写私有目录、
ABI 清单、依赖中无网络客户端库、五语言字符串完整。

**不能确认的**（需要构建产物或真机）：构建是否成功、Lint 结果、APK 体积、
`.so` 实际对齐、release 构建的 logcat、以及**全部**功能与性能测试。

这份文件里凡是标「已验证」的都属于第一类。第二类一律标「待执行」，一个都没有
提前判定为通过。
