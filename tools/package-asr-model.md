# 发布日语识别模型

把四个 int8 文件挂到本项目自己的 GitHub Releases 上。
理由与安全审查见 `docs/ADR/ADR-012-ASR-Reinstated.md §2`。

**做一次就够**，除非换模型。应用端下载的就是这些文件。

**命令是给 Windows `cmd.exe` 写的**，不需要 Git Bash、不需要 WSL。
需要约 200 MB 临时磁盘。`curl` 和 `certutil` 是 Windows 10 1803 起自带的。

---

## 1. 下载四个文件

从模型的源仓库 HuggingFace 下。**只要带 `int8` 的三个，加 `tokens.txt`**；
三个 fp32 文件（合计 614 MB）手机不加载，不要下。

```bat
mkdir %TEMP%\asr
cd /d %TEMP%\asr

curl -L -O https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main/encoder-epoch-99-avg-1.int8.onnx
curl -L -O https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main/decoder-epoch-99-avg-1.int8.onnx
curl -L -O https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main/joiner-epoch-99-avg-1.int8.onnx
curl -L -O https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/main/tokens.txt

curl -L -o LICENSE https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/LICENSE
```

合计约 161 MB。网页上点下载按钮也一样，放进同一个目录即可。

## 2. 核对——这一步不能跳

```bat
dir

certutil -hashfile encoder-epoch-99-avg-1.int8.onnx SHA256
certutil -hashfile decoder-epoch-99-avg-1.int8.onnx SHA256
certutil -hashfile joiner-epoch-99-avg-1.int8.onnx SHA256
certutil -hashfile tokens.txt SHA256
```

必须与下表**逐字节一致**。这些值是从 sherpa-onnx 的官方 tar 包里取出来实测的，
是独立于 HF 的第二个来源：

| 文件 | 字节 | SHA-256 |
|---|---|---|
| `encoder-epoch-99-avg-1.int8.onnx` | 154,670,139 | `2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d` |
| `decoder-epoch-99-avg-1.int8.onnx` | 2,959,337 | `8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485` |
| `joiner-epoch-99-avg-1.int8.onnx` | 2,696,970 | `49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de` |
| `tokens.txt` | 45,754 | `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def` |

`certutil` 输出的十六进制不带空格、全小写，可以直接和上表对。
嫌一个个看麻烦就用 PowerShell 一次列完：

```powershell
Get-FileHash *.onnx, tokens.txt -Algorithm SHA256 | Format-List Path, Hash
```

**对不上就停下来告诉我。** 说明 HF 上那份和 sherpa-onnx 分发的不是同一份，
在搞清楚之前不能往用户设备上推。

> HF 页面会把两个 encoder 标成 "scanned as suspicious"。那是 ClamAV 对大二进制的误报，
> 已经查证过：模型里零个自定义算子、零个外部数据引用、23 个可疑标记全无命中。
> 依据与复核方法在 `ADR-012 §同一性与安全审查`。

## 3. 上传

**不打包。** 六个文件直接作为 release 资产传上去——理由见本文件末尾。

`NOTICE` 在仓库里（`tools/asr-model/NOTICE`），先复制过来：

```bat
copy /Y "C:\Users\noway\Downloads\SaikaiPTT\tools\asr-model\NOTICE" .
dir
```

现在目录里应该正好六个文件。用 `gh` 传：

```bat
gh release create asr-model-ja-v1 ^
  encoder-epoch-99-avg-1.int8.onnx ^
  decoder-epoch-99-avg-1.int8.onnx ^
  joiner-epoch-99-avg-1.int8.onnx ^
  tokens.txt ^
  LICENSE ^
  NOTICE ^
  --repo jyh9521/SaikaiPTT ^
  --title "Japanese ASR model (int8) v1" ^
  --notes "ReazonSpeech k2 v2, int8 quantised, exported by sherpa-onnx. Apache-2.0. See NOTICE."
```

没装 `gh` 就在网页上新建 release，tag 填 `asr-model-ja-v1`，六个文件拖进去。

**tag 名字不要改**，应用端的 URL 是按它拼的：

```
https://github.com/jyh9521/SaikaiPTT/releases/download/asr-model-ja-v1/<文件名>
```

## 4. 验一下传上去的东西能下

```bat
cd /d %TEMP%
curl -L -o check.txt https://github.com/jyh9521/SaikaiPTT/releases/download/asr-model-ja-v1/tokens.txt
certutil -hashfile check.txt SHA256
```

应当还是 `2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def`。

对上了就说一声，我把 URL 和四个校验值钉进 `SaikaiConfig`。**校验值我已经有了**
（上面那张表），所以这一步之后不需要你再提供任何数字。

## 5. 清临时文件

```bat
cd /d %TEMP%
rmdir /s /q asr
del check.txt
```

---

## 为什么不打包成一个归档

本文件早先的版本要求打成 `.tar.bz2`，理由是「一个归档 = 一个摘要、一次原子安装」。
那个理由站不住，换成六个独立资产：

- **Java 没有内置的 bzip2 解码器。** `java.util.zip` 只有 deflate 和 gzip。
  用 `.tar.bz2` 就得为它引一个 commons-compress 之类的依赖，
  而 `CLAUDE.md §32` 要求引依赖前先确认平台有没有现成的——这里的答案是「有，但不是 bz2」。
- **改用 zip 也只是把问题换个样子**：手机上要同时放下归档和解压后的内容，
  峰值多占 161 MB，而低端机本来就紧张。
- **「一个摘要」并没有省事。** 四个文件的 SHA-256 已经有了独立来源（上表），
  原子性靠「下到临时目录 → 逐个校验 → 整个目录改名」实现，和归档没关系。
- 对你来说少一步打包，对我来说少一段解压代码，两边都少一个会出错的地方。

`.onnx` 本来就是压过的权重，压缩率也没什么可省的。
