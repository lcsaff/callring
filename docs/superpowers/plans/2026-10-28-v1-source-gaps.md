# 1.0 源码问题解决计划

> 执行方式：后续使用 executing-plans 逐项执行，每个工作包完成后单独审查与验收。本轮仅交付计划，不实施产品变更。

**Goal:** 解决录音列表全量读取、录音时间显示不一致，以及备份恢复、存储管理、首次配置三项能力缺口。

**Architecture:** 遵循 PROJECT.md 的 contracts → backend / ui → app 装配关系。业务校验、提交和变更发布归正式服务；SQLite、文件、权限和生命周期适配归 app；UI 只消费契约。

**Tech Stack:** Kotlin、Compose/MVVM、SQLite、Coroutines、Android SAF、现有 ChangeFeed 与统一验证门。

**基线：** 2026-10-28，远端 main `d805157`，version.properties 为 0.7.0 / 19。此前审核工作区为 `e189a71`；已核对两者差异，以下五项涉及的生产实现未变化。

**文档性质：** 待实施方案，具体新增产品行为是本计划的建议默认值；不改变现行 PROJECT.md，不代表已经开发、验证或获得发布资格。实施前复核最新主线与接口声明。

## 1. 范围与选择

| 编号 | 性质 | 范围 | 完成标志 |
| --- | --- | --- | --- |
| A | 明确性能风险，尚未实测卡顿阈值 | 录音列表摘要查询与分页 | 打开和翻页不解码完整归档，加载量有界 |
| B | 明确显示缺陷 | 录音列表、详情的配置时区 | 与工作时间配置一致，配置变化即时刷新 |
| C | 新增产品能力 | 用户主动备份、校验、恢复 | 可换机恢复，坏包和中断不破坏原数据 |
| D | 新增产品能力 | 存储统计、清理预览、批量清理 | 空间可解释，删除范围可确认、可核验 |
| E | 新增产品能力 | 首用配置清单及异常状态提示 | 用户能辨别核心来电处理是否具备条件 |

选择“逐项交付”：B → A → C → D → E。B 改动小且不依赖其他任务；A 建立 D 需要的有界查询；C 先提供数据保障，再交付批量清理。E 可在前述工作间独立实施，但不要求多会话或并行代理。

不选择一次重写录音模块：风险大、难以定位回归。也不只修 B/A 后停止：那样无法解决用户长期使用与迁移问题。诊断导出、录音全文搜索、云同步、AI 新工具、真实来电/更新专项不包含在这五项计划中；1.0 发布仍需另行完成原有设备验收。

## 2. 工作包 B：统一录音页面时区

**现状依据：** `RecordingArchivesScreen.kt` 与 `ArchiveScreen.kt` 直接使用 `ZoneId.systemDefault()`；两个 ViewModel 只订阅录音变更。工作配置时区与系统时区不同，即出现显示偏差。

**修改文件：**

