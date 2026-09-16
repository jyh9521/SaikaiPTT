# ADR-012 — 重新引入离线日语识别：sherpa-onnx + 自行分发 int8 模型

Status: Accepted
Date: 2026-09-16
Supersedes: ADR-011 的 Decision（v1 不集成）与体积结论；ADR-011 对 Vosk 的判定仍然有效

## Context

`ADR-011` 判定 v1 不集成 ASR，理由有两条：Vosk 过不了 16 KB 页对齐，而通过该项的
sherpa-onnx「没有体积可接受的日语模型」。第二条**是错的，或者说口径错了**。

ADR-011 引用的 713,097,333 字节是 `.tar.bz2` **压缩包**的大小。把包的清单拉出来之后：

| 文件 | 字节 | |
|---|---|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 154,670,139 | 147.5 MiB |
| `decoder-epoch-99-avg-1.int8.onnx` | 2,959,337 | 2.8 MiB |
| `joiner-epoch-99-avg-1.int8.onnx` | 2,696,970 | 2.6 MiB |
| `tokens.txt` | 45,754 | 45 KB |
| **手机上实际需要** | **160,372,200** | **约 153 MiB** |
| ~~encoder/decoder/joiner fp32~~ | ~~614,835,799~~ | ~~586 MiB，移动端用不到~~ |
| ~~test_wavs/~~ | ~~1,618,718~~ | ~~示例音频~~ |

包里 82% 是移动端根本不加载的 fp32 权重与测试音频。真实成本是 **153 MiB，
不是 680 MiB**——是 `ADR-006` 所设「约 50 MB」的 3 倍，不是 14 倍。

这个差别足以改变结论，所以本文件推翻 ADR-011 的决定。

> 顺带一提，这正是 `ADR-011 §0` 与 `10_PerformanceMeasurement §0` 反复强调的那类错误：
> 数字是对的，口径是错的，而错口径的数字看起来像结论。写那两段的人自己踩了一次。

## Decision

**集成离线日语识别**，用 sherpa-onnx + reazonspeech 的 int8 模型。

### 1. 引擎：sherpa-onnx v1.13.8

`ADR-011` 已经验证过的部分，此处不重复推导，只重申结论：

- **16 KB 页对齐：通过，且验到二进制。** 下载 `sherpa-onnx-1.13.8.aar` 读 ELF program
  header，`jni/arm64-v8a` 与 `jni/armeabi-v7a` 下全部 8 个 `.so` 的每个 LOAD 段
  Align 均为 `0x4000`。
- **许可证：Apache-2.0**，仓库根 `LICENSE`。
- **ABI**：arm64-v8a / armeabi-v7a 均有。

APK 增量（AAR 内实际字节，`abiFilters` 只留两个 arm ABI）：
arm64-v8a 约 31.9 MB，armeabi-v7a 约 22.3 MB。

分发形态：AAR 发布为 GitHub release 资产而非 Maven Central 坐标。接入方式见 `§4`。

### 2. 模型：reazonspeech int8，由本项目重新分发

模型来源 `https://huggingface.co/reazon-research/reazonspeech-k2-v2`，
**许可证 Apache-2.0**（由开发者在模型页确认）。Apache-2.0 允许再分发，
条件是保留版权与许可声明——照办，见 `§5`。

**sherpa-onnx 的 GitHub Releases 只有整包**：`...-int8.tar.bz2` 不存在（探测 404），
官方文档给的命令就是 `wget` 整包再 `tar xvf`。

**但模型的源仓库 HuggingFace 上四个文件可以单独下载**，而且字节数与 tar 包内完全一致
（见下「同一性」）。所以「取得 153 MiB」这件事不需要下 713 MB——本文件早先的版本
断言「上游不提供 int8 单独的下载」，那句话对 sherpa-onnx 的发布页成立，对 HF 不成立，
是在无法访问 HF 的情况下把推断写成了结论。已更正。

**仍然把四个文件挂到本项目自己的 GitHub Releases 上**，但**不打包成归档**。

重新托管的理由与「能不能单独下」无关：URL 由我们控制，不受第三方的可用性、
限速或重新发布影响，而 `ADR-006 §2` 要求的「记录预期大小与摘要」只有在我们控制的
URL 上才钉得住。

**不打包**，则是因为打包的三条理由都站不住：

- **Java 没有内置的 bzip2 解码器。** `java.util.zip` 只有 deflate 与 gzip。
  用 `.tar.bz2` 要为它引一个 commons-compress 之类的依赖，而 `§32` 要求
  引依赖前先确认平台有没有现成的——这里的答案是「有，但不是 bz2」。
- **改用 zip 只是把问题换个样子**：设备上要同时容纳归档与解压后的内容，
  峰值多占 161 MB，而低端机本来就紧张。
- **「一个归档 = 一个摘要」并没有省事。** 四个文件的 SHA-256 已有独立来源（见下），
  原子性靠「下到临时目录 → 逐个校验 → 整个目录改名」实现，与归档无关。

`.onnx` 本身已是压过的权重，压缩率也无可省。

