# callring（规则来电铃声）

## 项目名称
- callring

## 项目目的
- 按来电号码规则播放自定义铃声的独立 Android 应用原型。

## 当前状态
- incubating
- 2026-10-14 从 android-funs 拆分为独立项目。

## 默认工具
- codex

## 关键路径
- 项目根目录：`D:/Users/GreatWall/projects/callring`（用户指定位置）
- 发现入口：`D:/Users/GreatWall/workspace/projects/projects.json`
- Android 应用：`app/`，包名 `com.greatwall.callring`
- 使用与验证说明：`README.md`
- 设计与验证：`docs/plans/规则来电铃声_设计与验证.md`
- 设备测试、音频生成与发布校验：`tools/`

## 模块与职责
- `contracts`：纯 JVM 公共数据与能力契约；不依赖其他模块。
- `backend`：纯 JVM 的 `application` 业务编排、窄 ports 与 `domain` 算法；只依赖 contracts。
- `ui`：依赖 contracts 的 Compose `feature` / `state`、纯参数 `designsystem`、悬浮窗与组件。
- `app`：`bootstrap` 唯一装配、`bridge` UI 偏好与通知接线、`data` SQLite/偏好/文件、`platform` Android 能力适配、`entrypoint` 系统事件与生命周期编排。
- 根包六系统组件保留 Manifest、Job、PendingIntent 的原身份，资源由壳提供；runtime/jobs/recording/calling controller 各有唯一工作流。ScreeningController/IncomingCallController 继续使用 platform/calling 的既有唯一实现。
- [分层整理文件归属清单](docs/superpowers/specs/2026-10-20-layer-boundaries-file-map.md)记录该次整理的 main 文件归属；后续新增/拆分文件按上述职责放置。分层自动验收见[最终报告](docs/plans/layer-boundaries-evidence/acceptance.md)，后续隔离设备补验与剩余缺口见[设备复验记录](docs/plans/layer-boundaries-evidence/device-fix-review.md)。

## 后续开发约束

开工前明确：功能归属、复用入口、数据与订阅影响、验收方法；按需求范围实施。

1. **单向依赖**：项目模块依赖为 app 组合 ui/backend/contracts，ui 与 backend 仅依赖 contracts；domain 不依赖 application/contracts/Android/UI。公共契约放 contracts，业务放 backend，表现放 ui，装配、存储、平台适配与系统流程放 app；业务服务只接明确窄端口。
2. **业务唯一实现**：页面、AI、后台任务共用正式业务命令和查询；校验、事务与失效发布在统一业务入口负责。Screen 和系统组件壳不复制业务规则，工具执行器不另写一套业务。
3. **局部访问与精准刷新**：普通操作按需读写，不恢复全状态装载/差异保存；完整快照显式隔离，历史批更正明确必要范围。提交成功后通过唯一累计失效协议发布实际领域/实体；同时验证有关更新和无关查询增量为零。
4. **生命周期与并发**：明确资源创建/关闭、任务取消和写入排空顺序；已终止的资源所有者不得再初始化/重开资源，取消或过期请求不得回写/发布，已接收写入按约定排空后释放资源。保持锁顺序，网络请求不跨数据库事务，重试不得重复已提交操作。
5. **迁移删除与数据兼容**：最后消费者迁出即删除旧入口、转发、别名、缺能力默认和双实现。保留唯一必要的数据编码解释器；数据库变更有升级迁移，系统组件身份及外部协议变更显式评审，不以清库/重装替代兼容验证。
6. **AI 扩展完整约束**：新工具使用具名 OperationId 和唯一目录，明确参数、读写、作用域、确认及回执策略；写入按策略确认和读回验证，覆盖取消、重试、重复提交。外部协议兼容基线仅随审查后的协议变更更新，不为通过测试直接重生成。
7. **按风险验证与防回流**：规则测结果边界，存储测事务/旧数据/并发，刷新测真实 loader 次数，系统入口补隔离设备验证。新增依赖规则同步守卫正反例；真实来电/待机/重启/权限/升级单列实测，不用编译或单测冒充，明确未验证项。
8. **同步文档与交付**：版本以 version.properties 为唯一来源，进度以最新验收证据为准，变更同步职责/协议/验证文档。代码收尾运行下述统一验证，纯文档核对内容与链接；按范围提交、合并 main、推送并确认该提交 CI。保留用户改动和验证证据，按需发包；纯文档不递增版本。

## 启动注意事项
- 在本项目根目录运行 Gradle；不依赖 android-funs。
- JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、Compose BOM 2025.12.01；minSdk 29、compileSdk 36、targetSdk 33、Build Tools 36.0.0。
- SDK 通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或本地 `local.properties` 的 `sdk.dir` 配置。
- 版本号由根 `version.properties`（`versionCode`/`versionName`）单一驱动：根 `build.gradle` 读取并注入 `app/build.gradle`，发布工具读同一文件，发布标签为 `v<versionName>`。
- 统一构建与验证入口：`python tools/verify_backend.py`；`python tools/verify_ui.py` 使用同一完整任务表并保留 UI 专用设备流程。唯一清单与证据约定见 `tools/verify_common.py` 和 README。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；Release APK：`app/build/outputs/apk/release/app-release.apk`。
- Release 签名优先读取被 gitignore 的根 `signing.properties`（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`），缺失时回退本机 Android debug keystore；私钥与口令不入库。
- 发布校验脚本在 `tools/release/`（版本校验、`update.json`/`SHA256SUMS.txt` 同源生成、失败即停的发布一致性校验与失败样例自检）；CI 定义在 `.github/workflows/`（`release.yml` 签名 Secrets 缺失即失败，默认只构建校验不上传）。

## 已知限制
- 真实 SIM 来电、长时间待机、重启后行为尚待验证，详见 README。
- 当前发行身份是本机 debug 证书（证书 SHA-256 `5a901cad…24b4`）；v0.2 正式包的发行密钥已丢失，任何新构建都无法覆盖 v0.2 安装，覆盖升级只在同 debug 证书的 0.3.0 及后续版本之间成立。
- 应用内更新链路（下载/校验/安装）、真实下载与真机覆盖升级尚未验收：等 debug.keystore 备份与隔离测试设备后再执行，不能凭 Debug 构建或单测通过宣称更新链路通过。

## 统一验证与隔离设备
```powershell
python tools/verify_backend.py
# 可选，实际设备序号由执行时确认：
python tools/verify_backend.py --device ADB_SERIAL
python tools/verify_ui.py --device ADB_SERIAL
```
统一门包含两 Python 守卫与三组 unittest、contracts/backend/UI/app JVM、真实 UI 类路径、Debug/Release/backendQa/双 AndroidTest APK 和双 lint；CI 使用同一无设备入口。backendQa 与 UI 测试包隔离校验和 finally 清理只作用于各自包。设备结果不可由编译/单测替代；T14 审查与 T15 设备验收状态由对应证据记录。
