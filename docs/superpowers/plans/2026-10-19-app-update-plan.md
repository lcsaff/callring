# 应用更新与发布实施计划（P3）
状态：可执行计划，2026-10-19。执行按[并行开发计划](2026-10-19-parallel-execution-plan.md)的 S4 工作包；签名、真实下载、覆盖升级与发布演练独立执行。

## 1. 范围与依赖
- 设计依据：C（`../specs/2026-10-19-app-update-design.md`）§一/§三–§九；事实依据 D（`../specs/2026-10-19-external-facts-verification.md`）§2/§3；设置入口归属 A（`../specs/2026-10-19-compact-main-ui-design.md`）§6。
- 本文覆盖：keystore 门槛、版本单一来源与 signingConfigs、更新契约、GitHub 检查、元数据、下载、安装前校验与安装、更新页状态机、设置入口、发布工具与 CI、单测/真机/发布验收。
- 不覆盖：设置首页布局与四个主页面（A）、AI 请求策略（B）、Play 内更新实现、数据迁移方案（keystore 丢失时另立）。
- 并行总则：除 S01（签名门槛）与 S13（真机/发布）外，其余步骤均“可并行（与 P1/P2）”；`BackendGraph.kt`、`SettingsOverviewScreen.kt`、`SettingsRoute.kt` 等共享文件按 §5 收口。简称：B=`backend/src/main/kotlin/com/greatwall/callring/`，P=`app/src/main/kotlin/com/greatwall/callring/`，U=`ui/src/main/kotlin/com/greatwall/callring/ui/`；行号取材 2026-10-19 快照，可能漂移，以符号名与文件为准。

## 2. 前置事实（已核实）
| # | 事实 | 来源 |
| --- | --- | --- |
| 1 | 仓库 `lcsaff/callring` 公开、default_branch `main`；正式 Release `v0.2`（draft/prerelease=false，published 2026-10-19T03:49:30Z），`/releases/latest` 现返回 v0.2 | D §2 |
| 2 | 资产 `callring-0.2.apk` 33,570,356 字节；APK SHA-256 `103bfebcc4ed8d326d5d48e9dc3b3c893a203e897cc62a456598a05c88278772`；`SHA256SUMS.txt` 84 字节 | D §2 |
| 3 | 发行签名证书 SHA-256 `ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`；独立发行密钥，debug 包不可覆盖安装 | C §一.5 |
| 4 | 线上最高 versionCode 2 / versionName 0.2；本计划定 `0.3.0` / versionCode 3，标签 `v0.3.0` | C §六 |
| 5 | `app/build.gradle`：applicationId `com.greatwall.callring`、minSdk 29、targetSdk 33、compileSdk 36、versionCode 2、versionName `'0.2'`、`backendQa` 带后缀、无 signingConfigs；app 无任何 `androidx.*` 依赖；Manifest 无 `REQUEST_INSTALL_PACKAGES`/FileProvider，`app/src/main/res/xml/` 已存在 | D §5 |
| 6 | 契约默认值模式：`CallringUiApi` 用 `val x: XApi get() = UnavailableXApi`；平台实现在 `BackendGraph` 的 `object : CallringUiApi by backend` 中装配 | `CallringUiApi.kt`、`BackendGraph.kt` |
| 7 | 设置首页 6 张卡，“系统与支持”仅“运行状态与权限”；`SettingsSummary` 无版本字段；`SettingsPage.entries` 统一生成子页路由 | `SettingsOverviewScreen.kt`、`SettingsRoute.kt`、`UiDestination.kt` |
| 8 | 速率限制：未认证 60/h、**缺 User-Agent 直接 403**、限流 403 带 `X-RateLimit-*`；`make_latest` 语义见 D §3；无 `.github/`、`tools/` 无发布/签名脚本；后端是 `java-library`，测试任务为 `:backend:test` | D §2/§3/§5 |

门槛结论：S01 只阻塞**签名配置、发布演练与真机覆盖升级**（S02 的 signingConfigs 部分、S11 的签名/发布步骤、S13）；S02 的 `version.properties` 与 Gradle 版本读取、S04–S09 的契约/查询/元数据/下载/校验/更新页实现、S12 的测试都可先做。除 S01、S13 外可并行，两处主收口点（A 的设置入口、B 的 `BackendGraph`）不并行。

