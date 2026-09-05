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

## 开发

增量开发，一个 Task 一个 commit。执行任务前先阅读 `.claude/CLAUDE.md`、相关 `docs/` 与该任务列出的 ADR。

构建环境版本记录见 `docs/06_DevelopmentPlan.md` 与 Version Catalog（Task03 建立）。
