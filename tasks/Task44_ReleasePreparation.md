# SaikaiPTT Task

## Execution Rules

Before implementing this task:

1. Read `.claude/CLAUDE.md`.
2. Read the relevant documents under `docs/`.
3. **Read the ADRs listed under Required Reading below.** Where an ADR and a `docs/` file disagree, the ADR wins.
4. Read this task completely.
5. Inspect the current repository and existing implementation.
6. Do not assume the repository is empty or matches the planned structure.

Scope rule:

- Implement ONLY this task.
- Do not silently implement later tasks.
- Do not redesign unrelated modules.
- Do not modify protocol format, database schema, public interfaces, or module boundaries unless this task explicitly requires it.
- If an architectural change is necessary, explain why before making it, and create a NEW ADR (never edit an accepted one silently).

After implementation:

1. Build the affected project.
2. Run relevant tests.
3. Review changed files.
4. Check for architecture regressions.
5. Summarize changes.
6. Report tests and results.
7. Report known issues.
8. STOP and wait for the next instruction.

---

## Goal

完成 Release Candidate 的全部发布前检查。

## Required Reading

- `docs/08_ReleaseChecklist.md`（逐项执行）

## Requirements

### 构建
- Debug / Release build 成功
- Release 不依赖开发机路径、开发 WiFi、本地服务、Mock
- 签名配置正确
- 记录 Gradle / Kotlin / AGP / SDK / 依赖版本，验证可重复构建

### 静态检查
- Android Lint 无 blocker；warnings 已评估并记录
- 单元测试全部通过

### 原生库
- **`.so` 的 16 KB page size 对齐验证**
- ABI 清单确认
- APK / AAB 体积记录与评估

### Release 卫生
- DEBUG 日志关闭
- 详细 packet logging 关闭
- Debug 面板 / 测试按钮 / Mock / Fake 实现全部移除

### 隐私
- 无云端音频、云端 ASR、云端历史、非预期遥测
- 录音与字幕只存本地
- **确认唯一的联网路径是 ASR 模型下载**
- 评估 Android 系统 backup 行为，决定是否排除通信历史、录音与字幕

### 数据
- Room migration 正确，无 destructive migration
- 文件系统检查：只写应用私有目录、无大量临时文件、orphan cleanup 正常

### 无障碍
- TalkBack、内容描述、触控区域、文本可读性、非颜色唯一状态

### 文档
- README：项目介绍、核心功能、Android 要求、安装、权限说明、使用方法、**已知限制**（AP Isolation 无法发现、发送需界面可见、进程被杀不自动恢复、ASR 首次需联网）、开发方法、构建环境
- CHANGELOG
- 版本号 versionName / versionCode

### Sign-off
按 `08_ReleaseChecklist §57` 逐项记录：Build / Tests / Devices / Known Issues / Performance / Privacy / Release Logging / Version。

## Acceptance Criteria

只有在以下全部成立时才可声明 Release Candidate：

- 核心阻塞测试（一对一 PTT、后台接收、网络恢复、Busy、录音、播放、数据完整性、Android 11 兼容、低端设备稳定性、Force Interrupt、i18n）全部通过
- 无未解决的严重 Crash / 数据丢失 / PTT 可靠性问题
- 已知问题已明确记录，未被隐藏