这不只是省流量，还解决了 `ADR-006 §2` 要求而上游满足不了的一件事——
「记录预期大小与摘要，校验失败则删除并要求重新下载」。URL 由我们控制，
摘要就能钉死在代码里；用上游那个会随上游重新发布而变的包则做不到。

选 reazonspeech 而不是 whisper-tiny（int8 约 99 MiB）的理由：省下的 54 MB
换不来那个准确率差距。reazonspeech 是 35000 小时日语专训，whisper-tiny 的日语是
多语言模型顺带支持的。而且 whisper 的 decoder 量化效果很差，int8 之后仍有 85.7 MB,
所以「小模型」并没有小多少。

### 3. 仍然默认关闭

`CLAUDE.md §18.1` 不变：**每台设备默认关闭**，用户显式开启。
`§18.3` 不变：识别在 PTT 段结束之后跑，音频通信永远优先。

这一条同时是下面那个未知量的安全网。

### 4. 依赖接入

sherpa-onnx 的 Android AAR 不在 Maven Central。两条路：

- JitPack：`settings.gradle.kts` 增加 `maven("https://jitpack.io")`，依赖
  `com.github.k2-fsa:sherpa-onnx:<version>`。仓库自带 `jitpack.yml`，其内容是
  把官方预编译 AAR 用 `mvn install:install-file` 装进去——**即 JitPack 不重新编译，
  分发的就是上面验过对齐的那个二进制**。
- 或把 AAR 放进 `app/libs/`。可重复构建更强，代价是一个二进制进仓库。

选 **JitPack**。`.gitignore` 里 `libs/*/*.so` 与 `*.aar` 的意图是不把二进制放进仓库，
而 `08_ReleaseChecklist §51` 的可重复构建要求靠版本钉死满足，不靠把产物提交进来。

### 5. 归属与许可声明

Apache-2.0 要求随分发保留许可与声明。在 Release 资产旁与应用内各放一份：

- Release 里与模型文件并列发布 `LICENSE` 与 `NOTICE` 两个资产，写明模型来自
  reazon-research、经 sherpa-onnx 导出量化，二者均为 Apache-2.0。
  `NOTICE` 的正文在仓库里（`tools/asr-model/NOTICE`），不靠临时敲
- 应用内设置页增加「开源许可」项，列出 libopus、sherpa-onnx、reazonspeech

### 同一性与安全审查

从 sherpa-onnx 的 tar 包里取出四个 int8 文件，实测 SHA-256：

| 文件 | 字节 | SHA-256 |
|---|---|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 154,670,139 | `2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d` |
| `decoder-epoch-99-avg-1.int8.onnx` | 2,959,337 | `8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485` |
| `joiner-epoch-99-avg-1.int8.onnx` | 2,696,970 | `49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de` |
| `tokens.txt` | 45,754 | `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def` |

从 HF 下载的同名文件应当与上表逐字节一致。对不上就说明两边不是同一份，要查清楚再用。

**HF 把两个 encoder 标为 "scanned as suspicious"**，其中一个正是我们要分发的
`encoder-epoch-99-avg-1.int8.onnx`。往用户设备上推一个第三方模型之前，这个必须查清楚，
所以把文件拆开验了：

| 检查 | 结果 |
|---|---|
| opset 域 | 只有 `''`（标准 ONNX），version 13 |
| 节点 | 10,634 个，**全部在默认域**，零个自定义算子 |
| 算子种类 | 38 种，全是标准 ONNX（Conv / MatMul / MatMulInteger / DynamicQuantizeLinear / …） |
| 外部数据引用 | 0——模型无法牵出第二个文件 |
| 本地 function | 0 |
| 元数据 | `model_type=zipformer2`、`model_author=k2-fsa`、`onnx.infer=onnxruntime.quant` |
| 权重 | 688 个 INT8 + 846 个 FLOAT（scale / zero-point / bias），确实是量化过的 |
| 输入 / 输出 | `x`, `x_lens` → `encoder_out`, `encoder_out_lens`，即 zipformer encoder 的签名 |
| 字节级扫描 | `__reduce__`、`__setstate__`、`subprocess`、`os.system`、`builtins`、`pickle`、`torch.jit`、`ai.onnx.contrib`、`com.microsoft`、`PythonOp`、`importlib`、`socket`、`urllib`、`http(s)://`、`/bin/sh`、`cmd.exe`、`.dll` —— **23 个标记，155 MB 里零命中** |

**结论：ClamAV 对大二进制文件的误报。** HF 自己的 pickle 扫描器也报
"No problematic imports detected"，与上面一致。

模型是结构上惰性的：没有自定义算子就没有加载 Python 的入口，ONNX Runtime 不会去
反序列化张量字节。这个判断依据的是模型结构，不是「看起来没问题」。

复核方式写在这里，免得下次还要从头查一遍：

```bash
python3 -c "
import onnx, collections
m = onnx.load('encoder-epoch-99-avg-1.int8.onnx', load_external_data=False)
print([ (o.domain, o.version) for o in m.opset_import ])
print(collections.Counter(n.domain for n in m.graph.node))
print(sorted({n.op_type for n in m.graph.node}))
"
```

