# SaikaiPTT Data Model Specification

## 1. 文档目的

本文档定义 SaikaiPTT 的本地数据模型、DataStore、Room、录音文件、字幕、收藏、未读状态以及数据生命周期。

核心原则：

- 用户设置使用 DataStore
- 通信历史使用 Room
- 录音文件与数据库分离
- Device ID 永久保存
- 所有核心数据本地保存
- 不依赖云端
- 删除记录时同步处理关联音频
- 数据模型必须支持未来扩展
- 不为简单设置建立不必要的数据库表

---

# 2. Storage Architecture

SaikaiPTT 使用三类本地存储：

```text
DataStore
    ↓
应用设置 / 设备身份 / 用户配置

Room
    ↓
通信记录及结构化元数据

App Private Files
    ↓
录音文件
```

关系：

```text
                 ┌──────────────┐
                 │  DataStore   │
                 └──────┬───────┘
                        │
                 App Settings
                        │

┌──────────────┐   references   ┌─────────────────┐
│     Room     │ ─────────────→ │ Local Audio     │
│ History Data │                │ Files           │
└──────────────┘                └─────────────────┘
```

---

# 3. DataStore Responsibilities

DataStore 负责保存以下 key。这是**完整清单**，实现不得随意增删：

| Key | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `device_id` | String | 首次生成 UUID v4 | §4 |
| `local_users` | 序列化列表 | 空 | §5 |
| `active_user_id` | String? | null | §6 |
| `app_language` | String | `ja` | §7 |
| `allow_interrupt` | Boolean | **false** | §8，接收方策略 |
| `history_retention` | Enum | `7_DAYS` | §29 |
| `asr_enabled` | Boolean | **false** | §8 |
| `asr_model_ready` | Boolean | false | ASR 模型是否已下载并校验通过 |
| `overlay_enabled` | Boolean | false | 用户偏好，与系统权限分开 |
| `first_launch_completed` | Boolean | false | 首启流程是否走完 |
| `permission_guidance_shown` | Boolean | false | 权限引导是否已展示过 |

不要把通信记录放入 DataStore。

不要把大文本或音频放入 DataStore。

# 4. Device Identity

Key：

```text
device_id
```

类型：

String

值：

UUID v4。

例如：

```text
6e84d6d2-b7d2-4fa9-9d9d-7cbf72xxxxxx
```

生命周期：

永久。

除非用户执行明确的应用数据清除或重新安装，否则不主动改变。

重新生成 Device ID 必须是非常明确的操作。

正常：

- App 重启不改变
- 手机重启不改变
- IP 改变不改变
- WiFi 改变不改变

---

# 5. Local User Model

用户名称列表属于本机配置。

建议 DataStore 使用结构化序列化保存。

逻辑模型：

```text
LocalUser
- id
- displayName
- createdAt
- updatedAt
```

User ID：

建议使用本地 UUID。

不要使用 displayName 作为唯一键。

原因：

用户名允许修改。

例如：

User ID：

A123

displayName：

张三

修改后：

displayName：

经理

User ID：

仍然：

A123。

---

# 6. Active User

DataStore：

```text
active_user_id
```

必须指向一个有效 LocalUser。

如果当前没有 LocalUser：

进入首次创建流程。

如果 active_user_id 指向不存在的用户：

进行数据修复。

不要 Crash。

---

# 7. Language Settings

DataStore：

```text
app_language
```

允许值：

```text
system
ja
zh-CN
en
my
bn
```

产品默认：

ja。

是否允许 system 跟随系统语言：

由最终 UX 决定。

核心业务逻辑不得依赖具体语言。

---

# 8. PTT Settings

## 8.1 allow_interrupt

DataStore key：

```text
allow_interrupt
```

类型：

Boolean。

默认：

**false**。

含义：

> 允许其他设备打断我正在进行的通话。

**这是接收方策略**（`03_Protocol §33.1`、`01_PRD §16`）。

修订说明：

原设计为单一枚举 `interrupt_mode: BUSY | FORCE_INTERRUPT`，语义上无法表达「这是谁的策略」。且 `01_PRD §16` / `04_UI_UX §22` 当时按发送方开关描述，与 `03_Protocol §33` 的接收方描述直接冲突。

