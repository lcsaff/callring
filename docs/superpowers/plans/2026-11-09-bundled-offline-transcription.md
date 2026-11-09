# CallRing 内置离线转写实施计划

> **执行技能** 使用 executing-plans 逐项实施和验证。每项完成后单独复核差异与测试结果。

**目标** 正式 CallRing APK 自带中文离线转写所需的全部模型和运行库。全新安装后首次启动，在飞行模式、无 AI 配置的条件下，可从本地导入或打开已有录音，手动转写并持久保存全文。

**架构** Android 平台层解码音频并调用 sherpa-onnx，backend 负责录音任务准入、取消及归档提交，UI 通过 RecordingApi 调用。转写与分析独立准入，用户随后主动分析时可以复用已保存的全文。

**技术栈** Kotlin、MediaExtractor / MediaCodec、sherpa-onnx、ONNX Runtime、SenseVoice INT8、现有 Gradle 和 GitHub Release 流程。

## 范围与验收

- 首版提供详情页「离线转写」、进行中状态、取消和重试。已有全文直接展示，首版不提供覆盖式重转写。挂断自动转写、说话人分离和本地摘要另行设计。
- APK 包含模型、词表、运行库及所需许可声明。用户无需登录、联网、安装语言包或下载额外模型。首次点击才加载模型。
- 离线导入来自设备上已下载的音频文件，直接保存为可手动转写的归档。验收同时覆盖新导入和覆盖升级后已有归档。离线命令无在线回退，无自动摘要或档案补全。现有显式在线分析继续可用。
- 优先验证普通话通话。任务 1 先设定包体和存储预算，再根据实测确定支持机型、最大音频长度和单次转写资源预算。确认后将这些限制记录在 README 和验收用例。沿用现有两小时音频及 256,000 字符上限作为硬上界，实测不达标时收紧离线转写上限并明确提示。

## 文件与职责

