# callring（规则来电铃声）

## 项目与文档职责

独立 Android 来电管理应用，按号码规则播放自定义铃声，并提供档案、统计、免打扰、悬浮窗、组件、录音及 AI 沟通。状态 incubating，2026-10-14 从 android-funs 拆分；默认工具 codex。

- 根目录：`D:/Users/GreatWall/projects/callring`；发现入口：`D:/Users/GreatWall/workspace/projects/projects.json`。
- Android 包名：`com.greatwall.callring`；应用入口及资源在 `app/`。
- AGENTS.md 为规则入口；本文件集中维护全部项目规则、契约约定、验证与交付要求；README.md 仅说明功能、使用和用户可见限制。
- 有用的设计结论与验收摘要就地更新本文件，不引用其他文档作为当前规则入口。原有文档保留作历史参考，不因清理引用而删除。机器基线、测试、原始日志和产物保留各自用途。

## 模块与职责

| 模块 | 职责与依赖 |
| --- | --- |
| `contracts` | 纯 JVM 公共 DTO、命令、查询、回执、变更订阅与能力契约；不依赖其他模块，不包含存储、网络、平台适配、凭据和业务策略。 |
| `backend` | 只依赖 contracts；`application` 负责业务编排、窄 ports、查询投影及 AI 执行，`domain` 保存独立算法且不依赖 application/contracts/Android/UI。 |
| `ui` | 只依赖 contracts，包括 sharedTest/AndroidTest；`feature` / `state` 负责 Compose/MVVM，`designsystem` 只消费视觉参数，悬浮窗和组件复用正式 API。 |
| `app` | 组合上述模块；`bootstrap` 唯一装配，`bridge` 转换 UI 偏好与通知，`data` 持有 SQLite/偏好/文件及编码器，`platform` 适配 Android 能力，`entrypoint` 拥有系统事件和生命周期工作流。 |

`CallringApplication` 创建唯一业务图；主题偏好解释归 app/bridge，业务组件与表单状态归 ui/feature，纯视觉组件归 designsystem。六个根系统组件 RuntimeReceiver、CalendarSyncJobService、SyncJobService、RecordingJobService、CallCaptureService、RingPlaybackService 保留原 Manifest、Job、PendingIntent 身份与真实资源接线，工作流分别进入 RuntimeEventController、CalendarSyncJobController、CallLogSyncJobController、RecordingJobController、CaptureServiceController、PlaybackServiceController。ScreeningController / IncomingCallController 保持 platform/calling 的唯一实现。

## 后续开发约束

开工前明确功能归属、复用入口、数据与订阅影响和验收方法，按需求范围实施。

1. **单向依赖与窄能力**：遵守上述模块边界，业务服务只接明确窄端口，不接万能 Graph/Context/完整 API；全部 source sets 与真实编译类路径纳入守卫，测试 Fake 只在测试源集。
2. **业务唯一实现**：页面、AI、后台任务共用正式命令和查询；校验、事务、提交和失效发布归统一业务入口。Screen、系统壳、工具执行器不复制业务规则；算法变更验证实际消费者。
3. **局部访问与一致性**：普通操作按需读写，不恢复全状态装载/差异保存；完整快照显式隔离。聚合查询的 SQLite 数据来自同一读取事务，独立偏好和系统观察注明采样边界。更新仅写目标实际变化列，保留原始事实、未改编码、唯一约束和自增序列。
4. **精准刷新**：提交成功后通过唯一累计失效协议发布实际领域/实体；无变化不推进版本。订阅覆盖 DTO 的真实依赖、全局变化、时间/权限和恢复；同时验证有关更新及无关 loader 增量为零，不能用全局广播掩盖缺订阅。
5. **生命周期与并发**：先封口、取消可取消任务、排空已接收写入，再关闭资源；已终止 owner 不得重新初始化或开库。取消/过期请求不得继续写入或发布，迟到结果用 generation/版本隔离；取消等待不等于撤销已提交操作。保持锁顺序，网络不跨数据库事务，资源租期覆盖完整访问。
6. **迁移与兼容**：最后消费者迁出即删除旧入口、转发、别名、缺能力默认和双实现；保留唯一必要的数据编码解释器。数据库变更有升级迁移，不以清库/重装代替兼容验证。系统组件身份、外部协议和持久格式变更显式评审；内部签名变更同步迁移全部消费者。
7. **按风险验证**：规则测结果边界，存储测事务/旧数据/并发，刷新测真实 loader，系统入口补隔离设备验证。新增守卫同步违规与合规样例；缓存、编译、单测、模拟服务和真机结果分别记录，不互相替代。
8. **同步文档与交付**：变更同步本文件的职责、协议、验收摘要及 README 中的产品说明；保留用户改动和证据，按范围提交、合并 main、推送并确认该提交 CI。纯文档不递增版本，按需发包。