现统一为接收方 Boolean 开关。发送方不需要、也无法感知对方的策略。

## 8.2 其它

```text
asr_enabled          Boolean, 默认 false
asr_model_ready      Boolean, 默认 false
history_retention    Enum,    默认 7_DAYS
overlay_enabled      Boolean, 默认 false
```

# 9. Room Database

Room 仅保存：

- 通信历史
- 会话元数据
- Transcript
- Read state
- Favorite state

数据库名称建议：

```text
saikai_ptt.db
```

数据库版本：

从：

1

开始。

所有未来 schema 修改：

必须使用 Migration。

禁止生产版本直接 destructive migration。

开发阶段如需要 destructive migration：

必须明确说明。

---

# 10. CommunicationRecord

核心实体：

```text
CommunicationRecord
```

字段（**规范定义**，`01_PRD §27` 与 `.claude/CLAUDE.md §16` 均以本节为准）：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | String (UUID) | 否 | Record ID，同时用作音频文件名（§20） |
| `sessionId` | String (UUID) | 否 | 关联的 PTT Session（§12） |
| `senderDeviceId` | String (UUID) | 否 | §14 |
| `receiverDeviceId` | String (UUID) | 否 | §14 |
| `remoteDeviceId` | String (UUID) | 否 | 冗余列，等于 sender/receiver 中非本机的一方，用于索引与查询（§14） |
| `localUserId` | String (UUID) | 否 | 本机当时使用的 LocalUser（§15） |
| `remoteUserName` | String | 否 | 通信发生时的名称快照（§16） |
| `direction` | Enum | 否 | SEND / RECEIVE（§13） |
| `timestamp` | Long | 否 | UTC epoch millis（§17） |
| `durationMs` | Long | 否 | §18 |
| `audioPath` | String | **是** | 应用私有目录相对路径；保存失败时为 null（§19） |
| `audioFormat` | Enum | **是** | OPUS / AAC；audioPath 为 null 时为 null（§21） |
| `transcript` | String | 是 | §22 |
| `transcriptStatus` | Enum | 否 | 五态，默认 NOT_REQUESTED（§23） |
| `isRead` | Boolean | 否 | §24 |
| `isFavorite` | Boolean | 否 | 默认 false（§25） |
| `status` | Enum | 否 | COMPLETED / INTERRUPTED / FAILED（§26） |
| `createdAt` | Long | 否 | 记录创建时刻 |
| `updatedAt` | Long | 否 | 最后修改时刻 |

`remoteDeviceId` 是有意的冗余：它使「按对端设备查询历史」成为单列索引，而不需要在每次查询时对 sender/receiver 做条件判断。写入时一次性计算。

# 11. Record ID

Record ID：

UUID。

例如：

```text
e4b7...
```

Record ID：

仅用于标识：

本地历史记录。

不得与 Session ID 混淆。

---

# 12. Session ID

CommunicationRecord：

必须保存：

SessionId。

用于关联：

实际 PTT Session。

一个 Session：

对应一个通信历史记录。

如果未来支持更复杂的通信模型：

SessionId 仍可作为协议与数据库之间的关联键。

---

# 13. Direction

定义：

```text
SEND
RECEIVE
```

SEND：

本机发送。

RECEIVE：

本机接收。

不要根据：

senderDeviceId == 当前 Device ID

在 UI 层临时计算所有逻辑。

Direction 在记录生成时明确写入。

---

# 14. Sender and Receiver

必须分别保存：

```text
senderDeviceId
receiverDeviceId
```

不要只保存：

remoteDeviceId。

原因：

未来可能扩展：

群组、转发、系统消息等。

当前 UI：

remoteDeviceId

可以通过业务层决定。

---

# 15. Local User ID

发送记录应该记录：

```text
localUserId
```

这样可以知道：

当时本机使用的是哪个本地用户。

不要只保存当前用户名。

---

# 16. Remote User Name

必须记录：

```text
remoteUserName
```

这是通信发生时远程设备发送的名称快照。

原因：

远程用户之后可能改名。

历史记录仍应保留当时显示的名字。

例如：

2026-09-05：

田中

