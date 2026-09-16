# ADR-011 — 离线日语 ASR 引擎复核：v1 不集成

Status: Accepted
Date: 2026-09-16
Supersedes: ADR-006（引擎选型与「约 50 MB 一次下载」两条）

## Context

`ADR-006 §1` 选定 Vosk，并要求在 `Task41` 开始实现**之前**完成四项复核：

1. 是否提供 16 KB page size 对齐的 `.so`（Android 15+）
2. ABI 是否覆盖 `arm64-v8a` + `armeabi-v7a`
3. 引擎与模型的许可证与再分发条款
4. MTK P22 / 4 GB 设备上的常驻内存与识别耗时

同一节写明：「如复核不通过，另立 ADR 记录更换结论，不得静默替换。」本文件即该记录。

`CLAUDE.md §4.1` 把「原生库必须 16 KB 页对齐」列为 API 35 的红线，`§34.2` 要求版本与事实取自实证而非记忆。以下每一条都注明了取证方式。

## 复核结果

### Vosk —— 第 1 项不通过

取证：`git clone --depth 1 --branch v0.3.50 https://github.com/alphacep/vosk-api`（v0.3.50 是 `git ls-remote --tags` 显示的最新 tag）。

| 项 | 结果 | 依据 |
|---|---|---|
| 16 KB 对齐 | **不通过** | `android/lib/build.gradle` 声明 `ndkVersion = "25.2.9519653"`；`android/lib/build-vosk.sh` 的链接参数是 `EXTRA_LDFLAGS="-llog -static-libstdc++ -Wl,-soname,libvosk.so"`；对全仓库的 `*.sh` / `*.gradle` / `*.cmake` / `CMakeLists.txt` / `Makefile*` 搜索 `max-page-size` 与 `16384`，**零命中**。NDK 25 默认按 4 KB 对齐链接 |
| ABI | 通过 | 构建脚本循环覆盖 `armeabi-v7a arm64-v8a x86_64 x86` |
| 许可证 | 通过 | 仓库根 `COPYING` 为 Apache License 2.0 |
| 设备实测 | **未做** | 见下「未能验证的部分」 |

补充发现：`android/lib/build.gradle` 依赖 `net.java.dev.jna:jna:5.13.0@aar`。JNA 自带每 ABI 的 `.so`，是第二个原生依赖，其对齐由第三方决定，即使 Vosk 自身修好也要单独复核。

**这一条从项目侧无法绕过。** ELF 的 `p_align` 在链接时写死，`zipalign -P 16` 对齐的是 zip 条目偏移而不是 ELF 段，AGP 也不会重新链接预编译的 `.so`。要修只能用 NDK r27+ 自行编译整条 Kaldi + OpenBLAS + OpenFST 工具链。

### sherpa-onnx —— 前 3 项通过，第 4 项被模型体积否决

取证：`k2-fsa/sherpa-onnx` v1.13.8（`git ls-remote --tags` 排序后的最新 release tag）。