| 位置 | 职责 |
| --- | --- |
| `app/build.gradle`、`.github/workflows/build.yml`、`.github/workflows/release.yml`、`tools/release/verify_release.py` | 构建期准备资产和验证正式 APK |
| `app/src/main/kotlin/com/greatwall/callring/platform/recording/AndroidAudioTranscriber.kt`、同目录新增 `AndroidPcmDecoder.kt`、`SherpaOfflineTranscriber.kt` | 共享解码、端侧分段推理、模型加载和释放 |
| `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/OfflineTranscriber.kt`（新增）、`app/src/main/kotlin/com/greatwall/callring/bootstrap/RecordingAssembly.kt` | 窄端口与真实装配 |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingWorkflow.kt`、同目录 `RecordingService.kt`、`contracts/src/main/kotlin/com/greatwall/callring/contracts/recording/RecordingApi.kt` | 正式命令、运行互斥、归档提交与后续分析 |
| `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/ArchiveViewModel.kt`、同目录 `ArchiveScreen.kt` | 操作、进度、错误与全文 |
| `app/src/backendQa/kotlin/com/greatwall/callring/BundledOfflineTranscriptionDeviceTest.kt`（新增）、`README.md`、`PROJECT.md` | 真机验收、用户可见说明、项目约定与证据 |

### 任务 1 · 验证随包模型、包体及升级可行性

- [ ] 选定 sherpa-onnx Android 库版本、SenseVoice INT8 2024-07-17 模型与词表。记录来源、版本、SHA-256 和各自许可，固定依赖与资产清单。优先用现成 AAR，并检查其实际包含的 ABI、ONNX Runtime 和 16 KiB ELF 对齐。
- [ ] 在隔离的 backendQa APK 内打入 arm64 运行库及真实模型，先用 AssetManager 路径加载。测量初始化时间、峰值 PSS 和磁盘增量。若直接加载超出预算或库要求真实路径，试用按模型版本私有解包方案，并分别记录安装和首次使用的存储开销。
- [ ] 全新安装测试包，断网后才首次启动。用固定短 WAV 验证识别指定短句，再用 10 分钟及更长的代表性通话验证首尾内容、安静片段、姓名、号码和口音。记录音频时长、转写时长、APK 体积、峰值 PSS、设备 ABI 和页大小。样本含私人数据时仅在授权设备上使用。
- [ ] 在接入产品流程前，先确定可接受的 APK 体积、覆盖安装和首次加载的可用空间预算。指定 INT8 模型文件约 239 MB。用真实模型、词表、目标 ABI 运行库及预定的资产压缩或解包方式构建候选 APK，记录 APK 字节数、相对现行包的增量、覆盖安装和首次加载的空间峰值。
- [ ] 用隔离安装和同证书的旧版/候选包，驱动现有应用内更新链路下载实际大小的候选包，中断后续传，完成整包校验并覆盖安装，读回旧归档。记录各阶段结果。候选包使用与正式包相同的资产打包方式和目标 ABI。
- [ ] 确认目标机型、离线长度上限和资源预算。加载失败、明显漏段、资源超预算或更新链路无法处理候选包时，先解决阻塞并重测，再锁定引擎和模型、进入产品接线。

任务 1 阶段记录（2026-11-09）

- Xiaomi 2410DPN6CC、Android 16、arm64-v8a、4 KiB 页。隔离 backendQa 包在飞行模式下通过 `BundledOfflineTranscriptionDeviceTest`，真实 MediaCodec 解码及随包模型识别短句通过。夹具为固定模型修订下的 `test_wavs/zh.wav`，SHA-256 为 `3cdb4ee60943a54fd2c967f72baec369c88b9e57129adad0d0f274016f4b130d`。
- 独立探针使用约 616 秒的公开普通话拼接样本，模型初始化 2.27 秒，分段推理 14.60 秒，采样峰值 PSS 544,968 KiB。该样本覆盖首尾及静音，代表性通话、口音和姓名号码专项仍待测。
- 隔离候选 APK 约 417 MB，原 backendQa 包约 51 MB。候选包通过 `zipalign -c -P 16 -v 4` 静态校验。应用内更新生产入口要求正式包名和非调试包，且本机已安装生产包。隔离下载续传和覆盖升级、安装空间峰值及 16 KiB 实机推理仍待验证。
- 产品接线与发布门可独立验证，已与设备验收并行推进。归档命令在同一台真机的飞行模式下使用真实模型完成识别，写入全文及模型标识，关闭并重开录音组件后读回一致。测试使用隔离包和独立录音命名空间。
- 隔离包从版本代码 21 覆盖安装至 24，覆盖前放入的应用私有音频保留。版本 24 在飞行模式下转写该音频，归档写入后重开读回一致。应用内下载器用约 405 MB 的真实 APK 和模拟 HTTP 中断验证了 Range 续传及整包 SHA-256 一致性。

### 任务 2 · 固定生产打包和本地解码路径

- [ ] 增加版本化模型清单及构建资产准备任务，将模型和词表写入 Gradle 生成的 assets 目录。使用指定来源和散列验证，缓存命中也校验。发布构建缺件或不匹配直接失败。模型二进制由构建资产来源管理，运行时只从 APK 读取。
- [ ] 将现有 MediaCodec 解码、重采样和取消检查提取到 AndroidPcmDecoder，共享 16 kHz 单声道 PCM 输出。保留在线 WAV 分段和原有错误语义。离线路径采用有界 PCM 缓冲，遇到语音停顿或最大段长即提交一段推理，按时间顺序拼接。长静音可跳过，全文为空明确返回无可识别语音，超限明确失败。
- [ ] 定义 backend 端口 `OfflineTranscriber.transcribe(reference, cancelled)`，返回全文与稳定模型标识。Android 适配器按需初始化、限制为单路推理，每段前后检查取消。Native 调用返回后再释放资源，取消期间丢弃结果。若采用私有解包，先检查空间，以临时文件流式写入并校验后原子改名，复用完整版本并在无活动任务时清理过期副本。
- [ ] 用真实 WAV 覆盖分段、结尾不足一段和纯静音，用 JVM 测试覆盖取消和超限。运行 `.\gradlew.bat :app:testDebugUnitTest :app:assembleBackendQa`，预期通过且 APK 含模型与词表。

### 任务 3 · 接入录音归档与独立分析

- [ ] 先在 `backend/src/test/kotlin/com/greatwall/callring/RecordingWorkflowTest.kt` 添加场景。无 AI 配置可以本地转写，在线客户端调用数为零，已有全文不重复转写，处理中删除或换音频不写入迟到结果，取消和失败不覆盖已有数据，显式分析复用全文且无需转写连接。运行 `.\gradlew.bat :backend:test --tests "*RecordingWorkflowTest"`，确认新场景按预期失败。
- [ ] 通过 RecordingApi 提供离线转写启动、当前运行状态观察和取消，接入 RecordingService 和 RecordingAssembly。启动返回本次运行 ID，取消须匹配归档和运行 ID，迟到的取消对新运行无效。复用现有录音活动互斥和版本检查，同一归档只允许一个写任务，全局最多一个本地推理。已有在线任务排队或运行的归档返回忙碌，保持原队列语义。存储管理与替换音频复用活动判断。
- [ ] 进程内录音任务持有运行状态。通过现有累计失效协议发布该归档的运行状态变化，详情页首次进入及重进都可读回当前状态。离开详情页后任务继续，进程退出后可从头重试，离线任务不写在线后台可领取的排队记录。推理在事务外执行，提交时串行裁决取消与写入，同时复核归档存在、音频身份、generation 和运行 ID。取消先被接受时丢弃结果，提交先完成时保留全文。成功一次性写入全文及 `transcriptionModel`，沿用 READY 表示仍可分析。归档数据失效仅在实际提交后发布。
- [ ] 本地导入界面提供明确的离线导入选项，经同一正式导入命令传递离线意图。提交归档时直接写入 READY，跳过自动分析入队和调度，现有普通导入及系统导入沿用原行为。测试全新无 AI 配置和已启用自动分析两种配置，验证离线导入均无在线调用。
- [ ] 补充竞态测试。覆盖已排队归档返回忙碌、迟到取消与新运行隔离、取消先于提交、提交先于取消，以及删除或换音频后的迟到结果。设备测试在 native 推理期间取消，验证推理返回前仍保有活动占用，返回后释放资源且不写入全文，随后可重试。
- [ ] 调整显式分析和任务执行两处校验：已有全文时只校验分析连接，缺全文时保留原有在线转写准入。用现有补全流程处理分析结果，并保持失败后重试复用全文。再次运行定向测试，预期全部通过。

### 任务 4 · 完成详情页体验

- [ ] 在 `ui/src/test/kotlin/com/greatwall/callring/ui/feature/recording/RecordingViewModelsTest.kt` 及 `RecordingScreensTest.kt` 增加场景：有音频且无全文时按钮可用，缺音频时不可用，运行中显示状态与取消，离开再进入可恢复当前运行，失败可重试，成功展示全文并保留显式分析入口。
- [ ] ArchiveViewModel 发起和取消正式录音命令，订阅正式运行状态和归档失效，重进详情时恢复当前阶段及对应运行的取消操作。ArchiveScreen 仅展示状态与操作。加载模型期间显示「准备离线模型」，转写阶段显示「正在离线转写」，无可靠总时长时使用不定进度。区分忙碌、音频缺失、静音、模型不可用、空间不足和长度超限。
- [ ] 运行 `.\gradlew.bat :ui:testDebugUnitTest`，预期录音页面测试及既有导入、回听、分析路径通过。

### 任务 5 · 验收签名包与更新链路

- [ ] 在 `tools/release/verify_release.py` 和发布工作流接入强制校验，对最终待上传的签名 APK 核对目标 ABI、模型及词表摘要、包名、签名和 versionCode，检查每个原生库 ELF 的 LOAD 段满足 16 KiB 对齐，并执行 `zipalign -c -P 16 -v 4 <待上传 APK>`。任一项失败即阻止发布。复核任务 1 的包体和更新证据，用最终候选包复验下载、续传、整包校验和覆盖升级。
- [ ] 使用隔离测试包完成全新安装、飞行模式、首次打开、本地导入、保存并重开读回。另测升级后历史归档、长音频、删除竞态、取消重试和 16 KiB 环境。记录代表性目标真机的资源指标，隔离测试保留生产安装。另用专用测试设备安装最终待上传的正式签名候选 APK，在 16 KiB 环境断网首次加载模型并识别固定短句，记录 APK 摘要、设备页大小和推理结果。
- [ ] 运行 `python tools/verify_backend.py` 完成统一门，保留对应提交的日志和报告。更新 README 的操作、支持限制和隐私说明，并在 PROJECT.md 记录状态语义与实测范围。包体验收通过后再进入正式发布流程。

当前实现验证（2026-11-09）

- 统一门通过，backend/UI/app JVM 共 1,293 项，隔离真机常规回归 31 项。测试报告记录在 `app/build/qa/backend-refactor/`，此轮基于工作区源码。
- 405,045,611 字节的签名候选 APK 通过正式验包脚本，包名、证书、模型和许可资产摘要、目标 ABI、16 KiB APK 对齐及 ELF LOAD 段对齐均通过。SHA-256 为 `a53fa28329d80f0ecdd02ab0c54503552c60a94a7b1c35a315fd970c2af720dc`。
- 16 KiB x86_64 隔离模拟器经 `getconf PAGE_SIZE` 实测为 16,384。最终签名候选包在飞行模式下全新安装，通过正式导入及离线转写命令识别固定中文短句。使用同证书的正式 0.10.1 在同一隔离模拟器写入历史归档，经系统覆盖安装候选包后保留归档 ID 和音频，断网转写成功。
- 系统覆盖安装期间按约 150 ms 间隔采样，模拟器 `/data` 可用空间最大下降 395,708 KiB。该值是设备级采样，未覆盖下载文件与安装暂存同时存在时的峰值。
- 16 KiB 模拟器上，正式 0.10.1 的应用内更新器用测试 HTTP 传输下载最终签名候选包，在 121,513,683 字节处中断后按 Range 续传，整包摘要、包名、版本与签名校验通过。通过应用的 FileProvider 拉起系统安装器并确认更新，版本代码由 23 升至 24，原归档与音频保留，离线转写通过。
- arm64 真机补测使用最终 backendQa 隔离包，飞行模式下从内容 URI 走正式离线导入入口，确认 READY、真实模型转写、模型标识及重开读回，专项 1 项通过。飞行模式恢复至测试前状态。
- 原始日志和隔离模拟器测试驱动源码保存在 `app/build/qa/bundled-offline-transcription/`。外部 GitHub 真实下载、代表性真实通话专项及 native 调用内部的取消时点仍未覆盖。候选包未发布。

## 上游资料

- [SenseVoice INT8 模型文件](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/blob/6a65851692da9706cbddfac66ea9b96ebb1dee21/model.int8.onnx)
- [SenseVoice 官方说明与许可](https://github.com/QwenAudio/SenseVoice/blob/main/README.md)
- [sherpa-onnx Android 构建文档](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [Android 16 KB 页兼容指南](https://developer.android.com/guide/practices/page-sizes)
