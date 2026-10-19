# 应用更新与发布设计（2026-10-19）

状态：可实施设计规范。本文把 [紧凑主界面设计](2026-10-19-compact-main-ui-design.md)（下称 A）的“当前版本与发布现状 / 更新技术方案 / 版本与签名管理 / 阶段三、四”拆出，并用 2026-10-19 核对事实替换原文的“未核实”表述。范围：GitHub Releases 更新源、发布元数据、应用内下载与安装、版本与签名、发布流程。UI 布局与设置页结构见 A；AI 请求策略见 [2026-10-19-ai-request-policy-design.md](2026-10-19-ai-request-policy-design.md)（下称 B）；事实来源与原始 API 结果见 [2026-10-19-external-facts-verification.md](2026-10-19-external-facts-verification.md)（下称 D），本文只引用结论。

拆分边界：

| 内容 | 归属 |
| --- | --- |
| 设置页分组、四个主页面导航与“检查更新”入口行 | A（本文只约定更新页自身字段、状态与按钮行为） |
| AI 连接、模型能力、关闭思考参数、预算 | B（更新链路不涉及） |
| 仓库/Release/API/速率限制/代码事实的原始核对过程 | D（本文引用其结论并标注 §） |
| 版本定案、签名门槛、元数据契约、下载安装、发布流程、验收 | 本文 |

## 一、现状（已核实）

| # | 事实 | 值 / 证据 |
| --- | --- | --- |
| 1 | 仓库可见性 | `lcsaff/callring`，`private: false`（**公开**）；default_branch `main`；created 2026-10-19T03:39Z，pushed 2026-10-19T03:49:30Z（D §2） |
| 2 | 正式 Release | tag `v0.2`、name `Callring v0.2`、draft=false、prerelease=false；created 2026-10-15T07:24:02Z，**published 2026-10-19T03:49:30Z**；target_commitish `8e38c66cfe73991dff72a9ea36085e5bd62e575b`；`/releases/latest` 现返回 v0.2 |
| 3 | 资产 | `callring-0.2.apk`（33,570,356 字节，`application/vnd.android.package-archive`，下载 1 次）、`SHA256SUMS.txt`（84 字节）；下载地址形如 `.../releases/download/v0.2/callring-0.2.apk` |
| 4 | APK 摘要 | `SHA256SUMS.txt` 全文为 `103bfebcc4ed8d326d5d48e9dc3b3c893a203e897cc62a456598a05c88278772  callring-0.2.apk` |
| 5 | 发行签名 | 正式 Release 构建，**独立发行密钥**签名；证书 SHA-256 `ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`；Release 说明明确“与原 debug 包签名不同，不能覆盖 debug 包；卸载丢失本地数据；后续发行版沿用本次发行签名” |
| 6 | 线上最高版本 | versionCode 2 / versionName 0.2；v0.2 未含应用内检查更新（说明原文） |
| 7 | 标签 | 远端仅 `refs/tags/v0.2`（轻量标签，指向 `8e38c66`）；本地工作区 `git tag` 为空——“本地无标签”只对本地成立，原文结论需限定作用域 |
| 8 | 渠道现状 | 仓库无 `.github/`；`tools/` 只有校验/生成脚本（`check_backend_boundaries.py`、`verify_backend.py`、`verify_ui.py`、`generate_melody.py` 等），无发布/签名脚本 |
| 9 | 速率限制 | 未认证 core 60/h；**缺 `User-Agent` 直接 403**（此前“403 限流”判断的成因）；带 UA 返回 200；配额用尽时 403 且带 `X-RateLimit-*` 头 |
| 10 | 应用配置 | `app/build.gradle`：applicationId `com.greatwall.callring`、minSdk 29、targetSdk 33、compileSdk 36、versionCode 2、versionName `'0.2'`、buildType `backendQa`（`applicationIdSuffix '.backendqa'`、`versionNameSuffix '-backend-qa'`）、**无 signingConfigs** |
| 11 | 构建链 | Gradle 8.13（wrapper）、AGP 8.13.2、Kotlin 2.3.21、compose BOM 2025.12.01 |
| 12 | app 依赖面 | 仅 `project(':ui')`、`project(':backend')`、coroutines；`app/src` 下**没有任何 `import androidx.*`** → FileProvider 必须给 app 显式加 `androidx.core:core`（ui 用 `implementation`，不传递） |
| 13 | Manifest | 无 `REQUEST_INSTALL_PACKAGES`、无 FileProvider；`app/src/main/res/xml/` 已存在（`data_extraction_rules.xml`） |
| 14 | 契约模式 | `CallringUiApi` 用 `val aiSettings: AiSettingsApi get() = UnavailableAiSettingsApi` 提供默认实现；平台实现在 `app/.../bootstrap/BackendGraph.kt` 的 `object : CallringUiApi by backend` 中装配 |
| 15 | 统一验证现状 | PROJECT 的构建入口只列 app 三项（`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`），README 另列 UI 三项，两处都**不含** `:backend:test`；后端是 `java-library`，单元测试任务名是 `:backend:test` |