## 3. 阶段步骤表
| 阶段 | 步骤 | 目标 | 并行/收口 |
| --- | --- | --- | --- |
| 门槛 | S01 | 取得并核对 v0.2 发行 keystore 指纹 | 不可并行（签名门槛） |
| 版本与签名 | S02 | `version.properties` 单一来源 + signingConfigs + QA 隔离 | 可并行（与 P1/P2） |
| 契约 | S03 | `AppUpdateApi` + `CallringUiApi` 默认实现 + 图装配 | 需收口：与 B（`BackendGraph.kt`） |
| 检查 | S04 | GitHub `/releases/latest`、UA/Accept/ETag、403/429/404 | 可并行（与 P1/P2） |
| 元数据 | S05 | `update.json` 校验与版本判断 | 可并行（与 P1/P2） |
| 下载 | S06 | DownloadManager、任务持久化、取消/恢复 | 可并行（与 P1/P2） |
| 校验 | S07 | size/sha256/包名/versionCode/minSdk/证书一致 | 可并行（代码） |
| 安装 | S08 | FileProvider、权限、未知来源授权与返回重检 | 可并行（与 P1/P2） |
| 更新页 | S09 | 14 状态机与字段、唯一主操作 | 可并行（仅新增 `ui/feature/update/`） |
| 入口 | S10 | 设置行 + 版本副标题 + 路由 | 需收口：与 A（SettingsOverview/SettingsRoute） |
| 发布链 | S11 | `tools/` 脚本 + `.github/workflows/`、失败即停 | 可并行（与 P1/P2） |
| 单测 | S12 | 矩阵用例 + 统一验证命令与计数 | 可并行（P1/P2 各自测试） |
| 验收 | S13 | 真机同证书 0.3.0→0.3.1 覆盖升级（v0.2 不可作为起点）+ 发布演练证据 | 不可并行（签名、真机验收） |

## 4. 每步详述
### S01 发行身份与备份（第一门槛，已定案）
- 动作：v0.2 发行密钥已确认丢失（2026-10-19），不再寻找。改为把本机 Android debug 证书作为当前发行身份：确认 `C:\Users\GreatWall\.android\debug.keystore` 存在（2618 字节）、证书 SHA-256 = `5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4`；把 keystore 备份到仓库外并记录文件 SHA-256；用该 keystore 签一个 versionCode 递增的测试包，`apksigner verify --print-certs` 复核指纹；在**本机现有 debug 安装**上覆盖升级，确认数据保留；记录“v0.2 不可覆盖升级”的既有事实。
- 涉及文件：仓库内无改动；`signing.properties`（gitignore）与备份在仓库外；`apksigner` 取自 build-tools 36.0.0。
- 验收：备份文件存在且记录摘要；测试包 `--print-certs` 指纹等于 `5a901cad…24b4`（DN `CN=Android Debug`）；覆盖本机 debug 安装成功、档案/设置/录音保留；命令与日志不含真实口令。
- 完成定义：备份可恢复、指纹一致、覆盖升级通过 → 解锁签名配置/发布演练/真机覆盖升级；任一失败 → 这些步骤阻塞（不得改用新证书绕过：本机现有数据将无法保留）。**不阻塞** S02 的版本文件、S04–S09 的实现与 S12 的测试。
- 并行：不可并行（签名门槛，未通过即全局停止）。

### S02 版本单一来源与 signingConfigs
- 动作：新增根 `version.properties`（versionCode=3、versionName=0.3.0）作为唯一来源，根 `build.gradle` 读取并注入 `app/build.gradle` 以替换硬编码；`app/build.gradle` 增 `signingConfigs`：release 指向 **debug keystore**（storeFile/alias/口令从被 gitignore 的 `signing.properties` 读取，不提交私钥；CI 存在时读 Secrets）；`backendQa` 沿用 debug、保留 `-backend-qa` 后缀，不参与正式版本。
- 涉及文件：`version.properties`（新增）、根 `build.gradle`、`app/build.gradle`；仓库 `gradle.properties` 只放非密配置。
- 验收：改 `version.properties` 后 `:app:assembleDebug` 产物 versionCode/versionName 跟随（`aapt2 dump badging` 或 `apkanalyzer manifest version-*`）；`:app:assembleBackendQa`（任务名以实际为准）产物 applicationId 以 `.backendqa` 结尾、versionName 带 `-backend-qa`；`git status` 与 diff 无密钥/口令。
- 完成定义：版本单一来源生效、QA 变体不读正式 signingConfig、仓库无敏感物。
- 并行：可并行（与 P1/P2）；`app/build.gradle` 由 P3 独占。

