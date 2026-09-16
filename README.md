# SaikaiPTT / 西海PTT

Professional Offline LAN Push-To-Talk for Android.

同一个 WiFi 局域网内的一对一数字对讲机。不需要互联网、不需要服务器、不需要账号。

- Android 11 (API 30) 及以上
- 一对一 UDP Unicast 语音，按住讲话、松开结束
- 自动发现同一局域网内的设备
- 后台接收、悬浮窗提醒
- 本地通信记录、录音回放、搜索与自动清理
- 五种界面语言：日本語 / 简体中文 / English / မြန်မာ / বাংলা

状态：**功能实现完成，尚未发布。** Task01~44 全部实现完毕，自动化测试全绿；
但真机上的两机联调、性能测量与长时间稳定性测试**尚未进行**，
因此这还不是 Release Candidate。当前状态见 `docs/09_TestReport.md` 与
`docs/11_ReleaseSignOff.md`。

**离线日语字幕不在 v1 中。** 引擎复核没通过，原因与依据见
`docs/ADR/ADR-011-ASR-Engine-Review.md`。数据模型保留了字幕字段，
将来补上识别引擎不需要改数据库。

## 文档

```text
docs/00_MasterPrompt.md      产品与工程最高原则
docs/01_PRD.md               产品需求
docs/02_Architecture.md      软件架构
docs/03_Protocol.md          网络协议
docs/04_UI_UX.md             界面与交互
docs/05_DataModel.md         数据模型
docs/06_DevelopmentPlan.md   开发计划（Task 01~44）
docs/07_TestPlan.md          测试计划
docs/08_ReleaseChecklist.md  发布检查
docs/09_TestReport.md        测试执行记录（哪些测了、哪些没测）
docs/10_PerformanceMeasurement.md  性能测量手册与记录表
docs/11_ReleaseSignOff.md    发布前签核
docs/ADR/                    架构决策记录（与 docs/ 冲突时以 ADR 为准）
tasks/                       具体实现任务
```

## 已知限制

这些是设计上已知并接受的限制，不是缺陷：

- **企业级 AP 的广播隔离**：设备发现使用 UDP 广播。若 AP 开启 AP Isolation 或禁用广播转发，设备之间无法互相发现。v1 不提供手动输入 IP 的补救手段。
- **发送需要界面可见**：Android 14 及以上禁止从后台启动麦克风类型的前台服务，因此按住 PTT 讲话要求应用界面处于可见状态。从悬浮窗或通知点击会先打开应用。**接收不受此限制**，后台、熄屏、锁屏均可正常接收。
- **进程被系统杀死后不自动恢复通信**：Android 12 及以上禁止从后台启动前台服务。本地数据不会损坏，下次打开应用时恢复。建议将应用加入电池优化白名单。
- **没有语音字幕**：v1 不含离线日语识别。可用的引擎里，通过 16 KB 页对齐要求的那个
  没有体积合理的日语模型，体积合理的那个过不了对齐要求。依据见 `docs/ADR/ADR-011`。

## 隐私

录音与通信记录只保存在本机应用私有目录。不上传、不云同步、无遥测、无分析 SDK。

**v1 不访问互联网，一次都不。** 应用产生的网络流量只有一种：局域网内发往对端设备
或本网段广播地址的对讲协议包。

`AndroidManifest.xml` 里的 `INTERNET` 权限**不是**联网的证据：Android 开任何 socket
都需要它，包括这个应用赖以工作的局域网 UDP。

系统备份已关闭（`android:allowBackup="false"`），通信记录与录音不会被带到别的设备上。

## 安装

没有应用商店发布。安装方式是侧载：

```
adb install -r app-release.apk
```

或者把 APK 传到手机上用文件管理器打开，系统会要求允许「安装未知来源的应用」。

所有需要通话的设备都要装同一个版本，并且**连到同一个 WiFi 网段**。
手机热点也可以，把一台设备开成热点、其余连上去即可。

## 权限说明