### 原文需要更正的结论

- “不能据此判断仓库是否公开、是否有 Release 或线上最高版本”已过期：仓库 public，v0.2 是已发布的正式 Release 且为 latest，线上最高 versionCode 为 2。
- “本地未发现 Git 标签”只对本地成立：远端存在 `refs/tags/v0.2`；文档必须区分本地工作区与远端。
- “403 限流”原判断的成因是**缺少 `User-Agent`**，不是配额用尽；两类 403 的处置完全不同。
- “latest 由发布方选择的最新版本”不够准确：默认新发布的 Release 会成为 latest，但 `legacy` 可按创建日期+语义版本改变该规则，因此不得用时间顺序或版本号大小推断 latest。
- 原文把“首个受管理版本从 0.3.0 开始”写成条件句；现已确认前提成立，改为定案。

## 二、方案取舍

【决策】首期采用“GitHub Releases + `update.json` 发布元数据 + Android DownloadManager”，不自建更新服务，不在 APK 内置令牌。

【理由】仓库已公开（事实 1），无需认证即可读取；GitHub 托管下载与重定向可用；DownloadManager 提供后台下载、进度与进程重启后的任务查询。自建服务要额外部署维护，当前规模（APK 下载计数 1）不划算；只跳 Release 网页则用户需自行识别版本与安装包。

【验收】设计评审确认不含服务端组件、APK 内无 GitHub Token；所有下载地址为 Release 返回的 HTTPS 直链或 GitHub 官方重定向。

## 三、发布元数据 `update.json` 契约

【决策】每个正式 Release 上传 `update.json` 与 `SHA256SUMS.txt`，两者由发布工具从**实际 APK** 同源生成；`assetName` 必须与 APK 附件名完全一致，`sha256` 必须等于 `SHA256SUMS.txt` 中该 APK 的值。

```json
{
  "schemaVersion": 1,
  "applicationId": "com.greatwall.callring",
  "versionCode": 3,
  "versionName": "0.3.0",
  "minSdk": 29,
  "assetName": "callring-0.3.0.apk",
  "sizeBytes": 12345678,
  "sha256": "由发布工具计算的完整 SHA256 值"
}
```

【理由】人工填写元数据必然漂移；同源生成让 APK、`update.json`、`SHA256SUMS.txt`、标签四者可用脚本互相校验。`SHA256SUMS.txt` 保留，用于人工下载后的 `sha256sum -c` 校验（与元数据同源，不是第二套真值）。

【验收】构造样例 APK 跑生成脚本：`assetName` ≠ 附件名、`sha256` ≠ `SHA256SUMS.txt` 记录、`sizeBytes` ≠ 实际字节数、`versionCode` ≠ APK 内值时，脚本均返回非零；字段齐全时输出文件可用于后续上传。

## 四、检查与版本判断

【决策】`GET https://api.github.com/repos/lcsaff/callring/releases/latest`，固定发送自定义 `User-Agent`（如 `Callring/<versionName>`）与 `Accept: application/vnd.github+json`，并用 `ETag` / `If-None-Match` 做条件请求；只认非 draft、非 prerelease 的 Release。

【理由】事实 9 证明缺 UA 会被 403，这类 403 与限流 403 必须分开处理；条件请求在 304 时复用缓存，减少配额消耗（未认证 60/h）。`/releases/latest` 本身只返回非 draft、非 prerelease，但客户端仍要防御性地忽略这两类，避免日后改用列表接口时误判。

【验收】单测/契约测试覆盖：带 UA 200 正常解析；缺 UA 403 归入“配置/权限错误”而非“限流”；`X-RateLimit-Remaining: 0` 的 403 与 429 展示恢复时间（读 `X-RateLimit-Reset` / `Retry-After`，不自动反复重试）；404 不断言“无版本”，改为“无法确认最新版本”并提供 Release 网页入口；304 复用上次缓存；draft/prerelease 条目被忽略。