### S03 更新契约与图装配
- 动作：新增 `B/application/api/AppUpdateApi.kt`（当前版本展示、检查结果与状态、下载/取消/安装操作，统一返回 `ApiResult`）；`CallringUiApi.kt` 增 `val update: AppUpdateApi get() = UnavailableAppUpdateApi`；默认实现以既有 `ErrorCode.SYSTEM_UNAVAILABLE` 类失败返回、不抛异常；在 `P/bootstrap/BackendGraph.kt` 的 `object : CallringUiApi by backend` 与 `boundApi` 两处 override 装配平台实现（实现在 S04–S08 填充）。
- 涉及文件：`AppUpdateApi.kt`（新增）、`CallringUiApi.kt`、`P/bootstrap/BackendGraph.kt`、`P/platform/update/`（新增，先最小实现）。
- 验收：`:backend:test` 与 `:app:compileDebugKotlin` 通过；新增契约测试断言默认实现返回失败而非崩溃；现有 `CallringUiApi` 委托行为不变。
- 完成定义：后端无 Android 依赖、默认实现可用、图装配完整且不破坏既有 API。
- 并行：需收口：与 B（`BackendGraph.kt` 同一文件，先到先改、后到者 rebase）。

### S04 GitHub 检查客户端
- 动作：实现 `GET https://api.github.com/repos/lcsaff/callring/releases/latest`，固定 `User-Agent: Callring/<versionName>` 与 `Accept: application/vnd.github+json`，持久化 `ETag` 并发 `If-None-Match`（304 复用缓存）；403 分两类（缺 UA/权限错误 vs `X-RateLimit-Remaining: 0` 的限流），429 读 `X-RateLimit-Reset`/`Retry-After` 给恢复时间、不自动反复重试；404 显示“无法确认最新版本”并提供 Release 网页入口；防御性忽略 draft/prerelease；不内置令牌。
- 涉及文件：`P/platform/update/`（新增 GitHub 查询与 ETag 缓存文件，按职责拆分）、`app/src/test/.../platform/update/`（新增测试）。
- 验收：`:app:testDebugUnitTest --tests '*Update*'`（按实际类名过滤）用本地 HTTP 替身覆盖 200/304/缺 UA 403/限流 403/429/404/异常 JSON/draft/prerelease；断言请求头、304 不重新解析、恢复时间字段。
- 完成定义：错误可区分、可展示、可重试；单测零真实网络、零令牌。
- 并行：可并行（与 P1/P2）。

### S05 update.json 校验与版本判断
- 动作：从 Release 资产定位 `update.json`，校验 schemaVersion=1、applicationId=`com.greatwall.callring`、versionCode/versionName/minSdk 类型与范围、`assetName` 与 Release 中 APK 附件**唯一匹配**、`sizeBytes`>0、`sha256` 为 64 位 hex；仅远端 versionCode > 本机 versionCode 提供更新，相等/更低 → `UP_TO_DATE`；缺元数据/缺匹配 APK → `NO_INSTALLABLE` + 网页入口；`minSdk` 高于设备 → `UNSUPPORTED_DEVICE`。
- 涉及文件：`P/platform/update/`（新增元数据解析/校验文件）、对应单测。
- 验收：`:app:testDebugUnitTest --tests '*UpdateMetadata*'`（按实际类名）覆盖高/同/低版本、缺附件、缺元数据、draft、系统不满足、异常 JSON、assetName 不唯一、sha/size 非法；断言 versionName 更大但 versionCode 相等时不提示更新。
- 完成定义：每个非法字段有可展示原因；更新判断只按 versionCode；源码 zip/tar 不被当安装包。
- 并行：可并行（与 P1/P2）。

