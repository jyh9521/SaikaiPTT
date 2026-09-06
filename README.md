# SaikaiPTT / 西海PTT

Professional Offline LAN Push-To-Talk for Android.

同一个 WiFi 局域网内的一对一数字对讲机。不需要互联网、不需要服务器、不需要账号。

- Android 11 (API 30) 及以上
- 一对一 UDP Unicast 语音，按住讲话、松开结束
- 自动发现同一局域网内的设备
- 后台接收、悬浮窗提醒
- 本地通信记录、录音回放
- 可选的离线日语字幕
- 五种界面语言：日本語 / 简体中文 / English / မြန်မာ / বাংলা

状态：**文档与架构决策已完成，实现尚未开始。**

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
docs/ADR/                    架构决策记录（与 docs/ 冲突时以 ADR 为准）
tasks/                       具体实现任务
```

## 已知限制

这些是设计上已知并接受的限制，不是缺陷：

- **企业级 AP 的广播隔离**：设备发现使用 UDP 广播。若 AP 开启 AP Isolation 或禁用广播转发，设备之间无法互相发现。v1 不提供手动输入 IP 的补救手段。
- **发送需要界面可见**：Android 14 及以上禁止从后台启动麦克风类型的前台服务，因此按住 PTT 讲话要求应用界面处于可见状态。从悬浮窗或通知点击会先打开应用。**接收不受此限制**，后台、熄屏、锁屏均可正常接收。
- **进程被系统杀死后不自动恢复通信**：Android 12 及以上禁止从后台启动前台服务。本地数据不会损坏，下次打开应用时恢复。建议将应用加入电池优化白名单。
- **离线字幕首次启用需要一次联网**：识别模型不打进安装包，首次开启字幕功能时下载约 50 MB，之后永久离线可用。核心通信功能在任何时候都不需要互联网。

## 隐私

录音、字幕与通信记录只保存在本机应用私有目录。不上传、不云同步、无遥测。

应用产生的网络流量只有两类：局域网内的对讲协议包，以及用户主动开启字幕功能时的一次性模型下载。

## 构建

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

### 原生库约束

Task23（Opus）与 Task41（Vosk）会引入 `.so`。约束：

- ABI 限定 `arm64-v8a` + `armeabi-v7a`
- `packaging.jniLibs.useLegacyPackaging = false`，使原生库在 APK 中不压缩且按页对齐
- **必须 16 KB page size 对齐**（Android 15+ 要求），发布前逐一验证，见 `docs/08_ReleaseChecklist.md` §6.2

### 构建命令

```bash
gradlew assembleDebug        # Debug APK
gradlew assembleRelease      # Release APK（未签名）
gradlew testDebugUnitTest    # JVM 单元测试
gradlew lintDebug            # 静态检查
gradlew connectedDebugAndroidTest   # 仪器测试，需要连接设备
```

## 开发

增量开发，一个 Task 一个 commit。执行任务前先阅读 `.claude/CLAUDE.md`、相关 `docs/` 与该任务列出的 ADR。

开发路线见 `docs/06_DevelopmentPlan.md`（Task 01~44，编号即执行顺序）。