【决策】仅当远端 `versionCode` > 当前安装 `versionCode` 时提供更新；`versionName` 只用于展示与标签一致性校验；远端缺元数据/缺匹配 APK 时显示“该版本尚未提供可安装更新”。

【理由】versionCode 是整型单调量，versionName 不是序关系；Release 里的源码 zip/tar 不能当安装包。

【验收】同版本/低版本提示“当前已是最新版本”；versionCode 相等但 versionName 更大时**不**提示可更新；`minSdk` 高于设备时说明“系统版本不满足”，不给下载按钮；无元数据时给出网页入口且不显示安装按钮。

【决策】`latest` 由发布流程显式保证：上传草稿 → 校验附件 → 发布时 `PATCH make_latest: "true"` → 发布后核对 `/releases/latest` 的 tag 等于本次 tag。

【理由】GitHub 文档明确：`make_latest` 指定是否设为仓库 latest，draft 与 prerelease 不能设为 latest，新发布的 Release 默认为 true，但 `legacy` 会改成按“创建日期 + 更高语义版本”判定。因此不能把“最后发布”“版本号最大”“时间最新”当默认事实（v0.2 的 created 10-15 与 published 10-19 相差 4 天）；显式 PATCH 并把核对写进流程，才能消除该不确定性。

【验收】发布演练：创建草稿并发布后，`/releases/latest` 返回的 `tag_name` 必须等于本次 tag；若不等，发布脚本以非零退出并在日志中记录两者。构造“后建草稿再发布”的用例不得把 latest 留在旧版本。禁止回退、禁止依赖默认行为。

### 更新页展示与状态（本文定义，A 只提供入口）

【决策】更新页固定展示：当前安装版本（versionName + longVersionCode，来自系统应用信息）、可用版本、发布日期、更新说明、APK 大小；主按钮随状态唯一变化。状态集合：`IDLE`（未检查）、`CHECKING`、`UP_TO_DATE`、`UPDATE_AVAILABLE`、`UNSUPPORTED_DEVICE`（`minSdk` 高于设备）、`NO_INSTALLABLE`（缺元数据或缺匹配 APK，给 Release 网页入口）、`CHECK_FAILED`（断网/超时/404/限流/元数据非法，附原因与“重试”）、`DOWNLOADING`（字节进度，总量未知时显示已下载大小）、`VERIFYING`、`VERIFIED`（“安装”可用）、`VERIFY_FAILED`（摘要/签名/包名/版本不符，允许重新下载）、`INSTALL_PERMISSION_REQUIRED`（“去授权”，返回后重新检测）、`INSTALL_CANCELLED`、`INSTALL_PENDING_VERIFY`（以系统安装信息确认实际版本后才算安装完成）。

【理由】用户只主动检查，不新增启动弹窗或后台轮询；状态必须来自检查结果、下载任务与系统返回，UI 不得自行推断“已安装”。

【验收】每个状态只有一条主操作与一段文案；`UP_TO_DATE`/`NO_INSTALLABLE` 不出现下载按钮，`NO_INSTALLABLE` 提供 Release 网页入口；`VERIFY_FAILED` 不出现安装按钮且可重新下载；未校验通过前任何状态都不出现安装按钮；未知异常回落 `CHECK_FAILED` 且保留“重试”；失败判据：同一状态出现互相矛盾的两个主按钮，或跳过校验直接出现“安装”。

## 五、下载与安装

【决策】用 `DownloadManager` 下载到应用专属外部目录（`setDestinationInExternalFilesDir`），**必须** `setVisibleInDownloadsUi(false)`，并由应用自己拉起安装器；持久化任务 ID 与目标版本；取消时 `remove` 任务并删除未完成文件。

【理由】路径在 `/Android/data/<pkg>/files` 下，系统下载完成通知点开的安装器拿不到该文件的可读 URI，因此不能依赖系统通知完成安装；隐藏下载 UI 可避免用户在“下载”应用里看到一个点开无法安装的条目。持久化任务 ID 才能在页面返回或进程重启后恢复进度、避免重复下载。

【验收】下载中显示字节进度（总量未知时显示已下载大小）；返回页面再进入仍显示同一任务；杀进程重启后能恢复状态；取消后任务与残留文件都被清理；重复点下载不会产生第二个任务；系统“下载”应用不出现该条目。