首次启动会有一轮引导。每项权限的用途：

| 权限 | 必需 | 用途 | 拒绝的后果 |
|---|---|---|---|
| 麦克风 | **是** | 采集语音 | 完全无法讲话；仍可接收 |
| 通知 | 建议 | 前台服务的常驻通知（Android 13+ 需要运行时授予） | 服务仍在跑，但系统可能更早回收它 |
| 悬浮窗 | 可选 | 在别的应用之上显示通话状态 | 退化为通知提示，功能不受影响 |
| 电池优化白名单 | 建议 | 避免系统在后台杀掉接收服务 | 熄屏一段时间后可能收不到 |
| 自启动（部分国产 ROM） | 建议 | 开机后恢复接收 | 每次重启要手动打开一次应用 |

自启动没有统一的系统接口，各家 ROM 的入口都不一样，所以引导页给的是文字说明而不是按钮。

应用**不申请**：定位、通讯录、外部存储、相机、电话。

## 使用方法

1. 首次打开先设一个名字。这个名字会随通话一起发给对方，对方不需要在本地存过它。
2. 主页会自动列出同一局域网内的其他设备。不需要输入 IP。
3. 点一下要通话的设备把它选为目标。
4. **按住**大按钮讲话，**松开**结束。半双工，同一时刻只有一个人在讲。
5. 对方讲话时自动出声，不需要接听。
6. 历史页面可以回放录音、搜索、收藏、删除。保留期默认 7 天，可在历史设置里改。

对方正在通话时会收到「对方通话中」。如果对方在自己的设置里打开了「允许被打断」，
那么新的呼叫会顶替掉他当前的通话——**这是接收方的开关，呼叫方无法单方面强插**。

## 构建

### 首次 clone 之后

```bash
git submodule update --init --recursive
```

libopus 以 git submodule 引入并锁定在 v1.6.1（见 `docs/ADR/ADR-007`）。忘了执行的话，
CMake 会在配置阶段直接失败并打印这条命令。

还需要在 SDK Manager 里安装 **NDK**（建议 r28 及以上）。CMake 由 AGP 按需下载。

### 工具链

所有版本显式钉死，不随开发机浮动。这是 `docs/08_ReleaseChecklist.md` §51 可重复构建要求的前提。

| 组件 | 版本 | 定义位置 |
|---|---|---|
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| compileSdk | 37 | `gradle/libs.versions.toml` |
| targetSdk | 36（Android 16） | `gradle/libs.versions.toml` |
| minSdk | 30（Android 11） | `gradle/libs.versions.toml` |
| Java 源码/目标兼容性 | 11 | `app/build.gradle.kts` |
| Gradle 守护进程 JVM | toolchain 25 | `gradle/gradle-daemon-jvm.properties` |
| 参考 IDE | Android Studio Quail 4 / 2026.1.4 | — |

`gradle-daemon-jvm.properties` 已纳入版本控制：它固定了执行构建的 JVM 版本，并让 Gradle 在缺失时自动下载对应 JDK，克隆仓库的人无需手动配置 JDK。

AGP 9 内置 Kotlin 支持，因此**不需要**单独的 `org.jetbrains.kotlin.android` 插件。

`compileSdk` 与 `targetSdk` 有意不同，这是常规配置而非疏漏：

- **compileSdk 37** 决定代码能编译哪些 API。保持在最新，让依赖可以持续升级——AndroidX 的库越来越要求用当前 SDK 编译。改动它**不影响任何运行时行为**。
- **targetSdk 36** 决定应用主动接受哪些运行时行为变更。这一项才会影响用户，因此只在真机验证过之后才推进。发布前复核见 `docs/08_ReleaseChecklist.md` §3。

构建 `compileSdk 37` 需要安装对应 SDK 平台（Android Studio → Tools → SDK Manager → SDK Platforms → Android 37）。

### 依赖清单

刻意保持最小。新增依赖前须按 `.claude/CLAUDE.md` §32 评估：平台是否已提供、维护状态、APK 与内存开销、Android 11 兼容性。