域不是只有 `''`，或者出现了上表以外的算子，就停下来。

### 模型的发布位置

已发布，tag `asr-model-ja-v1`。应用端的 URL 按下面这个前缀拼，**tag 名字不可更改**：

```
https://github.com/jyh9521/SaikaiPTT/releases/download/asr-model-ja-v1/<文件名>
```

| 资产 | 字节 | SHA-256 |
|---|---|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 154,670,139 | `2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d` |
| `decoder-epoch-99-avg-1.int8.onnx` | 2,959,337 | `8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485` |
| `joiner-epoch-99-avg-1.int8.onnx` | 2,696,970 | `49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de` |
| `tokens.txt` | 45,754 | `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def` |
| `LICENSE` | 11,358 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `NOTICE` | 1,300 | `75268472ca0dbc981922ea2ba8c71ee9b6bf130b47430e11677439ef0d9c4e0f` |

`LICENSE` 与 `NOTICE` 是许可要求的随附声明，**应用不下载它们**——只有前四个进设备。

前四个的摘要有三个互相独立的来源，全部一致：

1. 从 sherpa-onnx 官方 tar 包取出后实测
2. GitHub 在 release 页上自己算出并展示的
3. 从上面那个 URL 下回来后实测

第 3 条同时验证了 URL 本身可用：`tokens.txt` 下回来逐字节相符，
encoder 的 `Content-Length` 为 154,670,139，与预期一致。

Task46 把这张表钉进 `SaikaiConfig`。**不要在代码里重新抄一遍摘要**——
以本表为准，改了这里就要改那里。

## Consequences

### 承诺变了，必须同步改

`ADR-011` 让 v1 变成「不访问互联网，一次都不」，`README`、`CHANGELOG`、
`AndroidManifest` 的注释都照此写过。**现在这条不成立了**，要改回
`ADR-006 §3` 的措辞：

> 核心功能——设备发现、在线状态、一对一 PTT、录音、回放、历史记录——**永不需要互联网**。
> 唯一的例外：**首次开启日语字幕时，需要一次约 153 MiB 的模型下载**。
> 下载完成后识别 100% 本地，不上传任何音频或文本。

这是 Task45 的收尾工作，不能漏。

### 一个未知量，而且要先做才能知道

**147 MB 的 int8 encoder 在 4 GB 的 MTK P22 上跑得动吗，3 秒语音要识别多久。**

这是 `ADR-006 §1` 复核清单的第 4 项，从头到尾没测过——因为要测就得先集成。
先有鸡还是先有蛋，只能这么破：

1. 按默认关闭集成
2. 在低端参考机上实测常驻内存与识别耗时，记进 `10_PerformanceMeasurement`
3. 据结果决定：保留、标为「仅现代设备可用」、或撤回

默认关闭让第 3 步的任何一个结果都不伤害核心功能。**这一条必须真测，
不得以「看起来还行」结案**（`CLAUDE.md §31.2`）。

判定标准先定在这里，免得事后找理由：

| 指标 | 门槛 | 超出时 |
|---|---|---|
| 识别耗时 / 音频时长 | ≤ 1.0×（3 秒语音 ≤ 3 秒出字幕） | 超过 2× 视为不可用 |
| 识别期间的 PTT | 必须完全不受影响 | 任何影响都是阻塞项 |
| 识别时的进程 PSS 增量 | 记录即可，不设上限 | ASR 不计入 `§31` 的 130 MB |

### 其他

- `HistoryConfig.asrMaxRetries = 3` 一直在，现在终于有东西用它。
- 数据模型不动：`transcript`、五态的 `transcriptStatus`、`transcriptStatus` 索引
  都是 Task37 就建好的，**不需要 schema 迁移**。这是 ADR-011 当初坚持不删它们的回报。
- 识别需要 PCM，而录音是 Ogg 容器里的 Opus。需要一个 `OggOpusReader`，
  即 Task38 那个 writer 的逆操作。用自己的读取器而不是 `MediaExtractor`：
  纯 Kotlin、可对着 writer 做往返测试、且没有低端 ROM 的厂商差异。
- 新增的下载路径是全应用唯一的联网代码，必须在隐私说明里单列（`§40`）。

## 被否决的方案

| 方案 | 否决理由 |
|---|---|
| 照上游下整包 713 MB，端上解压后删掉 fp32 | 用户实际要传 713 MB，且摘要随上游重新发布而变，钉不住 |
| whisper-tiny（int8 约 99 MiB） | 只省 54 MB，换来明显更差的日语准确率 |
| sense-voice（多语言） | 压缩包 1.05 GB，且日语不是它的强项 |
| 内置进 APK | ADR-006 §4 已否决：默认关闭的功能，让所有人为不用的东西付出下载与存储 |
| 用 Android `SpeechRecognizer` 离线识别 | 可用性由厂商决定，MTK P22 / Android 11 不保证存在；语言包来源不可控，无法保证 100% 本地 |
| 自己用 NDK r27+ 编 Vosk | 要维护 Kaldi + OpenBLAS + CLAPACK + OpenFST 整条交叉编译链（ADR-011 已否决，理由不变） |