### S06 下载与状态持久化
- 动作：用系统 `DownloadManager` 下载 Release 资产直链到 `setDestinationInExternalFilesDir(..., DIRECTORY_DOWNLOADS, "update/<assetName>")`，调用 `setVisibleInDownloadsUi(false)`（新系统行为见 §8 风险 3）；持久化任务 ID 与目标 versionCode/versionName/assetName/sha256（更新专用存储）；页面返回/进程重启按任务 ID 查询恢复进度；取消 `remove(id)` 并删除目标文件；重复点下载复用既存任务。
- 涉及文件：`P/platform/update/`（新增下载器与状态持久化文件）、对应单测。
- 验收：`:app:testDebugUnitTest --tests '*UpdateDownload*'`（按实际类名）覆盖任务恢复、未知总量进度、取消后无残留、重复请求单任务；路径仅 `<external-files>/Download/update/`。
- 完成定义：恢复与清理语义可单测；清理只作用于更新目录，不触碰档案/设置/录音存储。
- 并行：可并行（与 P1/P2）。

### S07 安装前校验
- 动作：下载完成依次校验文件大小=`sizeBytes`、流式 SHA-256=`sha256`；`getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` 取包名/versionCode/longVersionCode/minSdk/签名证书，与 `getPackageInfo(当前包名, GET_SIGNING_CERTIFICATES)` 的当前应用证书比对 SHA-256；包名、versionCode、minSdk、签名任一不符 → `VERIFY_FAILED` 且允许重新下载；全部通过 → `VERIFIED`。
- 涉及文件：`P/platform/update/`（新增 APK 校验文件）、对应单测。
- 验收：`:app:testDebugUnitTest --tests '*ApkVerifier*'`（按实际类名）覆盖 size/sha256/包名/versionCode/minSdk/签名不符各一例；篡改字节与换签名重打包的实测归 S13。
- 完成定义：缺任一项不得进入 `VERIFIED`；证书比对语义（待装包签名证书与当前应用一致）写入测试。
- 并行：可并行（与 P1/P2；签名不符真机实测归 S13）。

### S08 安装链路与 Android 配置
- 动作：Manifest 增 `REQUEST_INSTALL_PACKAGES` 与不导出的 `androidx.core.content.FileProvider`（authority `${applicationId}.fileprovider`、`grantUriPermissions="true"`、`android.support.FILE_PROVIDER_PATHS` 指向新增路径 XML）；路径 XML 增 `external-files-path`（`Download/update/`）；`app/build.gradle` 显式加 `androidx.core:core`（ui 的 `implementation` 不传递）；安装前用 `canRequestPackageInstalls()` 实时检查，未授权跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 应用详情页并在返回后重检；用 `FileProvider.getUriForFile` + `ACTION_VIEW` 拉起安装器并只授 `FLAG_GRANT_READ_URI_PERMISSION`；安装结果以系统信息确认（回页重读 PackageManager，或复用 Manifest 已有 `MY_PACKAGE_REPLACED` 接收）——不按按钮推断“已安装”。
- 涉及文件：`app/src/main/AndroidManifest.xml`、`app/build.gradle`、`app/src/main/res/xml/`（新增路径 XML）、`P/platform/update/`（新增安装器）。
- 验收：`:app:assembleDebug` 通过；`apkanalyzer manifest print` 或 `aapt2 dump xmltree` 断言权限、Provider、authority 与路径；`backendQa` 变体不启用更新入口（S10）；授权前/后与取消安装行为归 S13。
- 完成定义：安装器可打开、Provider 不导出、QA 不冲突；取消安装/存储不足/文件被清理均有状态且可重试。
- 并行：可并行（与 P1/P2；Manifest 与 `app/build.gradle` 由 P3 独占）。

