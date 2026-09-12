# ADR-007 — Opus 编解码库选型与集成

Status: Accepted
Date: 2026-09-07
Depends on: ADR-003（VOICE_DATA payload ≤ 400 字节）、ADR-004（音频参数）

## Context

`ADR-004` 固定了音频参数并指出：**Android 平台不保证提供 Opus 编码器**（解码器自
Android 5.0 起可用，编码器不是必备设备编解码器），因此传输编码几乎必然引入原生库。
`ADR-004` 的 Consequences 要求本 ADR 记录：所选库、版本、编译方式、16 KB page size
对齐验证、ABI 覆盖、Android 11 兼容性、维护状态、许可证、体积与内存开销。

`Task23` 另外要求：若无法满足 Android 11 或 16 KB 对齐，回退到 `MediaCodec` AAC-LC
并同步升 ProtocolVersion 至 2。**该回退未被触发。**

## 候选方案与否决理由

### 1. 平台 `MediaCodec` 的 `audio/opus` 编码器 —— 否决

Android CDD 只要求 Opus **解码器**。编码器由厂商可选提供，现实中缺失并不罕见
（例如 scrcpy 在 Android 11 设备上报告 "Could not create default audio encoder for
opus"）。本产品的参考设备正是低端 Android 11 机型，把可用性押在一个可选组件上，
失败形式是「部分设备完全无法讲话」，不可接受。

这也正是 `ADR-004` 当初的判断，此处只是复核后确认。

### 2. Concentus —— 纯 Java 移植 —— 否决

`io.github.jaredmdobson:concentus`（原 `lostromb/concentus`），libopus 的纯
Java/C# 移植。**极具吸引力**：没有 `.so`，16 KB 对齐问题与 ABI 覆盖问题**根本不存在**，
Android 11 天然兼容。

否决理由是性能。上游 issue（`lostromb/concentus.oggfile#9`）记录 Android 上编码
48 kHz 单声道 1 秒音频耗时 **约 2000 ms**，是同一份代码在 Windows/iOS/macOS 上
（100–200 ms）的十倍，提问者结论是「对该 app 来说太慢了」。按采样率折算到本项目的
16 kHz 约为 0.6× 实时——在一台云端 x86 上尚可，在 MTK P22 上必然跑不动。

「低端设备兼容是一等需求」与「押注一个已知在 Android 上慢十倍的实现」不能共存。

### 3. 现成的预编译 AAR / JNI 封装 —— 否决

调研到的候选（`theeasiestway/android-opus-codec`、`louisyonge/opus_android`、
`axet/android-opus`、`techery/opus_android` 等）均为多年未更新的个人项目，没有
维护中的 Maven 制品，**更没有任何一个声明满足 16 KB 对齐**。

把 2027 年 2 月的 Google Play 硬性要求，交给一个几年没动的第三方封装去满足，
是把一个可控的构建问题换成一个不可控的等待问题。

### 4. 从源码构建 libopus —— **采纳**

## Decision

**引入 `xiph/opus` 源码作为 git submodule，锁定 `v1.6.1`（commit `22244de5`），
以静态库编译，链接进本项目自己的单个 JNI 共享库 `libsaikaiopus.so`。**

| 项目 | 值 |
|---|---|
| 库 | `https://github.com/xiph/opus.git` |
| 版本 | **v1.6.1**，commit `22244de5a79bd1d6d623c32e72bf1954b56235be` |
| 许可证 | **BSD-3-Clause**（Xiph.Org 等），与本项目分发方式兼容 |
| 维护状态 | Xiph.Org 官方上游，持续维护 |
| 引入方式 | git submodule，路径 `third_party/opus` |
| 编译 | CMake `add_subdirectory`，**静态库** |
| 产物 | **仅一个** `.so`：`libsaikaiopus.so` |
| ABI | `arm64-v8a` + `armeabi-v7a`（`app/build.gradle.kts` 的 `abiFilters`） |

### 为什么是静态链接进自己的 `.so`

这不是体积优化，**是 16 KB 对齐策略本身**。

对齐要求作用于 APK 里的**每一个**共享库。我们能控制链接参数的，只有我们自己链接的
那些。把 libopus 编成静态库并入 `libsaikaiopus.so`，APK 里就只有这一个 `.so`，
它的对齐由我们自己的 `target_link_options` 保证，不需要去验证、也不需要去等待
任何第三方产物。

同理设置 `ANDROID_STL=none`：JNI 层是 C，不需要 STL，否则 AGP 会额外打包
`libc++_shared.so`——那是另一个「别人负责对齐」的共享库（OpenCV 的
`libc++_shared.so` 未对齐正是社区里的真实案例）。

### 构建参数