- `ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt`：复用现有设置摘要观察，将配置时区传给录音页面。
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingArchivesScreen.kt`、`ArchiveScreen.kt`：接收显式配置时区，通过 UiFormat 格式化。
- `ui/src/test/kotlin/com/greatwall/callring/ui/feature/recording/RecordingScreensTest.kt`、`RecordingArchiveListTest.kt`：补显示与刷新用例。

**执行与验收：**

- [ ] 先增加失败用例：手机时区与配置时区不同，验证同一时间戳在列表、详情与业务报表一致；覆盖日期跨日和夏令时切换。
- [ ] 沿用 SettingsSummary 中的 workZoneId 及既有设置变化协议，不创建第二份持久时区。配置尚未取得时使用现有首次加载/错误状态，不悄悄显示成手机时区。
- [ ] 切换配置时区时只刷新显示依赖；不重写录音时间戳、不修改历史分类、不重新加载无关全文。设备系统时区变化不覆盖显式配置。
- [ ] 验证导航、旋转、恢复前台、加载失败重试；设置变化相关 loader 正确刷新，无关录音正文 loader 增量为零。
- [ ] 更新 PROJECT.md 的验收摘要；README 无需增加新功能说明，只核对既有时区描述。

不扩大到更新发布日期、诊断时间等其他时间字段；若后续发现同类偏差，单独确认其业务语义。

## 3. 工作包 A：录音列表改为摘要分页

**现状调用链：** `RecordingArchivesViewModel` → `RecordingApi.listArchives()` → `RecordingService` → `AndroidRecordingStore.listArchives()`。底层查询所有 payload，构造包含 transcript/analysis 的 CallArchive，后端和 UI 再排序；LazyColumn 只减少绘制，不减少读取与内存。

**修改文件：**

- `contracts/.../recording/RecordingApi.kt`：将无参数全集列表替换为分页摘要查询；DTO 限于 callId、generation、number、startedAtEpochMillis、status、source、durationMillis、hasAudio、受限摘要。
- `backend/.../application/ports/recording/RecordingMetadata.kt`：增加窄分页读取端口；`application/recording/RecordingService.kt` 负责参数校验与投影。
- `app/.../data/recording/AndroidRecordingStore.kt`、`ArchiveProjectionIndex.kt`、`RecordingMetadataReads.kt`：复用既有派生索引，增加列表所需标量/受限摘要和 `(started DESC, call_id)` 索引，升级录音库。
- `ui/.../feature/recording/RecordingArchivesViewModel.kt`、`RecordingArchivesScreen.kt`、`ui/app/RecordingRoutes.kt`：分页状态、加载更多、单页重试、详情导航。
- `ui/.../feature/recording/RecordingArchiveListTest.kt`、`app/src/test/.../platform/ai/AndroidRecordingStoreTest.kt`，新增 `app/src/test/kotlin/com/greatwall/callring/data/RecordingArchivePagingTest.kt`。

**接口设计约束：** 默认每页 50，允许 1–100；使用 `(startedAtEpochMillis DESC, callId ASC)` 键集游标，不能用 callId 推断时间或正负顺序。游标绑定查询和录音来源版本，源变化返回明确失效状态，UI 丢弃旧分页并重新请求首屏。返回 items / nextCursor / complete，不将当前页称为全集。摘要最多 160 个 Unicode 码点，完整文字仍在详情读取。

**执行与验收：**

- [ ] 先补失败测试：同时间戳、负 ID、空列表、页尾、新增/删除/更新期间翻页、取消及过期响应。
- [ ] 迁移只创建或补齐派生数据，不重写权威 payload。历史缺索引数据以限额、可中断、可恢复的批次补建；准备中返回明确进度，不能伪装为空或完整，也不能每次打开列表扫描全部旧全文。
- [ ] 索引与归档写入共享原有事务；删除同步移除派生数据；generation 检查阻止旧索引覆盖新归档。保留 AI D16 的原有全文与查询语义。
- [ ] 将页面、测试 Fake 及全部消费者迁出旧 listArchives 后删除旧入口，不保留绕过分页的转发；精准更新已审查的契约摘要，外部 AI 协议不因 UI 分页而扩展。
- [ ] 以 1,000 / 10,000 条归档，混合短文本和接近现有上限的长文本，测冷迁移、首屏、翻页、取消；记录耗时与峰值内存，区分索引准备和已就绪读取。
- [ ] 已就绪每页最多返回 limit 条，读取最多 limit+1 个候选；`listArchiveDecodes`、`fullArchiveDecodes` 增量为零。冷迁移按字节预算计量；不得借用暖路径指标掩盖一次性全量读取。
- [ ] 保留已有列表摘要和状态，详情读取仍正确；补订阅验证、迁移中断恢复及失败回滚。

## 4. 工作包 C：可迁移的备份与恢复

**范围默认值：** 全量替换恢复，不做跨设备合并。默认备份档案、职位、规则、工作配置及历史、统计原始事实、日历与审计、外观、录音事件/关联/删除标记、转写和分析；允许选择是否包含自有录音音频，默认包含自定义铃声。排除 API 密钥、Keystore 材料、系统权限/角色、SAF 授权、组件宿主身份、更新缓存和进程内聊天/任务。

**恢复后的运行条件：** 不触发自动分析或上传，恢复后自动分析保持关闭，需重新确认连接及录音目录；权限和组件重新设置。未包含的音频明确标为未恢复，禁止沿用旧设备私有路径。备份口令仅驻内存，不进入模型、日志或 SavedState。

**拟新增边界：**

- `contracts/.../backup/BackupApi.kt`：导出、导入校验预览、确认恢复、取消及结构化进度；文件参数沿用 DocumentRef。
- `backend/.../application/backup/BackupService.kt` 和窄 ports：覆盖范围、预览绑定、版本检查及业务编排，不接万能 Graph。
- `app/.../data/backup/BackupArchiveCodec.kt`：流式、版本化的可迁移格式与文件摘要清单，不复用 AI snapshot 的 20,000 行/8 MiB 预算，也不把整个库装入内存。
- `app/.../platform/backup/BackupDocumentStore.kt`：SAF 导入导出、暂存、空间检查及取消清理。
- `app/.../bootstrap/RestoreCoordinator.kt`：业务 owner 封口、排空、关闭、恢复日志与重新装配；不让 backend 持有完整 Graph。
- `ui/.../feature/backup/`：范围选择、口令、导入预览、恢复确认和结果；设置“系统与支持”添加入口。

**执行与验收：**

- [ ] C1 先建立持久字段清单和文件格式 v1，逐项映射所有 store、偏好、引用、ID、枚举和自增序列；备份预览显示真实数量和包含/排除范围。
- [ ] C2 实现跨设备口令加密，不依赖原设备 Keystore。使用认证加密与每包独立随机盐/nonce；采用平台 JCA 和有上限的 KDF 参数，不自行实现算法。将格式、参数和篡改检测作为独立安全审查项后才开放 UI。
- [ ] C3 导出前检查无活动通话/采集；在一致性屏障内固定核心库、录音库和偏好版本，将所选自有文件复制到私有暂存，再解除屏障并流式加密写出。系统来电入口优先放行；来电触发取消备份准备，不让网络或外部文档写入持有数据库事务/业务锁。
- [ ] C4 导入先在隔离暂存区解密、校验版本/摘要/引用/空间。拒绝路径穿越、重复条目、超限声明、恶意膨胀、缺文件、未知格式、错误口令和坏库；失败不改变现有状态。
- [ ] C5 恢复预览绑定文件摘要和当前数据版本。正式提交再次检查无通话/采集，封口并排空旧 owner，关闭数据库及文件访问。跨数据库/文件/偏好采用持久恢复日志，启动时先完成或回滚恢复，再创建正常业务图；不能把多次 rename 声称为一个原子事务。
- [ ] C6 新 owner 发布新的 epoch/incarnation；旧 owner 迟到结果不得写入。恢复后的待处理录音暂停为需用户重试的明确状态，不恢复网络在途操作，不重复导入被删除归档。
- [ ] C7 测试导出→新安装隔离测试包恢复→数据库重开读回；覆盖不含音频、包含音频、自定义铃声、配置历史、ID/序列保留、权限未恢复、口令错误、空间不足、取消。
- [ ] C8 在暂存完成、各切换节点和 owner 重建节点注入进程终止；启动后只能得到完整旧数据或完整新数据，不能混合。恢复前后不得意外向模型服务发出请求。

自动云备份继续禁用。若 C 的跨资源恢复达不到故障恢复标准，1.0 不开放恢复入口，不以只做 CSV 导出宣称完成此项。

## 5. 工作包 D：存储统计与安全清理

**范围默认值：** 1.0 先提供手动清理，不自动按保留期删除。展示自有音频、数据库、可清理临时文件和更新缓存占用；允许按日期及状态选择录音归档，预览数量与可释放音频字节，再显式确认。数据库删除后逻辑空间可复用，不承诺文件立即缩小。

**拟新增文件：** `contracts/.../storage/StorageManagementApi.kt`、`backend/.../application/storage/StorageManagementService.kt`、`app/.../platform/storage/AndroidStorageInventory.kt`、`ui/.../feature/storage/StorageManagementScreen.kt` 与 ViewModel。复用 A 的有界元数据读取和既有 `RecordingArchiveCommands` 删除协议。

**执行与验收：**

- [ ] D1 补磁盘占用和剩余空间观察；读失败显示未知，不能显示 0。统计不读取全文，不扫描未授权目录，不向 UI 或模型暴露私有路径。
- [ ] D2 批量清理预览绑定目标 ID、generation、范围和有效期；确认前重新检查，目标改变要求刷新预览。正在录制、处理或播放的归档默认排除，并明确展示排除数量。
- [ ] D3 复用现有文件删除→归档删除→防重导入标记与发布语义，按小批执行；逐项回执保留成功/失败/未开始结果，取消仅停止未开始项，不声称已完成项可撤销。
- [ ] D4 默认只清理用户明确选中的归档和确认安全的过期临时文件，不删除系统原录音，不清理当前导入/备份/恢复/下载使用中的文件，不删统计事实或不可变审计。
- [ ] D5 低空间时在导入、采集和备份前复核可用空间；并发消耗仍可能导致写入失败，须保留原数据和明确错误，不通过自动删除旧归档腾空间。
- [ ] D6 验证部分失败、文件已丢失、权限异常、删除时来电、取消、旧结果迟到；按实际变化精准刷新录音及占用相关查询，无变化不推进版本。新增失效依赖先审查，不以全局广播替代。
- [ ] D7 使用 10,000 条元数据进行分页清理演练，验证全文解码增量为零、内存有界；实际释放量与预估差异可解释。

## 6. 工作包 E：首用配置与异常状态

**范围默认值：** 可跳过的配置清单，不强制向导、不一次索取全部权限。首页仅在核心能力缺失/未知时展示状态提示及“检查”入口；正常状态不新增常驻大卡片。复用现有“运行状态与权限”、授权宿主和悬浮窗测试，不能用测试卡片成功证明真实来电正常。

**修改/新增文件：** `ui/.../feature/home/HomeScreen.kt`、`HomeViewModel.kt`、`ui/app/CallringApp.kt`、`ui/feature/settings/RuntimeScreen.kt`，新增 `ui/.../feature/setup/SetupViewModel.kt`、`SetupScreen.kt`；能力就绪规则若需业务投影，放入 `backend/.../application/runtime/` 并通过 contracts 暴露，不能在多个页面重复实现。

**执行与验收：**

- [ ] E1 建立就绪矩阵：来电识别角色与电话状态为核心条件；通知按系统适用性展示；联系人缺失说明覆盖限制；悬浮窗、录音目录和麦克风仅在启用对应功能时提示。
- [ ] E2 系统静音、振动、音量和系统勿扰显示当前声音条件，不自动改变用户设置；电池豁免与厂商后台设置单独标为后台可靠性建议，不伪装成标准授权或绝对就绪保证。
- [ ] E3 每步复用 ForegroundActionHost/既有宿主授权入口；仅用户点击才打开系统设置。拒绝、永久拒绝、不可用、未知分别表达，跳过后不反复弹窗。
- [ ] E4 返回前台、覆盖升级及权限变化后重查真实状态；不只保存“用户曾点完成”。若保存跳过标记，仅属于本地 UI 偏好，不写入业务事实。
- [ ] E5 保留首次加载和错误状态，已有状态刷新不插入页面进度条；旋转不重复启动授权。正常状态首页保持简洁。
- [ ] E6 补就绪矩阵单测、首页结构及 UI 测试；隔离设备覆盖拒绝、设置授予、返回应用、撤销和旋转。记录厂商设置无法标准探测的边界。

## 7. 每包验证和交付

- [ ] 实施前确认功能归属、真实声明及全部消费者；以失败用例固定缺陷或新增行为，再实现最小完整切片。
- [ ] 先运行对应定向测试，再运行根目录 `python tools/verify_backend.py`，要求对应最终 SHA 的完整证据。UI 设备流程使用 `python tools/verify_ui.py --device ADB_SERIAL`，执行前确认真实序号和设备状态。
- [ ] B/A 补时区和分页；C 补跨库一致性、恢复故障注入、旧数据迁移；D 补并发删除及取消；E 补权限生命周期。付费模型不是这些任务的必要验收手段。
- [ ] 设备验证遵守隔离包限制，不覆盖生产安装、不改主包角色、不清生产数据。真实生产备份的试恢复不得直接在用户安装上进行。
- [ ] 正式契约变化逐项评审并精准更新机器基线，AI 目录不自动增加备份、恢复、批量清理或授权工具。
- [ ] 每包更新 PROJECT.md 的协议/验收摘要和 README 的用户行为说明，审查本次差异，再按 callring-delivery 提交、合入 main、推送并核对该 SHA 的 CI。
- [ ] 计划文档不升级版本；实际代码发版按最终影响定版本。完成五包也不直接改为 1.0.0：真实来电、长待机和应用内更新仍须独立放行。

## 8. 实施前评审点与依赖

| 评审点 | 推荐默认值 | 必须在何时落实 |
| --- | --- | --- |
| 备份恢复模式 | 全量替换，不合并；自动分析恢复后关闭 | C1 格式与字段清单冻结前 |
| 备份机密性 | 可换机口令加密，排除所有密钥与设备授权 | C2 加密格式安全审查前 |
| 清理策略 | 手动选择与预览，默认无自动保留期 | D1 接口冻结前 |
| 首页提示 | 异常时出现，健康时收起，不强制向导 | E1 就绪矩阵评审前 |

本轮完成标准是交付可审查计划。上述产品默认值仍为建议；后续实施应先确认设计，不将本计划的存在视为批量删除、恢复用户数据或付费模型调用的执行授权。