### S09 更新页状态机
- 动作：新增更新页与 VM，固定展示当前安装版本（versionName + longVersionCode，取系统应用信息）、可用版本、发布日期、更新说明、APK 大小；实现 C §四状态集合 `IDLE/CHECKING/UP_TO_DATE/UPDATE_AVAILABLE/UNSUPPORTED_DEVICE/NO_INSTALLABLE/CHECK_FAILED/DOWNLOADING/VERIFYING/VERIFIED/VERIFY_FAILED/INSTALL_PERMISSION_REQUIRED/INSTALL_CANCELLED/INSTALL_PENDING_VERIFY`，每个状态唯一主操作；仅用户主动检查，无启动弹窗、无后台轮询；未知异常回落 `CHECK_FAILED` 并保留重试。
- 涉及文件：`U/feature/update/AppUpdateScreen.kt`、`AppUpdateViewModel.kt`（新增）、`ui/src/test/.../feature/update/`（新增测试）、`UiFakeApi.kt`（扩展，见 §5）。
- 验收：`:ui:testDebugUnitTest --tests '*AppUpdate*'`（按实际类名）断言 14 状态各自唯一主按钮与文案、`UP_TO_DATE`/`NO_INSTALLABLE` 无下载按钮、未校验通过不出现安装按钮、`VERIFY_FAILED` 可重新下载；`:ui:lintDebug`。
- 完成定义：页面只经 `AppUpdateApi` 访问能力，不直连网络/安装器；状态不由 UI 自行推断。
- 并行：可并行（与 P1/P2；仅新增 `ui/feature/update/`）；测试替身需按 §5 收口。

### S10 设置入口接线
- 动作：`SettingsPage` 增 `UPDATE`；`SettingsOverviewScreen.kt` “系统与支持”卡在“运行状态与权限”后加“检查更新”行，副标题从 `PackageManager` 读取当前安装 versionName（`SettingsSummary` 无版本字段，禁止由后端摘要提供）；`SettingsRoute.kt` 增 UPDATE 分支挂更新页；复用 `SettingsPage.entries` 既有路由（无需改 `UiDestination` 结构）；`backendQa`（applicationId 以 `.backendqa` 结尾）隐藏该行。
- 涉及文件：`SettingsOverviewScreen.kt`、`SettingsRoute.kt`、设置页测试（`SettingsScreensJvmTest`/`SettingsScreenCases`，随 A 的改动同步）、`UiFakeApi.kt`。
- 验收：`:ui:testDebugUnitTest --tests '*Settings*'` 断言行文案、副标题等于 PackageManager 值、点击进入 UPDATE 页；`backendQa` 变体入口不可见（Robolectric 或设备）；`:ui:lintDebug`。
- 完成定义：入口只在正式变体出现、版本不硬编码、点击可达更新页。
- 并行：需收口：与 A（`SettingsOverviewScreen.kt`/`SettingsRoute.kt`；A 先落布局与路由，P3 后 rebase）。

### S11 发布工具与 CI
- 动作：`tools/` 新增版本准备（校验版本格式与 versionCode 递增，拒绝 `0.3`/`v0.3.0` 混用与相同 versionCode）、签名校验（证书指纹 + `zipalign`）、`update.json`+`SHA256SUMS.txt` 同源生成（从签名后 APK 生成，拒绝 QA 包与 applicationId 不符包）、四者一致性校验、发布后 latest 核对脚本；`.github/workflows/` 新增发布工作流：Secrets（`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`）未配置即非零失败，禁止 debug/未签名包，按 C §七 7 步失败即停。
- 涉及文件：`tools/`（新增脚本，名称实现时定）、`.github/workflows/`（新增工作流，文件名实现时定）。
- 验收：固定样例（错标签、附件缺失、包名不符、摘要不符、版本未递增、Secrets 缺失、QA 包混入）逐项非零退出且无 Release 被发布；正常样例演练产出 3 个附件且 latest 指向本次 tag（真实发布归 S13）。
- 完成定义：本地脚本与 CI 首跑可复现；任何一步失败不产出半成品 Release。
- 并行：可并行（`.github/`、`tools/` 与 P1/P2 无冲突）；真实发布演练不可并行。