| 依赖 | 配置 | 用途 |
|---|---|---|
| `androidx.compose:compose-bom` | implementation | 统一 Compose 各构件版本 |
| `androidx.activity:activity-compose` | implementation | `ComponentActivity` + `setContent` |
| `androidx.compose.material3:material3` | implementation | Material 3 组件 |
| `androidx.compose.ui:ui` | implementation | Compose 核心 |
| `androidx.compose.ui:ui-graphics` | implementation | 颜色与绘制 |
| `androidx.compose.ui:ui-tooling-preview` | implementation | `@Preview` 注解 |
| `junit:junit` | testImplementation | JVM 单元测试 |
| `androidx.compose.ui:ui-test-junit4` | androidTestImplementation | Compose 仪器测试 |
| `androidx.test.espresso:espresso-core` | androidTestImplementation | 仪器测试 |
| `androidx.test.ext:junit` | androidTestImplementation | 仪器测试 |
| `androidx.compose.ui:ui-tooling` | **debugImplementation** | 预览渲染，不得进入 Release |
| `androidx.compose.ui:ui-test-manifest` | **debugImplementation** | 测试 Activity，不得进入 Release |

Task03 移除了模板自带但零引用的 `androidx.core:core-ktx` 与 `androidx.lifecycle:lifecycle-runtime-ktx`，待实际需要时再由对应任务加回。

依赖版本以 `gradle/libs.versions.toml` 为准。**升级任何依赖前先确认它对 `compileSdk` 的要求**——AndroidX 库会在 AAR 元数据里声明最低 compileSdk，不满足时构建在 `checkAarMetadata` 阶段就会失败。

### 构建类型

| | Debug | Release |
|---|---|---|
| 代码/资源压缩 | 关 | **开**（AGP 9 中 `optimization { enable = true }` 同时覆盖代码与资源） |
| ProGuard 规则 | — | `proguard-android-optimize.txt` + `app/proguard-rules.pro` |
| 调试工具依赖 | 有 | 无 |

### 原生库

APK 里**只有一个** `.so`：`libsaikaiopus.so`。libopus 编成静态库链接进它，`ANDROID_STL=none`
避免额外打包 `libc++_shared.so`。

这不是体积优化，是 **16 KB page size 对齐**（Android 15+ 要求，Google Play 自 2027-02-01
起强制）的策略本身：对齐要求作用于 APK 里的每一个共享库，而我们能控制链接参数的只有
自己链接的那些。详见 `docs/ADR/ADR-007`。

- ABI 限定 `arm64-v8a` + `armeabi-v7a`
- `packaging.jniLibs.useLegacyPackaging = false`
- 链接参数显式带 `-Wl,-z,max-page-size=16384`（NDK r28+ 默认如此，写出来是为了 r27 也对，
  并且让这项要求出现在负责满足它的那个文件里）
- 发布前逐一验证对齐，见 `docs/08_ReleaseChecklist.md` §6.2

Task41（Vosk）会引入第二个原生依赖，届时同样按 ADR-007 的原则处理。

### 构建命令

```bash
gradlew assembleDebug        # Debug APK
gradlew assembleRelease      # Release APK（未签名）
gradlew testDebugUnitTest    # JVM 单元测试
gradlew lintDebug            # 静态检查
gradlew connectedDebugAndroidTest   # 仪器测试，需要连接设备
```

连接了多台设备时 `connectedDebugAndroidTest` 会对每一台都跑一遍；只想跑其中一台就先
`adb devices` 拿到序列号，再用 `-Pandroid.testInstrumentationRunnerArguments` 或直接
断开另一台。

## 开发

增量开发，一个 Task 一个 commit。执行任务前先阅读 `.claude/CLAUDE.md`、相关 `docs/` 与该任务列出的 ADR。

开发路线见 `docs/06_DevelopmentPlan.md`（Task 01~44，编号即执行顺序）。
