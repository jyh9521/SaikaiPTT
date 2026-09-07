# third_party

第三方源码，以 git submodule 引入并锁定到具体 tag。

## opus

`https://github.com/xiph/opus.git`，锁定 **v1.6.1**（commit `22244de5`），BSD-3-Clause。

选型理由、构建参数与验证结果见 `docs/ADR/ADR-007-Opus-Integration.md`。

**首次 clone 之后必须执行一次**：

```
git submodule update --init --recursive
```

忘了执行的话，CMake 会在配置阶段直接失败并打印这条命令，不会给出难懂的报错。

源码本身不打进 APK：libopus 以**静态库**编译，链接进我们自己的 `libsaikaiopus.so`，
最终产物只有那一个 `.so`。