## 接口、存储与兼容约定

- 当前接口以 `contracts/src/main/kotlin/com/greatwall/callring/contracts/` 的生产声明为准，统一入口为 `CallringUiApi`；不维护第二份 Kotlin 声明或历史包别名。
- suspend API 可从主线程调用，实现自行调度阻塞工作；返回不可变快照，UI 草稿与基线分离。取消按 CancellationException 传播，不包装成保存失败；业务错误用稳定错误码，UI 负责文案和格式。
- UI 不接数据库、Cursor、Context、Activity、Intent 或全局静态业务入口；平台交互通过契约与宿主完成。保存成功表示持久化及约定的历史更正已提交，通知不依赖页面是否存活。
- `ChangeFeed.snapshots()` 是唯一累计协议；实体选择按域声明且不能为空，未选实体表示全域，epoch/incarnation 变化使所有选中 token 失效；禁止旧通知桥和第二 loader。
- 保留数据库表列语义、JSON 字段与版本、枚举/ID、归档引用、凭据和缺省行为；未传、null、空值以及密钥 Keep/Replace/Clear 不混同。历史更正按事件当时的排班/时区处理，保留原始分类与决策，覆盖跨午夜、跨年、夏令时和回滚。
- 设置修改成功静默：设置总览、号码规则、免打扰、工作时间、评分、铃声、职位/请假、录音及 AI 设置不显示保存成功文字或 Snackbar；已有数据的保存后刷新不展示加载进度，密钥保存也不展示进度条。保留首次加载、就地错误、输入校验、未保存退出和破坏性操作确认；连接测试、同步任务与应用更新保留各自操作状态。通用反馈组件仍被其他功能复用，不删除。
- UI 统一 Compose/MVVM 与 Material 3 交互；草稿、取消、错误和成功反馈保持一致，系统字体缩放、窄屏/键盘与窗口恢复需覆盖。主色和深浅模式独立，主按钮保留原始RGB，评分语义色不被主题覆盖；显示回退不写回偏好。
- 内置铃声使用稳定 BuiltInRingtone 枚举，app 平台统一映射五个 raw WAV 资源，built_in_ringtone 偏好缺失或未知时只显示回退为 MELODY，不主动写回；旧 custom/导入文件语义不变。restoreBuiltIn 可指定选项，缺省仍为原始旋律，保存经正式业务入口发布 RINGTONE 实际变化，重复选择不推进版本；选择内置铃声使较早导入准备失效。试听循环且无时限，previewSeconds=null 表示持续，选项与设置读回经同一接口。页面 LifecycleStartEffect 在停止或移出组合时封口并串行清理迟到试听；stopPreview 专用命令只停止试听，不能停止真实来电。恢复前台不自动播放，真实来电原有超时、焦点、权限及通话状态保护保留。
- 启动更新由 UI 应用级 StartupUpdateViewModel 复用 AppUpdateApi，每个主界面宿主会话只自动检查一次，旋转保留检查和关闭状态；无更新或失败静默，下载/校验/待安装状态不主动重查。新版本提示复用 designsystem 的 ConfirmationDialog（同类双按钮确认提示统一使用，关闭文案可配置），关闭只消费提示，确定进入更新页，不自动下载或安装。平台检查按 generation 与原状态快照隔离迟到响应，不能覆盖后续检查、下载或安装状态；不新增契约、偏好或失效域。
- 更新下载片段绑定发布的 versionCode、assetName、sizeBytes 和 SHA-256；网络中断及进程关闭保留，重启只恢复可继续状态，用户取消丢弃。继续下载校验 HTTP Content-Range，200 覆盖重下，416 最多回退一次；完成后重算整包摘要并复用包名、版本、SDK、签名验证。旧检查不能覆盖下载，已终止 owner 禁止发布结果；沿用现有持久状态格式与契约。AI 仅提供 update_status / update_check 两项平台观察工具，不开放下载或安装；不新增领域失效，投影不包含可调用地址、私有路径或原始失败细节。
- 号码规则为可空列表，每条包含号码前缀与号码总长度，任意一条命中即匹配，空列表表示全部不匹配；缺少配置时默认一条 3/5。前缀允许多位 ASCII 数字及前导零，可为空表示不限前缀，长度不得超过该条总长度（1..30）。页面每行并排编辑两项，通过 + 添加、删除，停输 500ms 后整组自动保存；长度输入留空按 5 校验和保存，不把非法值回退为默认。规则行使用紧凑单行输入框，简短提示置于框内，删除按钮与输入框本体居中对齐；保存期间继续编辑排队提交最新草稿，不覆盖迟到编辑，冲突保留草稿并提示重新加载。生产 matcher 统一注入，列表 OR 组合集中实现，页面、AI、号码检查及系统流程共用；单条长度/前缀比较最多检查 30 位，不因性能限制为一位前缀。冲突按完整列表与持久版本检查，同次来电保留首次匹配事实、同批导入用同一不可变规则快照；保存不删除或重算旧记录。同步号码检查前先等待规则加载，恢复/关闭时不接受旧结果。单条 JSON version=1 读取为一条列表且保留 revision，version=2 存 rules/revision；显式空列表不得回退默认。兼容读取仅在唯一规则编码器中实现，不保留另一套业务接口。
- 生产接口由架构守卫核对审查后的声明摘要与内部签名变更标识；外部 AI 协议基线为 `docs/contracts/ai-operation-compatibility.json`。变更经评审后精准更新，不为通过测试直接重生成；机器校验数据不作为另一套规范入口。

