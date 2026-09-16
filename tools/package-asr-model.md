# 重新打包日语识别模型

把四个 int8 文件打成一个归档，挂到本项目自己的 GitHub Releases 上。
理由与安全审查见 `docs/ADR/ADR-012-ASR-Reinstated.md §2`。

**做一次就够**，除非换模型。应用端下载的就是这一步的产物。

需要：约 400 MB 临时磁盘、`tar`、`bzip2`、`sha256sum`。
Windows 上用 Git Bash 或 WSL。**别在仓库目录里做**——这些文件一个都不该进 git。

---

## 1. 下载四个文件

从模型的源仓库 HuggingFace 下。**只要带 `int8` 的三个，加 `tokens.txt`**；
三个 fp32 文件（合计 614 MB）手机不加载，不要下。

```bash
mkdir -p /tmp/asr && cd /tmp/asr
B=https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main

curl -L -O $B/encoder-epoch-99-avg-1.int8.onnx
curl -L -O $B/decoder-epoch-99-avg-1.int8.onnx
curl -L -O $B/joiner-epoch-99-avg-1.int8.onnx
curl -L -O $B/tokens.txt
```

合计约 161 MB。

> 网页上点下载按钮也一样，四个文件放进同一个目录即可。

## 2. 核对——这一步不能跳

```bash
sha256sum *
ls -l
```

必须与下表**逐字节一致**。这些值是从 sherpa-onnx 的官方 tar 包里取出来实测的，
是独立于 HF 的第二个来源：

| 文件 | 字节 | SHA-256 |
|---|---|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 154,670,139 | `2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d` |
| `decoder-epoch-99-avg-1.int8.onnx` | 2,959,337 | `8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485` |
| `joiner-epoch-99-avg-1.int8.onnx` | 2,696,970 | `49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de` |
| `tokens.txt` | 45,754 | `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def` |

**对不上就停下来告诉我。** 说明 HF 上那份和 sherpa-onnx 分发的不是同一份，
在搞清楚之前不能往用户设备上推。

> HF 页面会把两个 encoder 标成 "scanned as suspicious"。那是 ClamAV 对大二进制的误报，
> 已经查证过：模型里零个自定义算子、零个外部数据引用、23 个可疑标记全无命中。
> 依据与复核方法在 `ADR-012 §同一性与安全审查`。

## 3. 附上许可与出处

Apache-2.0 要求随分发保留许可与声明（`ADR-012 §5`）。

```bash
mkdir -p saikai-asr-ja-v1
mv encoder-epoch-99-avg-1.int8.onnx \
   decoder-epoch-99-avg-1.int8.onnx \
   joiner-epoch-99-avg-1.int8.onnx \
   tokens.txt \
   saikai-asr-ja-v1/

curl -L -o saikai-asr-ja-v1/LICENSE \
  https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE

cat > saikai-asr-ja-v1/NOTICE <<'EOF'
SaikaiPTT Japanese ASR model bundle
===================================

This archive redistributes, unmodified, four files from the ONNX export of
the ReazonSpeech k2 v2 Japanese speech recognition model: the int8
quantised encoder, decoder and joiner, and the token table. The fp32
weights published alongside them are omitted because a phone never loads
them. Nothing that is included has been altered in any way.

Model
  ReazonSpeech k2 v2
  https://huggingface.co/reazon-research/reazonspeech-k2-v2
  Copyright Reazon Holdings, Inc.
  Licensed under the Apache License, Version 2.0

ONNX export and int8 quantisation
  sherpa-onnx, by the k2-fsa project
  https://github.com/k2-fsa/sherpa-onnx
  Licensed under the Apache License, Version 2.0

A copy of the Apache License, Version 2.0 is in the LICENSE file
alongside this notice.
EOF
```

## 4. 打包

```bash
tar cf - saikai-asr-ja-v1 | bzip2 -9 > saikai-asr-ja-v1.tar.bz2
ls -l saikai-asr-ja-v1.tar.bz2
sha256sum saikai-asr-ja-v1.tar.bz2
```

`.onnx` 已经是压缩过的权重，bzip2 再压收益很小；用 `tar.bz2` 只是为了和上游一致、
且一个归档一次校验。压得慢是正常的。

## 5. 传到本项目的 Releases

```bash
gh release create asr-model-ja-v1 saikai-asr-ja-v1.tar.bz2 \
  --repo jyh9521/SaikaiPTT \
  --title "Japanese ASR model (int8) v1" \
  --notes "ReazonSpeech k2 v2, int8, exported by sherpa-onnx. Apache-2.0. See NOTICE inside."
```

或者网页上新建 release，tag 填 `asr-model-ja-v1`，把归档拖进去。

**tag 名字不要改**，应用端的 URL 是按它拼的：

```
https://github.com/jyh9521/SaikaiPTT/releases/download/asr-model-ja-v1/saikai-asr-ja-v1.tar.bz2
```

## 6. 回来告诉我两个数

1. `saikai-asr-ja-v1.tar.bz2` 的 **SHA-256**
2. 它的**字节数**

两个都会钉进 `SaikaiConfig`。没有它们，下载校验就是摆设——
`ADR-006 §2` 要求校验失败即删除并重新下载，而「预期值」只能来自这一步。

## 7. 清掉临时文件

```bash
cd / && rm -rf /tmp/asr
```