### S12 单测矩阵与统一验证
- 动作：把验收矩阵落成用例：高/同/低版本、缺附件、缺元数据、draft、系统不满足、403 两类、429 与恢复时间、404、异常 JSON、304、下载恢复、取消清理、摘要不符、签名不符；跑 §7 统一命令并记录退出码与三处 XML 计数；同步 `README.md`/`PROJECT.md` 验证说明（补 `:backend:test`）。
- 涉及文件：各模块测试类；`README.md`、`PROJECT.md`（与 A 收口，见 §5）。
- 验收：统一命令退出码 0；`backend/build/test-results/test`、`ui/build/test-results/testDebugUnitTest`、`app/build/test-results/testDebugUnitTest` 的 tests>0、failures=0、errors=0，记录 skipped；空测试集不算通过。
- 完成定义：矩阵每项有对应用例且绿；Debug 通过不写成“更新链路已验收”。
- 并行：可并行（与 P1/P2 测试各自执行），执行时点在 S01–S11 之后。

### S13 真机覆盖升级与发布演练
- 动作：隔离测试设备以本机现有 debug 安装（或同一 debug 证书签名的 0.3.0 测试包）为起点（**v0.2 正式包不可作为起点**：发行密钥丢失、签名不兼容），用 versionCode 递增的同证书包（如 0.3.0 → 0.3.1）覆盖升级；走完应用内检查→下载→取消→重试→进程重启恢复→未知来源授权→取消安装→安装成功；另做一次真实 Release 演练并核对 `/releases/latest`；记录机型/系统、前后 `dumpsys package`、档案/设置/录音抽查与截图/日志。禁止覆盖日常设备。
- 涉及文件：证据归档（路径实现时定）；不新增代码。
- 验收：包名不变、versionCode 递增、档案/设置/录音保留；每步状态与错误可复现；发布证据齐全（§7 清单）。
- 完成定义：全部证据齐备才可写“已验收”；未完成项单列，失败保留产物与日志，不把编译/Debug 通过写成链路通过。
- 并行：不可并行（签名、真机验收与单向发布，独占设备与 Release）。

## 5. 共享文件收口清单
| 文件 | 收口方 | 规则 |
| --- | --- | --- |
| `P/bootstrap/BackendGraph.kt` | B | `object : CallringUiApi by backend` 与 `boundApi` 两处 override；先后落地，后到者 rebase，禁止并行改 |
| `U/feature/settings/SettingsOverviewScreen.kt`、`U/app/SettingsRoute.kt` | A | A 负责布局与路由，P3 只加 `UPDATE` 行与分支；同文件不并行 |
| `ui/src/sharedTest/.../testing/UiFakeApi.kt` | A / B / P3 | 三方分别追加自己 API 的替身；最后合入者 rebase，禁止整体重写 |
| `README.md` | A | A §10 修改旧表述；P3 只补验证入口 `:backend:test`；按 A 先改 |
| `app/build.gradle`、`app/src/main/AndroidManifest.xml`、`version.properties`、根 `build.gradle` | P3 独占 | A/B 不触碰；signingConfigs 与版本读取集中在此；`UiDestination.kt`/`CallringApp.kt` 只读，复用 `SettingsPage.entries` 路由（必须改时与 A 收口） |

## 6. 发布流程检查表（失败即停）
| 步 | 动作 | 失败处理 / 核对 |
| --- | --- | --- |
| 1 | 准备版本：脚本更新 `version.properties`，校验格式与递增（> 已发布最高 2，> 现装最高 2） | 非零退出，不进入构建 |
| 2 | 完整验证：`:backend:test`、`:ui:testDebugUnitTest`、`:app:testDebugUnitTest`、`:app:assembleRelease`、lint（`lintRelease` 可用则以 release 为准） | 任一失败即停；不签名、不上传 |
| 3 | 签名：固定发行证书签名，`apksigner verify --print-certs` 核对 `5a901cad…24b4`（当前发行身份=debug 证书），`zipalign -c -v 4` | 指纹/对齐不符即停，产物作废 |
| 4 | 生成元数据：从签名后 APK 生成 `update.json` 与 `SHA256SUMS.txt`，拒绝 QA 包/包名不符 | 生成失败或字段校验失败即停 |
| 5 | 一致性：APK、`update.json`、`version.properties`、标签 `v0.3.0` 四者互校（版本/assetName/摘要/大小/包名/签名） | 任一不符即停 |
| 6 | 上传草稿 Release（tag `v0.3.0`），核对附件名/大小/摘要 | 附件缺失或摘要不符即停，发布件不转正式 |
| 7 | 发布并 `PATCH make_latest: "true"`，随后核对 `/releases/latest` 的 `tag_name` 等于本次 tag | 不等即非零退出并记录两者；禁止依赖默认行为，禁止回退 |