【决策】完成后的安装前校验：文件大小、SHA-256、APK 包名、versionCode、minSdk，以及**签名证书是否与当前应用一致**；全部通过才允许安装。

【理由】`sha256` 只防传输损坏，不能防“另一个同包名、可安装、恶意签名”的包；真正的完整性闸门是签名一致：`PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` 取待装包的签名证书，与 `getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)` 取当前应用证书比对 SHA-256。缺任一项都不得给安装按钮。

【验收】篡改 APK 任一字节 → 摘要不符拦截；换签名重打包 → 证书比对失败拦截；改 versionCode/包名 → 对应项失败；`minSdk` 高于设备 → 提示系统版本不满足；校验失败后允许重新下载，不出现安装按钮。

【决策】通过受限 `FileProvider` URI 拉起安装器，只授临时读权限：`androidx.core.content.FileProvider`、`external-files-path` 路径 XML（如 `Download/`）、`FLAG_GRANT_READ_URI_PERMISSION`；未获“安装未知应用”授权时引导到系统设置并在返回后重新检测。

【理由】FileProvider 属于 `androidx.core`，而 app 模块当前没有任何 androidx 依赖（事实 12），必须显式添加；当前 targetSdk 33 下走 `file://` 会抛 `FileUriExposedException`。授权是用户级开关，必须在每次安装前实时检查而不是缓存。分发渠道前提：本链路只服务**自更新/侧载**，`REQUEST_INSTALL_PACKAGES` 属受限能力；Google Play 不允许应用内自更新，若将来上架 Play 会被拒、需改用 Play 内更新（政策原文本轮未核对，见 D）。

【验收】未授权时跳到对应应用详情页，返回后按钮状态即时更新；授权后安装器正常打开；`FileProvider` authority 用 `${applicationId}.fileprovider` 且不与 `backendQa` 变体冲突；Manifest 不导出该 Provider；安装被取消/存储不足/文件被清理都有明确状态且可重试。

【决策】状态持久化只保存可恢复的最小事实：目标 versionCode/versionName、`assetName`、`sha256`、下载任务 ID、已校验结果与失败原因，不缓存 APK 之外的用户数据；清理只作用于更新目录。

【理由】进程重启、任务被系统回收、用户离开页面后都要能恢复同一个下载而不是重下；把业务数据放在更新目录会有被误删风险，也可能阻碍“取消时删文件”的清理语义。

【验收】杀进程后重进页面仍显示同一任务与进度；取消后更新目录无残留；清理更新目录不影响档案、设置与录音（与业务存储路径无交集）。

## 六、版本与签名管理（keystore 门槛）

【决策】新增根目录 `version.properties` 作为唯一版本来源（`versionCode`、`versionName`），Gradle 读取，发布工具与校验脚本读取同一文件；首个受管理版本定为 **`0.3.0` / `versionCode 3`**；标签统一 `v<versionName>`，即 `v0.3.0`。

【理由】线上最高已发布为 versionCode 2、现装亦为 2（事实 6），所以 3 是确定值而非条件值；v0.2 的两段式标签是历史例外（当时未建正式发布规则），后续不再沿用两段式。

【验收】改 `version.properties` 后构建产物 `versionCode/versionName` 与文件一致；准备脚本对 `0.3`、`v0.3.0`、`0.2.9`、相同 versionCode 等输入报错并停止；标签与 versionName 不一致时校验脚本非零退出。

【决策】**keystore 连续性是阶段三的第一门槛**：必须找到并备份 v0.2 的发行密钥，用 `apksigner verify --print-certs` 核对证书 SHA-256 **必须等于** `ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`；拿不到该密钥则阶段四的全部升级验收作废，只能另立数据迁移方案。

【理由】覆盖安装要求同包名、兼容签名、更高 versionCode（Release 说明与事实 5 已确认 debug 包不可覆盖）；签名不同只能卸载重装，而卸载会丢失本地数据。仓库里没有私钥、`app/build.gradle` 也没有 signingConfigs，所以“密钥是否还在”是当前最大未知项，必须在写更新代码之前先解决。

【验收】对已发布的 v0.2 APK 与用候选密钥签出的测试包分别执行 `--print-certs`，两处 SHA-256 都等于上述值；把密钥与密码备份到仓库外的安全位置并记录恢复演练结果。若指纹不等或密钥缺失：阶段四标记为阻塞，不进入下载/安装实现，改为单独评审数据迁移。

【决策】`backendQa` 构建禁用更新入口；禁止把 QA 包当正式 APK 发布。