之后：

改为：

管理者

历史记录仍显示：

田中。

---

# 17. Timestamp

通信记录至少保存：

```text
timestamp
```

推荐：

UTC Instant / epoch milliseconds。

UI 根据当前 Locale 和时区格式化。

不要把已经格式化的：

“2026/09/05 11:25”

作为唯一存储值。

---

# 18. Duration

保存：

```text
durationMs
```

不要保存：

"4 秒"

这种格式化字符串。

UI 根据语言进行格式化。

---

# 19. Audio Path

保存：

```text
audioPath
```

只保存：

App 私有文件目录中的：

相对路径或稳定标识。

推荐避免保存依赖完整绝对路径的信息。

因为 Android 应用路径可能变化。

例如：

```text
records/2026/09/05/e4b7.opus
```

---

# 20. Audio File Naming

目录结构：

```text
records/
  .tmp/                  录制中的临时文件（§32）
  YYYY/
    MM/
      DD/
        <recordId>.<ext>
```

扩展名由 `audioFormat` 决定：

| audioFormat | 扩展名 |
|---|---|
| `OPUS` | `.opus`（Ogg 容器） |
| `AAC` | `.m4a` |

例如：

```text
records/2026/09/05/e4b7c1a2-....opus
```

规则：

- Record ID 作为文件名。
- **禁止**使用用户名作为文件名：用户名可修改，且可能包含非法文件名字符（含缅甸语、孟加拉语字符）。
- 数据库保存的是**相对路径**，不含应用私有目录前缀（`§19`）。

# 21. Audio Format

首选：

Opus（Ogg 容器），16 kHz 单声道，与传输编码一致（`ADR-004 §6`）。

回退：

AAC。

## 21.1 audioFormat 是必需字段

```text
audioFormat: OPUS | AAC
```

不得假设所有历史文件永远都是 Opus。文件扩展名与播放器选择均由该字段驱动（§20）。

## 21.2 与传输编码的关系

`audioFormat` **仅描述本地存档文件**。

传输编码由 ProtocolVersion 固定（v1 = Opus，参数固定），**不做能力协商**。

两者不得混淆：协议层没有 codec 协商字段，若一端擅自改用其它传输编码，语音将无法解码（`03_Protocol §21`）。

# 22. Transcript

数据库保存：

```text
transcript
```

可以为空。

如果 ASR 未开启：

NULL。

如果 ASR 尚未完成：

NULL 或根据实现使用中间状态。

如果识别完成：

保存最终文本。

---

# 23. Transcript Status

**五态**：

```text
NOT_REQUESTED
PENDING
PROCESSING
COMPLETED
FAILED
```

| 状态 | 含义 |
|---|---|
| `NOT_REQUESTED` | ASR 未开启，本条记录不参与识别。**默认值** |
| `PENDING` | 已加入识别队列 |
| `PROCESSING` | 正在识别 |
| `COMPLETED` | 识别成功，`transcript` 有值 |
| `FAILED` | 识别失败（已达重试上限） |

因为 ASR 默认关闭，`NOT_REQUESTED` 是绝大多数记录的初始状态，**必须存在**。

修订说明：

`01_PRD §33` 与 `00_MasterPrompt §22` 原先只列 4 态，遗漏 `NOT_REQUESTED`。本节为规范定义，两处已同步修正。这是 Room 列的取值域，写错需要 migration。

# 24. Read State

字段：

```text
isRead
```

Boolean。

发送记录：

默认：

true。

接收的新记录：

默认：

false。

用户进入记录：

更新：

true。

---

# 25. Favorite State

字段：

```text
isFavorite
```

Boolean。

默认：

false。

收藏：

true。

历史清理：

普通清理不得删除：

isFavorite = true

的记录。

除非用户明确执行：

全部删除。

---

# 26. Record Status

**三态**：

```text
COMPLETED
INTERRUPTED
FAILED
```

| 状态 | 含义 |
|---|---|
| `COMPLETED` | 完整的 PTT 会话，正常收到/发送 VOICE_END |
| `INTERRUPTED` | 过程中提前结束：被强插终止、会话超时、WiFi 断开、AudioFocus 丢失、对端离线 |
| `FAILED` | 会话建立成功但录音保存失败（`audioPath = null`） |