## AI 接入约定

- AI 沟通输入草稿归 ui/feature/conversation，以进程内存状态跨页面及宿主重建保留，不写 SavedState、偏好或数据库；进程重启清空。新建会话、继续任务及发送失败不清草稿；发送成功仅清除仍与已发送文本一致的草稿，保留等待期间编辑的新内容，不新增契约或失效域。
- 模型负责理解与选工具，本地程序独立负责权限、参数、版本、事务、执行与核验；业务文字、转写、摘要及工具结果均视为不可信数据，不能授予权限。
- `OperationCatalog` 是唯一目录；所有操作声明稳定 OperationId、工具名、Schema、读写及绑定、作用域、确认和回执策略，启动拒绝缺声明或重复。当前 85 项（50 写、35 非写），身份/分派不用 ordinal、数字编号或展示顺序。`ToolRegistry.definitions` 为唯一发现入口，完整协议以冻结 JSON 与生产目录为准。
- X10/X11 的号码规则读取/保存使用 rules 数组（元素为 prefix/length）与 revision；保存携带完整 base 与期望 rules，AI 可通过整组替换增加、修改、删除或清空。保持 numberRule 精确作用域、D10 真实来源观察及完整持久列表/revision 读回，不能用返回值自证成功。
- 普通聊天不读业务数据；业务任务按需发现、分页、详情、全集聚合或码点分块。来源版本、游标、完整性和访问状态必须明确，EMPTY/PARTIAL/NOT_CONFIGURED/DENIED/UNAVAILABLE 不混同；当前页不冒充全集。外部 D01–D21 与 UI 失效域不是同一概念。
- 不向模型、日志、回执或保存的 UI 状态泄露密钥、私有路径和可调用 URI；文件只给短期句柄，使用前复核授权。凭据仅经本地表单修改，连接变化取消旧请求并核对页面已显示的目标。
- 写入依次校验 Schema、计划/依赖、权威目标及作用域版本、授权、提交前复核、正式业务事务、变更发布和读回。当前写操作要求前台宿主；删除、文件、权限、角色和密钥等按目录等待本地交互，一次性票据绑定目标/版本/期限，旋转或宿主恢复不得重复启动。
- 批次允许部分提交，逐项保留原始任务数量与回执；同执行 ID 改参拒绝，等价效果去重。VERIFIED 才计已核验完成；COMMITTED_UNVERIFIED 只补核验，UNKNOWN 先核对，QUEUED/LAUNCHED/PENDING 不宣称已完成；已提交步骤不因取消、读回失败或网络错误重放。
- 只修复明确允许且未开始步骤的参数错误（每步最多2次、每轮4次）；不修改目标/任务数量，不绕过确认。请求限制和完整性保护不能靠省略数据、减少数量或扩大权限规避。
- 默认 context=49152、output=4096；按实际 UTF-8 请求、Schema、历史、输出及安全余量计量，不等同精确 Token 数或模型容量。请求8MiB、回复1MiB，每轮最多16次模型请求/64工作量单位，工具批次最多32调用、档案批处理最多64子调用；旧显式配置保留。
- OpenAI/Anthropic 兼容适配保持 HTTPS 基础路径及代理前缀，仅追加端点，不自动补 `/v1`；转写只走兼容 audio/transcriptions。FAST/思考回放等兼容性以具体模型证据为准，不由协议名称推定。
- 当前 QWEN_FAST 限 OPENAI + qwen3.8-flash；不匹配返回 ai.profile_invalid，保留原配置并提示改 AUTO/STANDARD。STANDARD 不支持非空 reasoning_content 回放，不能推定 DeepSeek 思考模式或所有模型可用。
- 录音处理独立于聊天；上传、分段转写/分析、保存和重试遵循持久状态与 generation。网络不跨事务，删除阻止迟到回写，已有全文可复用；分段中间结果尚未持久化，失败可能重复调用片段。
- 正式业务 CLI、MCP、通用 SDK、流式文本及跨进程聊天账本尚未实现。后续扩展须共用目录、授权、执行和回执内核；无人交互时明确拒绝。跨进程写入先补持久执行 ID/主体/参数摘要、提交核对与恢复去重，不承诺跨系统 exactly-once。优化先测同任务/数据/模型配置，不删除读回或用全量加载换速度。