【理由】QA 包 applicationId 为 `com.greatwall.callring.backendqa`，正式 `update.json` 的 `applicationId` 校验必然不通过，界面会显示“尚未提供可安装更新”，属于误导；反过来也不允许把后缀包上传到 Release。

【验收】`backendQa` 变体下设置页不出现“检查更新”入口或入口为禁用态；发布脚本拒绝任何 `applicationId != com.greatwall.callring`、带 `-backend-qa` 版本后缀的 APK。

## 七、发布流程与校验（失败即停）

【决策】固定顺序，任一步失败即停，不产出半成品 Release：

1. 准备版本：脚本更新 `version.properties`，校验格式与递增关系（versionCode 必须大于已发布最高与现装最高）。
2. 完整验证：`:backend:test`、`:ui:testDebugUnitTest`、`:app:testDebugUnitTest`、`:app:assembleRelease`、`:app:lintDebug`（若 `lintRelease` 在实施时可用，则以 release 变体为准）。
3. 签名：用固定发行证书签名，`apksigner verify --print-certs` 核对指纹，`zipalign` 校验。
4. 生成元数据：从签名后的 APK 生成 `update.json` 与 `SHA256SUMS.txt`。
5. 一致性校验：APK、元数据、`version.properties`、标签四者互校（版本号、`assetName`、摘要、大小、包名、签名）。
6. 上传草稿 Release（tag `v0.3.0`），校验附件名/大小/摘要。
7. 发布并 `PATCH make_latest: "true"`，随后核对 `/releases/latest` 的 tag。

【理由】发布是单向操作，人工在最后一步补救的成本远高于脚本前置拦截；签名后再生成摘要可避免“先算摘要再重签”导致的不一致。CI 用 Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）注入，未配置即停，禁止用 debug 签名或未签名包冒充正式包。

【验收】用固定样例（错误标签、附件缺失、包名不符、摘要不符、版本未递增、Secrets 缺失）逐项跑发布脚本，全部非零退出且无 Release 被发布；正常样例演练后 Release 附件含 APK、`update.json`、`SHA256SUMS.txt` 三个且 `latest` 指向本次 tag。

## 八、代码落点

| 范围 | 位置（新建或修改） | 职责 |
| --- | --- | --- |
| 更新契约 | 新增 `backend/.../application/api/AppUpdateApi.kt` | 状态、发布信息、检查/下载/取消/安装操作；接入 `CallringUiApi` 默认实现模式（`val update: AppUpdateApi get() = UnavailableAppUpdateApi`） |
| 更新界面 | 新增 `ui/.../feature/update/AppUpdateScreen.kt`、`AppUpdateViewModel.kt` | 展示当前版本、可用版本、说明、大小与状态；不直接访问网络或系统安装器 |
| 平台实现 | 新增 `app/.../platform/update/`，按职责拆文件 | GitHub 查询（UA/Accept/ETag）、元数据校验、DownloadManager、校验与安装、状态持久化 |
| Android 配置 | `app/src/main/AndroidManifest.xml`、新增 FileProvider 路径 XML、`app/build.gradle` | `REQUEST_INSTALL_PACKAGES`、Provider 声明、新增 `androidx.core:core` 依赖与 `signingConfigs` |
| 版本来源 | 根 `version.properties`、`app/build.gradle` | 单一版本来源，Gradle 读取 |
| 发布工具 | `tools/` 新增版本准备、签名校验、元数据生成、发布校验脚本 | 失败即停的本地校验链 |
| CI | 新增 `.github/workflows/` | Secrets 驱动的签名构建与草稿上传 |
| 设置入口 | `ui/.../feature/settings/SettingsOverviewScreen.kt`、`ui/.../app/SettingsRoute.kt` | “系统与支持”增加“检查更新”行，副标题显示当前安装版本；版本必须从 `PackageManager` 读取（`SettingsSummary` 无版本字段，见 A §2 证据），不得由后端摘要提供 |

## 九、分阶段验收

### 阶段三：版本与发布基础

1. 取得并备份 v0.2 发行密钥，核对指纹等于 `ac2a4489…4062ee`（门槛；不通过则停止本阶段）。
2. 建 `version.properties`、准备脚本、发布校验脚本；`app/build.gradle` 接入版本读取与 signingConfigs。
3. 用固定样例验证：版本未递增、标签错、附件缺失、包名不符、摘要不符、签名不符、QA 包混入均被拦截。
4. 在 CI 配置好 Secrets 前，工作流必须失败退出而不是产出未签名 Release。