```cmake
OPUS_BUILD_SHARED_LIBRARY  OFF   # 静态
OPUS_BUILD_PROGRAMS        OFF
OPUS_BUILD_TESTING         OFF
OPUS_DRED                  OFF   # 神经网络冗余，模型数据比编解码器本身还大
OPUS_OSCE                  OFF   # 同上
OPUS_CUSTOM_MODES          OFF
OPUS_FIXED_POINT           ON    # 参考设备是 MTK P22
OPUS_ENABLE_FLOAT_API      OFF   # 本项目只调用 int16 接口
```

链接参数：

```
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
-Wl,--gc-sections
-Wl,--exclude-libs,ALL
```

NDK **r28 及以上默认就是 16 KB 对齐**，但参数照写不误：一是 r27 也要能正确构建，
二是让这项要求出现在**负责满足它的那个文件里**，而不是只存在于某个工具链版本号中。

### 运行时参数（ADR-004 §1 的落实）

`OPUS_APPLICATION_VOIP`、16 kHz 单声道、20 ms 帧、20 kbps **CBR**、complexity 3、
inband FEC 开、DTX 关，外加两项 ADR-004 没有写出但必需的：

- **`OPUS_SET_PACKET_LOSS_PERC(10)`**。见下方「发现」。
- `OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)`。

## 实测验证

以本 ADR 规定的完全相同的构建参数与运行时参数，在 x86-64 宿主上编译 libopus v1.6.1
与本项目的 JNI 层，通过 JVM 加载并跑通完整往返（100 帧，300 Hz 起伏音高 + 起伏包络）：

| 项目 | 实测 |
|---|---|
| `opus_encoder_ctl` 全部设置 | `OPUS_OK` |
| 包长 | **min = max = avg = 50 字节**（CBR 名副其实） |
| 实际码率 | 20 000 bps |
| 与 400 字节上限的余量 | **8 倍** |
| 编码耗时 | 0.379 ms/帧（预算 20 ms） |
| 解码耗时 | 0.016 ms/帧 |
| 算法延迟 | **101 采样 = 6.3 ms** |
| 对齐后信噪比 | **21.9 dB**，相关性 0.996，RMS 比 1.010 |
| FEC 从下一个包恢复丢帧 | 320 采样 ✓ |
| 丢包隐藏（len=0） | 320 采样 ✓ |
| 越界参数 | 返回 `OPUS_BAD_ARG`，不崩溃 ✓ |

耗时数据来自云端 x86-64，只作数量级参考，真机复核见下。

## 发现（四项，均需记录）

### 1. 只开 `OPUS_SET_INBAND_FEC` 不会产生任何冗余

`ADR-004 §1` 写「Opus inband FEC 开启」，`§4` 又写「Opus inband FEC 已覆盖单帧丢失」。
**仅设置 `OPUS_SET_INBAND_FEC(1)` 是不够的**：libopus 只在**认为正在丢包**时才为
冗余副本花费比特，`OPUS_SET_PACKET_LOSS_PERC` 默认为 0，于是 FEC 开关打开、
冗余为空——付了配置的代价，买不到任何东西。

处理：新增 `AudioConfig.opusExpectedPacketLossPercent`，默认 **10%**；并在 `init` 中
强制「开了 FEC 就不允许期望丢包率为 0」，让这个组合无法被静默地配置出来。

### 2. FEC 只在解码方主动索取时才存在

冗余副本搭在**下一个**包里。`ADR-004 §4` 的「插入静音帧，FEC 已覆盖单帧丢失」两句
互相矛盾：不调用 `opus_decode(..., decode_fec=1)`，单帧丢失就没有被覆盖。

处理：`VoiceCodec.decodeLost()`（Task22 已预留）由本任务实现；另提供 `conceal()`
走 libopus 自己的丢包隐藏（`len=0`），它比插静音更好且开销相同。**是否启用、以及
抖动缓冲如何调用它们，是 Task26 的决定**，本 ADR 只保证能力存在且已验证可用。

### 3. libopus v1.6.1 的 CMake 构建在 ARM + 定点下必然链接失败（上游缺陷）

第一次真机构建在 `arm64-v8a` 和 `armeabi-v7a` 上同时失败：

```
ld.lld: error: undefined symbol: celt_pitch_xcorr_neon
```

不是配置写错，是上游缺陷，而且**只有选了 `OPUS_FIXED_POINT=ON` 才会踩到**——
浮点构建的 `celt_pitch_xcorr` 走的是 NEON **intrinsics**，不碰这个符号。链条：

| 位置 | 内容 |
|---|---|
| `celt/arm/pitch_arm.h:71` | 在 `FIXED_POINT` 且 `OPUS_ARM_MAY_HAVE_NEON` 下**声明** `celt_pitch_xcorr_neon` |
| `celt/arm/arm_celt_map.c:88` | 同一宏下把它填进运行时分发表 |
| `celt/arm/celt_pitch_xcorr_arm.s` | 该符号**唯一的定义**，手写汇编 |
| `cmake/OpusSources.cmake:36` | 把这个 `.s` 读进 `CELT_SOURCES_ARM_ASM`，**而这个变量在整个 CMake 构建里再没被用过** |
| `CMakeLists.txt:573` | 却照样定义 `OPUS_ARM_MAY_HAVE_NEON` |
| `cmake/OpusConfig.cmake:77` | 对 aarch64 用裸 `set()` 强制 `OPUS_PRESUME_NEON ON` |