## 26.1 不引入 TIMEOUT 状态

`03_Protocol §23` 的会话超时归入 `INTERRUPTED`。

具体中断原因记录在**日志**中，不进入数据库 schema——增加枚举值会带来 migration 成本，而 UI 对不同中断原因的呈现完全一致。

## 26.2 请求失败不生成记录

被 BUSY 拒绝、或 500ms 无应答的请求，**不生成任何历史记录**（`01_PRD §10.4`）。

`FAILED` 仅用于「会话已建立但存储失败」的情况。

即使 `FAILED`，也应尽可能保留已有可用数据。

# 27. Indexing

为支持历史查询与搜索，Room 建立以下索引：

| 索引 | 用途 |
|---|---|
| `timestamp DESC` | 历史列表默认排序 |
| `remoteDeviceId` | 按对端设备筛选 |
| `remoteUserName` | 用户名搜索 |
| `sessionId`（唯一） | 协议与数据库的关联键，同时防止同一会话重复插入 |
| `isRead` | 未读筛选与角标计数 |
| `isFavorite` | 收藏筛选与清理保护 |
| `transcriptStatus` | ASR worker 查询待处理记录 |
| `status` | 异常记录排查 |

## 27.1 Transcript 搜索

v1 使用 `LIKE '%keyword%'` 实现。

理由：

单机历史记录量级（按 7 天默认保留、每天数十条计）在数千条以内，`LIKE` 扫描完全够用。

**不引入 SQLite FTS**：FTS4/FTS5 会增加表结构复杂度、迁移成本与数据库体积，而日语分词效果对 FTS 默认 tokenizer 并不理想。

若未来实测证明性能不足，再评估 FTS 并记录 ADR。

# 28. Communication Query

历史页面默认：

timestamp DESC。

支持：

最新 → 最旧。

搜索：

remoteUserName

以及：

transcript。

---

# 29. Retention Settings

DataStore：

```text
history_retention
```

值：

```text
1_DAY
3_DAYS
7_DAYS
30_DAYS
FOREVER
```

默认：

7_DAYS。

---

# 30. Cleanup Strategy

自动清理：

不能只删除 Room row。

必须同步处理：

Audio File。

推荐：

```text
query expired records
↓
delete audio files
↓
delete database records
↓
scan for orphan files
```

发生文件删除失败：

记录日志。

后续继续 cleanup。

不要因为一个文件删除失败而中止所有清理任务。

---

# 31. Orphan Audio Cleanup

定期检查：

Audio directory。

找出：

数据库没有引用的音频文件。

删除：

过期 orphan files。

注意：

不能简单删除所有数据库暂时没有引用的文件。

需要考虑：

文件正在创建。

文件正在播放。

正在进行的 PTT。

---

# 32. Active Recording Protection

正在录音的文件：

不得被 cleanup 删除。

建议：

临时文件：

```text
records/.tmp/
```

PTT 完成后：

finalize：

移动到正式目录。

只有 finalized record：

才进入普通清理范围。

---

# 33. Transaction Strategy

Room 数据库操作：

需要在适当位置使用 transaction。

例如：

创建历史记录与相关元数据：

应保证原子性。

但：

文件系统操作：

不是 Room transaction 的一部分。

因此：

必须采用可恢复的两阶段/最终一致策略。

---

# 34. Record Creation Flow

建议：

```text
PTT finished
↓
finalize audio file
↓
insert CommunicationRecord
↓
queue ASR
```

如果：

Room insert 失败：

保留音频文件并记录 orphan candidate。

后续 cleanup 可以处理。

如果：

audio finalize 失败：

不得创建一个指向不存在文件的正常 COMPLETED 记录。

---

# 35. ASR Queue Metadata

如果使用 WorkManager：

数据库不需要保存整个 WorkRequest。

只需要：

transcriptStatus

等业务状态。

Worker 通过：

RecordId

查询需要处理的记录。

---

# 36. ASR Retry

如果识别失败：

允许有限次数重试。

不要无限重试。

建议：

3 次以内。

之后：

FAILED。

用户可以手动：

重新识别。

---