## 构建与统一验证

在项目根目录运行，不依赖 android-funs。工具链为 JDK17、Gradle8.13、AGP8.13.2、Kotlin2.3.21、Compose BOM2025.12.01；Java/Kotlin JVM目标1.8，minSdk29、compileSdk36、targetSdk33、Build Tools36.0.0。SDK 由 ANDROID_HOME / ANDROID_SDK_ROOT 或 local.properties 的 sdk.dir 配置；JDK 由 JAVA_HOME 配置。

```powershell
python tools/verify_backend.py
# 同一完整门，保留 UI 专用设备流程：
python tools/verify_ui.py
# 可选：执行时确认真实序号，先手动唤醒解锁并确认 ADB 在线。
python tools/verify_backend.py --device ADB_SERIAL
python tools/verify_ui.py --device ADB_SERIAL
```

- 唯一任务表在 `tools/verify_common.py`：两架构守卫及三组 Python unittest、contracts/backend/UI/app JVM、真实 UI 类路径、Debug/Release/backendQa/双 AndroidTest APK 和双 lint。代码收尾运行统一门；仅文档核对内容、链接与差异，涉及守卫调整时补对应正反例。
- Windows 保留实际工作树路径并调用 gradlew.bat；Linux/CI 使用 PATH 中的 Gradle8.13。每次运行先覆盖本轮 summary，失败/超时也写 argv、退出码、精确任务状态和 cleanup；区分 EXECUTED、UP-TO-DATE、FROM-CACHE、NO-SOURCE，不能用旧 XML 冒充本轮执行。
- 后端证据在 `app/build/qa/backend-refactor/`，UI 在 `ui/build/qa/`；保留匹配提交的日志、JUnit/lint、APK及摘要。contracts 无测试源码可单列 NO-SOURCE，业务模块不得以零测试或跳过冒充通过。
- 设备仅安装各自隔离包 `com.greatwall.callring.backendqa` / `com.greatwall.callring.ui.test`；核对 APK 包名和 metadata，临时权限及 app-ops 在 finally 恢复，只停止对应测试包，不清数据、不覆盖生产安装、不改主包角色或锁屏超时、不自动解锁。
- 普通 UI 测试排除 lockeddevice；锁屏专项须显式选择 `com.greatwall.callring.ui.lockeddevice.LockScreenOverlayDeviceTest`，小米测试包按需临时允许10020/10021并恢复。录音专项为 `com.greatwall.callring.LockedRecordingPipelineDeviceTest`。
- 真实模型及连接测试可能计费，付费执行需明确授权。`tools/verify_ai_device.py` 是验证工具，不是通用业务 CLI，不能为补历史诊断重复新增业务数据；私人截图和日志不入库或发布。

