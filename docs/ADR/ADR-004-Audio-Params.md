# ADR-004 — 音频参数、Codec 与 AudioFocus 策略

Status: Accepted
Date: 2026-09-05

## Context

`04_UI_UX §44` 把音频中断行为交给「Audio Architecture 决定」，但 `02_Architecture` 只画了管线图，没有定义采样率、帧长、音频源、路由、AEC/AGC、AudioFocus 行为。这些参数同时被配置中心、采集、播放、Codec、打包四个 Task 引用，不先定会导致 Task20 起边写边改。

另外 Android 平台的 `MediaCodec` **不保证提供 Opus 编码器**（解码器自 Android 5.0 起可用，编码器不是必备设备编解码器），因此 Opus 编码几乎必然引入原生库。

## Decision

### 1. 音频参数（v1 固定，写入 Config）

| 参数 | 值 |
|---|---|
| 采样率 | 16000 Hz |
| 声道 | 单声道 |
| 采样格式 | PCM 16-bit |
| 帧长 | 20 ms（320 samples / 640 bytes PCM） |
| Opus 模式 | `OPUS_APPLICATION_VOIP` |
| Opus 码率 | 20 kbps CBR |
| Opus complexity | 3（低端设备优先） |
| Opus inband FEC | 开启 |
| Opus DTX | 关闭（v1 不做静音抑制，避免半双工下的状态歧义） |

采样率与帧长通过 `VOICE_START` payload 通告（ADR-003 §4）；接收方若收到不支持的组合，返回 `BUSY` 并记录 ProtocolError，不尝试重采样。

### 2. 采集

- `AudioSource` 优先 `VOICE_COMMUNICATION`（平台自带 AEC/NS/AGC，适合仓库/工厂噪声环境）；初始化失败时回退 `MIC`。两者均在 Config 中可切换。
- `AudioRecord` buffer = `max(minBufferSize, 4 × frameBytes)`。
- 采集在专用线程，优先级 `THREAD_PRIORITY_URGENT_AUDIO`。
- 缓冲区预分配复用，禁止每帧分配。

### 3. 播放

- `AudioTrack`，`AudioAttributes`：`USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`，`PERFORMANCE_MODE_LOW_LATENCY`。
- `AudioManager.mode` 保持 `MODE_NORMAL`。理由：PTT 为半双工，麦克风与扬声器不会同时工作，不存在回声路径，无需进入 `MODE_IN_COMMUNICATION`（后者会改变全局音量流并影响其他应用）。
- 默认输出扬声器；插入耳机/蓝牙时跟随系统路由。
- 音量走 `STREAM_VOICE_CALL`。

### 4. Jitter Buffer

| 参数 | 值 |
|---|---|
| 起播门限 | 3 帧（60 ms） |
| 目标深度 | 3 帧 |
| 最大深度 | 10 帧（200 ms），超出丢弃最旧帧 |
| 迟到包窗口 | 已越过播放点的包直接丢弃 |
| 丢帧处理 | v1 插入静音帧（不做 PLC），Opus inband FEC 已覆盖单帧丢失 |

### 5. AudioFocus 策略（填补 `04_UI_UX §44` 的空缺）

- 会话开始（发送或接收）时申请 `AUDIOFOCUS_GAIN_TRANSIENT`；会话结束立即释放。
- `AUDIOFOCUS_LOSS`（如来电接通）：**立即终止当前会话**。发送方停止采集并发 `VOICE_END`；接收方停止播放并本地标记 `INTERRUPTED`。
- `AUDIOFOCUS_LOSS_TRANSIENT`：同上，PTT 不做「暂停后恢复」——对讲的语义是实时的，恢复播放旧音频没有意义。
- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`：不降低音量，按 `LOSS_TRANSIENT` 处理（语音可懂度优先）。
- 麦克风被其他应用占用（`AudioRecord` 初始化失败或 `startRecording` 抛错）：返回 `MICROPHONE_UNAVAILABLE`，不进入 TRANSMITTING。

### 6. 录音落盘（消除双重编码）

- **发送端**：实时编码产生的 Opus 帧**同时**写入 UDP 与本地文件，不做第二次编码。
- **接收端**：把**收到的原始 Opus 帧**（经 jitter buffer 排序去重后）直接写入文件，不做「解码后重编码」。
- 容器：Ogg/Opus（`.opus`），16 kHz 单声道。
- 因此双方录音的 CPU 增量仅为文件 I/O。
- 丢失的帧在文件中不做补齐，`durationMs` 以会话实际时长为准。

## Consequences

- Opus 需要原生库（见 ADR-006 的同类约束）：库必须提供 **16 KB page size 对齐**的 `.so`（Android 15+ 要求），ABI 至少 `arm64-v8a` + `armeabi-v7a`。构建基线（Task03）必须预留 NDK/CMake 与 ABI 配置。
- 若最终选定的 Opus 库无法满足 Android 11 或 16 KB 对齐要求，回退方案为：传输层改用平台 `MediaCodec` AAC-LC（`audio/mp4a-latm`，16 kHz 单声道，24 kbps），并**同步升 ProtocolVersion 至 2**。该回退必须另立 ADR，不得静默切换。
- `05_DataModel` 的 `audioFormat` 字段仅描述**本地存档文件**格式，与传输编码无关；传输编码由 ProtocolVersion 固定。