## 7. 验收命令与证据要求
```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug
```

- 签名 Release 构建（`:app:assembleRelease` + `apksigner`/`zipalign`）、真实下载、覆盖升级**单独验收**，不得与 Debug 命令合并宣称；Debug/单测通过 ≠ 更新链路通过。
- 构建与测试证据：实际退出码；三处 XML 的 `tests/failures/errors/skipped` 计数（`backend/build/test-results/test`、`ui/build/test-results/testDebugUnitTest`、`app/build/test-results/testDebugUnitTest`）；空测试集不得记作通过。
- 发布证据：APK 字节数与 SHA-256、`apksigner --print-certs` 证书指纹、`zipalign` 校验结果、`update.json`/`SHA256SUMS.txt` 全文、`/releases/latest` 的 tag；设备证据：机型/系统 API、安装前后 `versionCode`、覆盖升级前后档案/设置/录音抽查、各状态截图或日志。未完成项与阻塞项单列，失败保留产物与日志。

## 8. 风险与未核实项
| # | 项 | 处理 |
| --- | --- | --- |
| 1 | 发行身份（**已定案**） | v0.2 密钥已确认丢失（2026-10-19）；发行身份=本机 debug 证书（`5a901cad…24b4`），S01 备份与覆盖升级验证；v0.2 不可覆盖，按无存量处理 |
| 2 | Play 上架政策原文未核对 | 本链路仅服务自更新/侧载；上架前按官方政策复核，改用 Play 内更新，不得写“一定被拒” |
| 3 | `setVisibleInDownloadsUi(false)` 在新系统行为未知（API 已标记废弃） | 真机确认系统“下载”应用不出现该条目；不满足则记录并调整策略 |
| 4 | `.github/`/Secrets 首跑与 latest 规则未实测（`make_latest` 语义） | 工作流首跑并保留“Secrets 缺失即失败”记录；发布后核对 `/releases/latest`，不按时间/版本号推断 |
| 5 | GitHub 服务端 asset digest 未取得 | 摘要唯一来源是 `update.json`/`SHA256SUMS.txt` 同源生成 |
| 6 | 行号可能漂移 | 以符号名/文件为准；C/D 与本计划行号仅定位 |
| 7 | `lintRelease` 是否可用未核实 | 可用则以 release 变体为准；不可用记录原因 |

## 9. DoD
- [ ] S01 备份可恢复、测试包指纹等于 `5a901cad…24b4`、覆盖本机现有 debug 安装通过；不通过时阶段四停止（禁止改用新证书绕过，否则本机数据不可保留）。
- [ ] `version.properties` 为唯一来源，Debug/QA 产物版本符合预期，仓库无密钥/口令。
- [ ] 更新契约、GitHub 检查、元数据、下载、校验、安装链路实现完成且单测全绿（含 403 两类/429/404/304/签名不符）。
- [ ] `BackendGraph.kt` 与 B 收口、`SettingsOverviewScreen.kt`/`SettingsRoute.kt` 与 A 收口完成；更新页 14 状态唯一主操作，无启动弹窗/后台轮询；入口版本副标题来自 PackageManager，QA 变体隐藏。
- [ ] 发布脚本与 CI 7 步失败即停，Secrets 缺失即失败，`/releases/latest` 核对通过；§7 统一命令退出码 0，三处测试计数非空且无失败；README/PROJECT 验证入口已同步。
- [ ] 设备实测：同 debug 证书的 0.3.0→0.3.1 覆盖升级成功、数据保留、全链路证据齐全（v0.2 正式包不可作为升级起点：发行密钥丢失、签名不兼容）；未核实项（Play 政策、设备行为、`setVisibleInDownloadsUi`）未写成“已验证”。