## 版本与交付

- 根 `version.properties` 是唯一版本来源；versionName 为 MAJOR.MINOR.PATCH，标签为 vMAJOR.MINOR.PATCH。根 Gradle 注入 app，发布工具读取同一文件；不在说明中维护另一份当前版本号。
- 版本修改使用 `python tools/release/version_tool.py set --code N --name X.Y.Z --published-max M`。修复/小改/内部重构升 PATCH，完整新能力升 MINOR 并清 PATCH；稳定验收后进1.0，之后不兼容升 MAJOR，0.x 不兼容升 MINOR 并说明迁移影响。versionCode 为已发行最高值加1，且高于目标安装；一个 Release 按最大影响定版本。纯文档/测试/开发工具/中间检查点不升版本，已分发版本不覆盖重用。
- Debug/Release APK 位于 `app/build/outputs/apk/debug/app-debug.apk` / `app/build/outputs/apk/release/app-release.apk`。Release 优先读取 gitignored signing.properties，缺失回退本机 debug keystore；私钥和口令不入库。当前证书 SHA-256 为 `5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4`；v0.2 密钥已丢失，新包不能覆盖它，同包名/同签名/versionCode递增才可覆盖升级，backendQa 不作正式发行。
- `.github/workflows/build.yml` 在 push/PR 运行统一门及发布工具自检；release.yml 手动触发、默认 publish=false，签名 Secrets 缺失即失败。本地验证不等于已上传或发布。

### AI 收尾流程（按序执行）

1. **定范围**：检查 `git status`，只处理本次文件，保留用户改动，同步文档并审查差异。默认提交、合入 main、推送 GitHub；用户要求打包/发版时再发布 Release。复用已有 GitHub CLI 与登录，检查 `gh auth status`。
2. **定版本并提交**：需发版时先查询已发布及已分发版本，按上述规则更新 `version.properties`，准备发布说明；按本次文件提交，记录源码 SHA。纯文档不升版本、不发 Release。
3. **验证最终源码**：代码运行 `python tools/verify_backend.py`，按风险补设备验证，记录未测项；纯文档只核对内容、链接及 `git diff --check`。同一 SHA 已有完整有效证据时复用，不重复构建；源码变更后重新验证。
4. **准备发行附件（仅发版）**：使用既有证书构建 Release；运行 `tools/release/check_workflows.py`、`tools/release/selftest.py`。用 `tools/release/generate_release_metadata.py --apk APK --out-dir STAGE` 生成独立目录内的 `callring-VERSION.apk`、`update.json`、`SHA256SUMS.txt`；用 `tools/release/verify_release.py --release-dir STAGE --tag vVERSION --published-max M --expected-cert-sha256 CERT --check-alignment` 校验版本、包名、签名、对齐、大小及摘要。VERSION 为本次版本，M 为已发行最高 versionCode，STAGE 为独立产物目录，CERT 为上述证书；APK 必须来自已验证 SHA。
5. **合并并推送**：获取远端 main，合入 main 并核对最终 SHA；快进且 SHA 未变时复用证据，SHA 改变则回到步骤3及适用的步骤4。推送 `origin/main`，确认该 SHA 的远端 CI 成功，不用其他提交的绿灯代替；清理已合并开发分支，保留工作树数据。
6. **发布并读回（仅发版）**：将 `vVERSION` 标签绑定已验证 SHA 并推送；创建 draft Release 并记录 ID，上传三个附件，核对远端名称、大小及 SHA-256 与本地一致，再发布并设为 Latest。读回 `/releases/latest`、标签及附件确认成功，交付版本、提交、CI、Release 和 APK 链接。签名 Secrets 齐全可用 release.yml；否则走本地校验后上传，不绕过校验或更换证书。