# 37. Manual Re-Transcription

History Detail 可以提供：

重新识别。

逻辑：

如果本地录音存在：

重新进入 ASR queue。

设置：

PROCESSING。

---

# 38. Deleting a Record

用户删除单条记录：

```text
mark/delete Room record
+
delete audio
```

必须避免：

UI 删除成功但音频永久残留。

如果文件删除失败：

数据库可以进入：

待清理状态。

具体实现由 Data Layer 定义。

---

# 39. Bulk Delete

用户可以：

删除全部历史。

但应明确确认。

删除规则：

普通记录：

删除。

收藏：

需要再次确认或者单独提示：

“是否同时删除收藏记录？”

不要默认无提示清空重要收藏内容。

---

# 40. Data Migration

DataStore key 改动：

必须考虑旧版本迁移。

Room：

使用正式 Migration。

任何破坏性 schema 改动：

必须先创建 ADR。

---

# 41. Data Validation

读取本地数据时：

必须考虑：

- 文件损坏
- 数据缺失
- schema mismatch
- 用户删除 App 数据后状态丢失
- audioPath 指向不存在文件
- activeUserId 无对应用户

不能因为本地数据异常导致 Crash。

---

# 42. Backup Policy

当前版本：

不要求云端备份。

如果 Android 系统自动 backup：

需要在 Release 阶段评估是否应该排除通信录音及敏感数据。

不要假定 Android backup 行为一定符合产品隐私目标。

---

# 43. Export

MVP 不需要：

- 云同步
- 自动导出
- 云备份

未来如果增加导出：

必须单独定义数据格式和隐私提示。

---

# 44. Privacy

本地通信记录：

属于用户私有数据。

默认：

只保存在应用私有目录。

不要：

上传。

不要：

云同步。

不要：

发送给第三方。

---

# 45. Storage Quota

因为录音会占用空间：

需要在 Settings 显示：

可选的存储使用量。

例如：

通信记录：

1.2 GB

可选：

清理历史。

不需要实时频繁计算。

打开历史设置时计算即可。

---

# 46. Storage Error

如果磁盘空间不足：

PTT 本身仍应优先。

录音可能无法保存。

这种情况下：

- 实时通信继续
- 显示存储错误
- 记录日志
- 不 Crash

如果录音无法保存：

仍可尽可能保留当前通信状态。

---

# 47. Database Error

Room 发生错误：

不要：

直接退出 App。

应该：

记录日志。

给 UI：

有限且本地化的错误状态。

核心 PTT：

尽量不依赖历史数据库可用性。

---

# 48. History Independence

即：

History 故障：

不能直接导致：

- Discovery 停止
- Heartbeat 停止
- Voice 停止

通信核心与历史持久化必须解耦。

---

# 49. Data Model Evolution

未来可能增加：

- Group
- Tags
- Notes
- Export
- More ASR languages

当前模型：

应保留扩展空间。

但是：

不要为了假想功能添加大量字段。

---

# 50. Data Model Definition of Done

数据模型完成必须明确：

- DataStore keys **完整清单**与默认值（§3）
- LocalUser model
- Device ID
- Active user 与自愈逻辑
- App language
- `allow_interrupt`（接收方策略）
- Room database 与版本
- CommunicationRecord **完整字段表**（§10）
- Session ID 关联与唯一索引
- `audioPath` 与 `audioFormat`
- 文件命名与扩展名规则（§20）
- transcript 与 transcript status（五态）
- record status（三态）
- read state / favorite state
- history retention
- cleanup strategy 与收藏保护
- 正在录制文件的保护（§32）
- 手动删除与批量删除（§38、§39）
- migration strategy
- orphan file handling
- 索引清单（§27）

完成后必须能够支持：

PTT → Recording → History → ASR → Playback → Search → Delete → Cleanup

完整生命周期。

# 51. Final Data Principle

数据层应该保持：

```text
Simple settings
    ↓
DataStore

Structured records
    ↓
Room

Large binary data
    ↓
Private file storage
```

不要把所有东西塞进数据库。

不要把所有东西塞进 DataStore。

不要让 UI 直接处理文件系统。

数据层必须对上层隐藏具体存储实现。