| 项 | 结果 | 依据 |
|---|---|---|
| 16 KB 对齐 | **通过，且验到二进制** | `CMakeLists.txt:207` 对 `if(ANDROID)` 无条件设 `-Wl,-z,max-page-size=16384`。下载发布产物 `sherpa-onnx-1.13.8.aar`（50,129,134 B）并读 ELF program header：`jni/arm64-v8a` 与 `jni/armeabi-v7a` 下全部 8 个 `.so` 的每个 LOAD 段 Align 均为 `0x4000` = 16384 |
| ABI | 通过 | AAR 内含 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` |
| 许可证 | 通过 | 仓库根 `LICENSE` 为 Apache License 2.0 |
| 体积 | **不通过** | 见下 |

引擎自身进 APK 的体积（AAR 内实际字节）：

| ABI | libonnxruntime | jni | c-api | cxx-api | 合计 |
|---|---|---|---|---|---|
| arm64-v8a | 22,249,560 | 4,771,760 | 4,465,168 | 440,688 | **≈ 31.9 MB** |
| armeabi-v7a | 15,359,592 | 3,428,780 | 3,202,700 | 282,008 | **≈ 22.3 MB** |

日语模型的下载体积（对 release 资产 HEAD 取 `Content-Length`，真实字节）：

| 模型 | 字节 | |
|---|---|---|
| `sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01` | 713,097,333 | ≈ 680 MiB |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | 1,047,870,769 | ≈ 999 MiB |
| `sherpa-onnx-whisper-tiny`（多语言，含日语） | 116,204,861 | ≈ 111 MiB |

这些压缩包同时含 fp32 与 int8 权重及测试音频，真正需要的子集更小；但**下载的就是这些包**，而本项目没有自己的服务器去托管重新打包过的模型——`ADR-006 §2` 的整个前提就是从引擎官方渠道下载。

分发形态也是成本：sherpa-onnx 的 Android AAR 发布为 GitHub release 资产而非 Maven Central 坐标，接入需要引入 JitPack 仓库或把 AAR 放进 `app/libs/`。

## Decision

**v1 不集成离线日语 ASR。** `Task41` 的实现部分不执行，复核结论以本 ADR 留档。

数据模型与界面保持现状，不做任何回退：

- `CommunicationRecord.transcriptStatus` 的五个状态、`transcript` 字段、`transcriptStatus` 索引全部保留。它们是 `05_DataModel §10/§23/§27` 的normative 定义，不因为暂时没有生产者而删掉——删了再加回来是一次 schema 迁移。
- 每条新记录的状态是 `NOT_REQUESTED`，这本来就是全设备默认值（`CLAUDE.md §18.1`）。详情页在这个状态下不画字幕区（Task39），所以界面上看不出缺了什么。
- `HistoryConfig.asrMaxRetries` 保留。
- Task39 为 `FAILED` 状态写的「再识别将在后续版本提供」文案保留，它现在描述的是同一件事。

## Rationale

四个选项都摆过，选这一个的理由：

**ASR 是默认关闭的可选功能。** `CLAUDE.md §3` 的优先级里，功能丰富度排在最后；`§18.1` 已经规定它在每台设备上默认关闭。一个默认关闭的功能不值得为它破坏 `§4.1` 的平台红线（Vosk 方案），也不值得为它在 APK 里加 32 MB 原生库再让用户下 111 MB~680 MB（sherpa 方案）。

**目标设备是 MTK P22 / 4 GB。** `§31` 给的是硬指标：空闲 PSS < 130 MB，发送 CPU ≤ 15% 单核。在这样的机器上，让一个 ONNX Runtime 或 Kaldi 常驻在实时音频管线旁边，是要拿核心通信的余量去换一个可选功能——而 `§44` 的冲突优先级里，实时 PTT 排第二，附加功能排第九。

**不集成反而让隐私承诺更强。** `ADR-006 §3` 要求在 PRD 和 README 里写明「唯一的例外是首次启用字幕需要一次联网下载」。v1 没有这个例外：**核心功能与全部功能都不需要互联网**。这句话更短、更真、更好验证，也免掉了一条受控下载路径的全部攻击面。

**保留接缝的成本接近零。** 数据模型已经能表达识别的五个状态，历史记录已经存了可播放的 Ogg/Opus 文件，识别本来就是「PTT 结束之后发生在录音上的事」（`§18.3`）。将来补一个生产者进来，不需要动 schema、不需要动协议、不需要动界面。

## 被否决的方案

| 方案 | 否决理由 |
|---|---|
| 仍用 Vosk，接受 16 KB 设备上降级 | 为一个默认关闭的功能给 `§4.1` 的红线开例外。降级本身是静默的：`System.loadLibrary` 失败，用户只看到「识别失败」 |
| sherpa-onnx + whisper-tiny（111 MB） | APK 加 32 MB，下载 111 MB，而 whisper-tiny 的日语准确率并不好——三项成本换一个勉强能用的结果 |
| sherpa-onnx + reazonspeech（680 MB） | 日语准确率最好，但 680 MB 下载对仓库/工厂场景不现实 |
| 用 NDK r27+ 自行编译 Vosk | 要重建 Kaldi + OpenBLAS + CLAPACK + OpenFST 整条工具链并长期自行维护。`§32` 的依赖政策不支持为一个可选功能背上一个交叉编译产物 |
| 改用平台 `SpeechRecognizer` 离线识别 | 可用性完全由厂商决定，MTK P22 / Android 11 上不保证存在；语言包的下载与来源不在应用控制内，无法保证 `§18` 要求的「100% 本地、不上传音频」 |

## Consequences

- `Task41` 的验收条件中「断网状态下可以生成日语字幕」在 v1 不成立，其余三条（识别失败不影响播放与历史、识别不影响 PTT、ADR 已创建）在「没有识别」这个前提下自动成立或由本 ADR 满足。
- `CLAUDE.md §18`、`ADR-006 §3` 描述的「唯一联网例外」在 v1 不存在。`Task44` 的隐私说明与 README 应当写成**无例外**，而不是照抄 ADR-006 的措辞。按 `§37`，此处以本 ADR 为准。
- `ADR-006` 标记为 Superseded：它的引擎选型（§1）与「约 50 MB」的体积假设（§2、§4）都被本文件取代。它的模型分发形态（不打进 APK、首次启用时下载、校验失败即删除重下）在将来重新引入 ASR 时仍然适用。
- 未来重新评估的触发条件写在这里，免得下次还要从头查：**存在一个 16 KB 页对齐、许可证允许再分发、且单次下载在 100 MB 以内的日语模型**。

## 未能验证的部分

诚实记录，不猜：

- **Vosk 发布的 `.so` 本体没读到。** 仓库里的 `android/lib/src/main/jniLibs/*/` 是空目录，`.so` 由 `build-vosk.sh` 生成；预编译产物在 Maven Central，而本环境的出口白名单不放行 `repo1.maven.org`（CONNECT 403）。上面的结论来自构建配置而非二进制。开发机上可以一条命令实证：

  ```
  unzip -o vosk-android-<ver>.aar -d /tmp/vosk
  readelf -lW /tmp/vosk/jni/arm64-v8a/libvosk.so | grep LOAD
  ```

  若 Align 显示 `0x1000`（4 KB）则本 ADR 的结论成立；若显示 `0x4000` 则 Vosk 已修复，可据此另立 ADR 重新评估。

- **Vosk 日语模型的体积没验成。** `alphacephei.com` 同样被出口白名单挡下（403）。`ADR-006` 写的「约 40–50 MB」本文件不为其背书。

- **没有任何设备实测。** 复核清单第 4 项（MTK P22 上的常驻内存与识别耗时）需要在真机上跑一个已集成的引擎才能得出，而本 ADR 的结论是不集成。这一项留空，不以估算充数（`CLAUDE.md §31.2`：代码审查不是性能测量）。