autotools 构建用 `arm2gnu.pl` 转译并汇编这个 `.s`，CMake 构建没有这一步。于是
**引用存在、定义不可能存在**。最后一行还意味着：仅把 `OPUS_MAY_HAVE_NEON` 选项关掉
无济于事——`pitch_arm.h:99` 的 `OPUS_ARM_PRESUME_NEON` 分支会把同一个引用再造出来。

处理（`app/src/main/cpp/CMakeLists.txt`，`add_subdirectory` 之后）：从 `opus` 目标的
`COMPILE_DEFINITIONS` 里精确移除 `OPUS_ARM_MAY_HAVE_NEON` 与 `OPUS_ARM_PRESUME_NEON`
两个宏。`list(REMOVE_ITEM)` 是全等匹配，`_INTR` 拼写的两个宏原样保留——**这正是这个
修法便宜的原因**：

- 全部 NEON intrinsics 路径继续编译进来：`xcorr_kernel_neon_fixed`、
  `celt_inner_prod`、`dual_inner_prod`，以及 SILK 的 NSQ、NSQ_del_dec、biquad、
  LPC_inv_pred_gain、warped autocorrelation；
- `celt/arm/armcpu.c` 的运行时 CPU 检测继续工作，它每一处判断都同时测
  `OPUS_ARM_MAY_HAVE_NEON_INTR`；
- 只有 `celt_pitch_xcorr` 一个函数退回 C 实现，而上游按约定保证它与汇编版**逐位一致**。

已否决的替代方案：`OPUS_DISABLE_INTRINSICS=ON` 能链接，但等于放弃全部 NEON，在 MTK
P22 上这不是舍入误差；改用浮点构建与 `ADR-004`、本 ADR 的理由直接冲突。

该改动带注释写在 CMake 文件里，并注明：将来若上游在 CMake 下汇编了那个 `.s`，删掉这
一段即可自动恢复汇编版本。

### 4. Debug 构建会把 libopus 编成 `-O0`

同一份构建日志里还有一条被淹没的警告：

```
opus_decoder.c:38: warning: You appear to be compiling without optimization,
if so opus will be very slow.
```

它说的是实话。装到测试机上的正是 debug APK，而一个未优化的定点编码器在低端设备上
完全可能直接吃满 20 ms 帧预算——表现出来是断续的声音，然后会被当成网络或采集的 bug
去查，而不是一个构建开关。

处理：`set_property(TARGET opus APPEND PROPERTY COMPILE_OPTIONS $<$<CONFIG:Debug>:-O2>)`。
只作用于编解码器；`opus_jni.c` 和整个 app 仍用构建类型自己的参数，真正需要下断点的
地方不受影响。

## 尚待真机复核

以下三项无法在开发沙箱内完成，必须在真实构建产物上核对，结果补记于本节：

1. **`.so` 的 16 KB 对齐**
   ```
   llvm-objdump -p app\build\intermediates\merged_native_libs\debug\...\lib\arm64-v8a\libsaikaiopus.so | findstr LOAD
   ```
   每个 `LOAD` 段需为 `2**14` 或更高。或对整包：`zipalign -c -P 16 -v 4 app-release.apk`
2. **两个 ABI 的 `.so` 体积与 APK 增量**
3. **参考低端设备（MTK P22 级）上的编码耗时**，需远低于 20 ms/帧

## Consequences

- 首次 clone 后必须执行 `git submodule update --init --recursive`。CMake 在配置阶段
  会检查并直接打印这条命令，不给出难懂的报错。
- 构建机需安装 **NDK**（建议 r28+）与 **CMake 3.22.1**（AGP 会按需下载 CMake）。
- `app/src/main/cpp/CMakeLists.txt` 携带一处针对 libopus v1.6.1 的上游缺陷绕行
  （见「发现 3」）。**每次升级 submodule 都必须复核它是否仍然必要、以及是否仍然
  正确**——上游一旦修好，这段代码就会变成默默削弱 NEON 的死代码。
- `ADR-004 §1` 的 FEC 条目应理解为「FEC + 非零期望丢包率」；`§4` 的「插入静音帧」
  被 libopus 自带的丢包隐藏取代，最终行为由 Task26 确定。
- 若将来必须更换编解码器，接缝是 `core.domain.VoiceCodec`（Task22），其中不含任何
  Opus 专有类型；`ADR-004` 规定的 AAC-LC 回退仍然可行，但需另立 ADR 并升
  ProtocolVersion 至 2。