任一步失败即停止后续发布，保留产物和日志；网络结果未知时先读回再重试，不重复创建 Release、不覆盖已分发版本。外部阻塞如实说明，不把“已构建/已上传/草稿”报告成“已发布”。

## 已有验收摘要与剩余缺口

以下均为历史验证范围，不代表后续代码自动通过；当次交付以对应提交的新证据为准，原始机器证据仍在构建目录及既有归档中。

- **分层（2026-10-21，0.3.9/12）**：提交 `911369c82b657f0c224e6070ccd3005a8ac36c50` 的分层、局部读写、事务、累计刷新、唯一具名目录、窄依赖、生命周期与完整自动门通过；历史1036项JVM含缓存结果。旧全状态入口、双通知、数字业务分派和迁移转发已删除。
- **设备补验（0.3.10/13）**：后端31/UI83项通过，修复SQL实际列更新、白名单镜像和测试装配；同证书0.3.8/11→0.3.10/13覆盖升级、冷启动双宿主组件及重启后组件/Job恢复有实测。重启后相关电话权限被拒，不能推定通话监听恢复；约30分钟后台观察有用户短暂操作，仅算持续及恢复验证，不算无干扰待机。
- **真实AI历史样本（2026-10-19，`346e896`）**：qwen3.8-flash下闲聊零业务读取、一次新增10条合成档案及LIGHT→DARK通过，持久化读回保留；不代表当前版本/全部模型兼容。原运行缺逐步receipt ID、Schema/history数量及完整时序，不能补造，也未为补诊断重新付费执行。后续主题目录共享与空白聊天项修复通过自动门，未新增真机模型验证。
- **锁屏与录音（2026-10-23，候选0.4.2/16）**：完整门JVM1051项通过；隔离真机模拟响铃锁屏卡片1项、固定音频解码→模拟AI→SQLite全文重开读回1项、后端31项通过。未启动麦克风、未用真实网络AI、未验证JobScheduler自动唤起，普通UI整套当轮未跑；主安装未改。
- **可配置号码规则（2026-10-23，0.5.0/17）**：实现提交 `aa5b893` 后，规则/存储/消费者/AI/页面及冷启动加载屏障已接入，最终本地统一门通过；该功能设备验收未执行。
- **多条号码规则（2026-10-27）**：单条配置迁移、可空 OR 列表、并排行编辑/稳定焦点、完整列表 AI 读写及持久读回通过；统一门在 `1178a613d5b7cfc51e0158dbc46ce8e57bf61597` 通过，backend/UI/app JVM 为 412/359/377 项，均零失败/错误/跳过，13 项 Gradle 任务及双 lint 通过。当前无 ADB 设备，未进行真机来电及真实模型付费验证；日志、XML 和摘要保留在构建目录。
- **内置铃声与持续试听（2026-10-27）**：五首随包 WAV、稳定枚举选择与旧偏好回退、原子保存/失败回滚、过期导入隔离、无变化不刷新、持续循环与真实来电保护已接入。页面后台/移出组合、前台恢复不重播及迟到启动清理通过自动测试；空闲停止试听会退出服务。合入远端号码规则调整后的源码 `22c63b559f709c84034b80bfc4b7c1bee658e903` 在独立工作树完整统一门通过，backend/UI/app JVM 为 417/366/394 项，零失败/错误/跳过，13 项 Gradle 任务（contracts 为 NO-SOURCE）及双 lint 通过。无 ADB 设备，未验证真机扬声器、后台策略或真实来电；证据另存主工作区 app/build/qa/ringtone-20261027/22c63b5/，不将资源格式与模拟生命周期测试当作真机试听。
- **仍未完整验收**：真实SIM来电及拨号器竞争、双方录音及听筒/蓝牙效果、锁屏真实通话到真实AI全链路、权限授权条件下重启监听、无干扰长待机、真实系统交互逐项确认、完整网络下载/摘要/安装更新链路。公开APK下载曾超时并取消；已有同证书覆盖升级证据不等于应用内更新通过。首次开机解锁前不支持存储访问。