### 阶段四：应用内更新

1. 更新契约、设置入口、更新页面、GitHub 查询、下载与校验、安装链路完成。
2. 单元测试覆盖：高/同/低版本、缺附件、缺元数据、draft/prerelease、系统版本不满足、403 两类、429 与恢复时间、404、异常 JSON、304、下载恢复、取消清理、摘要不符、签名不符。
3. **设备实测**（隔离测试设备，禁止覆盖正在使用的日常设备）：
   - 先安装已发布的 **v0.2 正式 Release APK**（同签名、versionCode 2），确认可启动。
   - 用 0.3.0 新包覆盖升级，确认包名不变、versionCode=3、档案/设置/录音均保留。
   - 走完应用内检查 → 下载 → 取消 → 重试 → 进程重启恢复 → 未知来源授权 → 取消安装 → 安装成功全链路。
   - 记录每步的时间、截图/日志与最终 `dumpsys package` 版本信息。
4. 注意：v0.2 没有更新入口，首次获得更新能力必须手动下载安装 0.3.0；此后才走应用内更新。

### 统一验证入口

```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

README/PROJECT 的验证说明同步补上 `:backend:test`（后端是 `java-library`，任务名不是 `testDebugUnitTest`）。Debug 构建与单测通过**不等于**更新链路通过：Release 签名、真实下载、覆盖升级必须另行验收并在文档中单列证据。验收证据要求（缺任一项不得写“已验收”）：

- 构建与测试：实际退出码、三处 XML 的 `tests/failures/errors/skipped` 计数（`backend/build/test-results/test`、`ui/build/test-results/testDebugUnitTest`、`app/build/test-results/testDebugUnitTest`），空测试集不能记作通过。
- 发布：APK 字节数与 SHA-256、`apksigner --print-certs` 指纹、`zipalign` 校验结果、`update.json` 内容、`SHA256SUMS.txt` 内容、`/releases/latest` 返回的 tag。
- 设备：目标设备机型/系统版本、安装前后 `versionCode`、覆盖升级前后的档案/设置/录音抽查结果、每一步的状态截图或日志；未完成项与阻塞项单列，失败时保留产物与日志，不把“编译通过”或“Debug 通过”写成更新链路验收通过。

## 十、风险与未核实项

| # | 项 | 状态与处理 |
| --- | --- | --- |
| 1 | v0.2 发行 keystore 是否仍在维护者手中 | **未核实，最高风险**；阶段三第一门槛，拿不到则阶段四升级验收作废 |
| 2 | Play 上架政策原文 | 未核对；本文按自更新/侧载前提设计，上架 Play 前须按官方政策复核并改用 Play 内更新，不可把“会被拒”当作已核实政策条款 |
| 3 | GitHub 服务端 asset digest | 本轮未取得；摘要以 `SHA256SUMS.txt` 为唯一来源 |
| 4 | 装机量 | `callring-0.2.apk` 下载 1 次是公开计数，不能推断真实安装量 |
| 5 | 设备行为 | 本轮未安装/未下载/未升级；所有下载、安装、覆盖升级结论是设计目标，不是实测结果 |
| 6 | `setVisibleInDownloadsUi(false)` 在新系统版本的行为 | 该方法在较新 API 已标记废弃；需在目标设备上确认不出现可点开的系统通知条目 |
| 7 | latest 规则 | 依赖 GitHub 文档的 `make_latest` 语义；若平台行为变化，以发布后核对 `/releases/latest` 为准 |
| 8 | CI 与 Secrets | `.github/` 尚不存在，工作流本身待实现并需首跑验证 |

## 十一、外部依据

- GitHub Releases API：https://docs.github.com/en/rest/releases/releases
- 创建/更新 Release（`make_latest`）：https://docs.github.com/en/rest/releases/releases#update-a-release
- DownloadManager：https://developer.android.com/reference/android/app/DownloadManager
- 未知来源安装授权：https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
- FileProvider：https://developer.android.com/reference/androidx/core/content/FileProvider
- apksigner：https://developer.android.com/tools/apksigner
- 版本管理：https://developer.android.com/studio/publish/versioning
- 同系列文档：[A 紧凑主界面 / 更新入口](2026-10-19-compact-main-ui-design.md)、[B AI 请求策略](2026-10-19-ai-request-policy-design.md)、[D 外部事实证据](2026-10-19-external-facts-verification.md)
