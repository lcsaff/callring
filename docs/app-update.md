# 应用更新逻辑

应用内更新从 GitHub Latest Release 读取发布信息，经用户手动下载、完整校验后交给 Android 系统安装器。版本新旧只比较 `versionCode`，`versionName` 用于展示及发布标签、附件命名。本文总结现有实现；项目更新约束及交付要求集中维护在 [PROJECT.md](../PROJECT.md)。

## 模块与入口

| 位置 | 职责 |
| --- | --- |
| [AppUpdateApi.kt](../contracts/src/main/kotlin/com/greatwall/callring/contracts/runtime/AppUpdateApi.kt) | 统一状态和 `status/check/download/cancel/install` 五个操作。 |
| [StartupUpdateViewModel.kt](../ui/src/main/kotlin/com/greatwall/callring/ui/feature/update/StartupUpdateViewModel.kt) | 主界面宿主会话内的一次静默检查及新版本提示。 |
| [AppUpdateViewModel.kt](../ui/src/main/kotlin/com/greatwall/callring/ui/feature/update/AppUpdateViewModel.kt)、[AppUpdateScreen.kt](../ui/src/main/kotlin/com/greatwall/callring/ui/feature/update/AppUpdateScreen.kt) | 更新页状态展示、操作及下载进度轮询。 |
| [AndroidAppUpdate.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/AndroidAppUpdate.kt) | 检查、下载、安装编排，并发隔离、状态恢复与文件清理。 |
| [GitHubReleaseClient.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/GitHubReleaseClient.kt)、[UpdateContract.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/UpdateContract.kt) | GitHub 请求、元数据解析、版本及附件准入判断。 |
| [ResumableUpdateDownload.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/ResumableUpdateDownload.kt) | HTTP 断点续传、长度及整包摘要验证。 |
| [AndroidUpdateAdapters.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/AndroidUpdateAdapters.kt)、[UpdateStateStore.kt](../app/src/main/kotlin/com/greatwall/callring/platform/update/UpdateStateStore.kt) | 构建识别、APK/证书检查、系统授权/安装器、持久状态编码。 |

UI 只调用契约，不直接访问网络、文件和安装器。更新属于平台能力，不通过业务数据库或领域失效订阅驱动；AI 也复用同一 API。

## 启动检查与手动操作

1. 主界面创建应用级 `StartupUpdateViewModel`，先读取已有状态。下载中、校验中、已校验、待授权、待安装确认或不支持状态下跳过自动检查。
2. 其余状态执行一次 `check()`；只有 `UPDATE_AVAILABLE` 弹出“发现新版本”。无更新和失败静默，旋转保留本次检查与提示关闭状态；这是宿主会话内去重，不是每日一次或永久忽略某版本。
3. 点击“关闭”只消费提示；点击“确定”消费提示并进入“设置 → 应用更新”，不触发下载或安装。
4. 更新页打开时调用 `status()`，启动检查结束时再刷新。手动“检查更新”才调用 `check()`。下载或校验期间每 500ms 读取状态，离开页面后的下载由平台协程继续；页面销毁停止自身轮询，进程退出则中断下载。

## 发布检查与元数据

检查固定请求 `https://api.github.com/repos/lcsaff/callring/releases/latest`，不遍历历史 Release，也不从标签文字推断版本高低。只有正式包名 `com.greatwall.callring` 且非 debuggable 的构建支持应用内更新；调试和内部测试构建返回 `UNSUPPORTED_DEVICE`，原因键为 `update.unsupported_build`。

请求使用 `Callring/<当前 versionName>` User-Agent，不携带账号凭据。Release 请求禁止自动重定向，连接和读取超时各 10 秒，响应上限 512 KiB；`update.json` 请求允许自动重定向，响应上限 64 KiB。ETag 与状态一起保存，只有仍保存可用版本元数据时才发送 `If-None-Match`；304 复用该元数据并刷新检查时间，无可用缓存的后续检查重新取完整响应。

`update.json` 是唯一机器更新清单，`SHA256SUMS.txt` 仅供人工核对。清单字段如下：

| 字段 | 含义与准入要求 |
| --- | --- |
| `schemaVersion` | 当前必须为 1。 |
| `applicationId` | 必须与当前应用包名一致。 |
| `versionCode`、`versionName` | code 为正数；Release 标签必须为 `v<versionName>`。 |
| `minSdk` | 正数；有新版本时还须不高于设备 SDK。 |
| `assetName` | 必须为 `callring-<versionName>.apk`，不能含路径分隔符。 |
| `sizeBytes` | 正数，且与 Release 同名 APK 附件的大小一致。 |
| `sha256` | APK 整包的 64 位十六进制 SHA-256。 |

下载地址取自 Release 附件的 HTTPS `browser_download_url`，不由清单另行指定。Release 为草稿或预发布、标签/包名/附件名/大小不匹配，或清单缺失、格式非法，均返回 `NO_INSTALLABLE`，不降级为随意选择一个 APK。满足上述条件后，目标 code 小于等于已安装 code 返回 `UP_TO_DATE`；更高版本但 SDK 不满足返回 `UNSUPPORTED_DEVICE`，否则返回 `UPDATE_AVAILABLE`。Release 更新说明最多保留 4,000 字符。

网络、HTTP、限流等读取错误返回 `CHECK_FAILED`；403 限流、普通 403、429、404、超时和网络失败保留区分，手动页面展示相应提示。`NO_INSTALLABLE` 页面提供 Release 网页入口。

## 下载与断点续传

只有存在目标元数据且状态为 `UPDATE_AVAILABLE` 或 `VERIFY_FAILED` 才准备下载；已有下载任务时不重复启动。文件位于应用专属外部文件目录 `Download/update`，未完成文件名为 `<assetName>.part`，完成后改名为目标 APK。文件名和规范路径检查将文件限制在该目录内。

续传片段绑定 `versionCode + assetName + sizeBytes + sha256`。重新检查得到相同目标时保留有效片段；目标变化则清理旧文件。有片段时检查失败仍保留旧目标与片段，状态转为 `VERIFY_FAILED`，允许继续下载。

- 有片段时发送 `Range: bytes=<已有长度>-`，并指定 `Accept-Encoding: identity`。
- HTTP 206 必须有合法 `Content-Range`，起点等于已有长度，总量等于清单大小，终点不能越界；若声明了正数 Content-Length，也必须与本次范围长度一致。
- HTTP 200 表示从零覆盖写入，不能追加到旧片段。HTTP 416 在已有片段时最多清空并从零回退一次，防止无限重试。
- 使用 64 KiB 缓冲流式写入，累计增加至少 256 KiB 及响应结束时保存进度；短响应视为中断，超量响应丢弃片段。
- 达到目标大小后重新计算整个 `.part` 的 SHA-256；大小或摘要不符丢弃片段，校验通过才改名。随后进入 APK 安装资格校验。

下载连接超时 10 秒，读取超时 30 秒。网络中断保留可继续的片段并进入 `VERIFY_FAILED`；用户“取消下载”会断开连接、取消任务并丢弃下载数据，回到 `UPDATE_AVAILABLE`。取消操作与进程关闭的语义不同：关闭 owner 保留片段，不再发布迟到结果。

## APK 校验与系统安装

下载完成后检查实际文件大小、整包 SHA-256、可解析的 APK 包信息、包名、目标 `versionCode`、设备 SDK 和签名证书。证书摘要须与当前已安装应用的证书相同；该比较不以一份硬编码证书替代当前安装的身份。通过后进入 `VERIFIED`，失败删除目标 APK 并进入 `VERIFY_FAILED`。

用户点击“安装”后，先检查系统“安装未知应用”权限。未授权时打开当前应用的系统授权页，保存 `INSTALL_PERMISSION_REQUIRED`；授权后仍需用户再次点击，才继续安装。安装前再次执行完整文件校验，防止下载后文件变化或丢失。

通过校验后使用 FileProvider 的 content URI，以临时只读授权启动 Android 系统安装界面。成功启动仅记录 `INSTALL_PENDING_VERIFY`；系统界面启动失败返回操作错误。后续 `status()` 重新读取当前安装信息，只有当前 `versionCode >= 目标 versionCode` 才转为 `UP_TO_DATE` 并清理更新文件。安装最终确认仍由系统和用户完成。

## 状态与恢复

主流程为 `IDLE → 检查结果 → UPDATE_AVAILABLE → DOWNLOADING → VERIFYING → VERIFIED → INSTALL_PENDING_VERIFY → UP_TO_DATE`。安装前可能先进入 `INSTALL_PERMISSION_REQUIRED`；检查和下载错误分别保留重试入口。

| 状态 | 当前行为与下一步 |
| --- | --- |
| `IDLE` | 尚无有效状态，可手动检查。 |
| `UP_TO_DATE` | 当前无需更新，可重新检查。 |
| `UPDATE_AVAILABLE` | 显示下载；有片段时显示继续下载。 |
| `UNSUPPORTED_DEVICE` | SDK 或构建不支持，不提供下载。 |
| `NO_INSTALLABLE` | 发布不可安装，可重新检查或打开 Release 网页。 |
| `CHECK_FAILED` | 检查失败，可重试检查。 |
| `DOWNLOADING`、`VERIFYING` | 下载中可取消，校验中等待结果；平台阻止重查覆盖。 |
| `VERIFY_FAILED` | 下载或校验失败；有片段继续下载，否则重新下载。 |
| `VERIFIED` | 可点击安装，平台检查不覆盖已准备好的安装包。 |
| `INSTALL_PERMISSION_REQUIRED` | 提供授权/继续安装入口，保留目标文件。 |
| `INSTALL_PENDING_VERIFY` | 等待读取已安装版本确认；页面虽提供“重新检查”，平台在版本未变化时保留该状态。 |
| `CHECKING`、`INSTALL_CANCELLED` | 契约及界面已有展示分支，但当前平台检查未写入 CHECKING，也没有安装结果回调写入 INSTALL_CANCELLED；不能据此宣称已检测系统安装取消。 |

状态保存在私有 SharedPreferences `app_update` 的 `state` 字段，JSON 编码版本为 1，包含 ETag、阶段、目标版本元数据、进度、文件名、校验标记、检查时间及失败信息；不进入应用备份。缺失或无法解码时按初始状态处理。

进程重启后，原 `DOWNLOADING/VERIFYING` 在没有活跃下载任务时恢复为可手动继续的 `UPDATE_AVAILABLE`，不会自动联网续传。若完整目标文件尚未完成资格校验，会先转回片段，再经续传入口重新校验。恢复进度以实际 `.part` 文件长度为准，不能只相信持久计数；`VERIFIED` 文件丢失或大小改变则转为 `VERIFY_FAILED`，即使大小未变，安装前仍会复验摘要及身份。

检查使用 generation 与请求前持久状态快照共同隔离结果，旧检查不能覆盖新检查、下载或安装状态。owner 关闭先封口并推进 generation，再断开网络和取消协程；下载进度、完成提交与失败回写都检查关闭/取消状态。

## AI 边界与发布衔接

AI 只有 `update_status` 和 `update_check` 两项平台观察工具，不能下载、取消下载或安装。工具结果采用安全投影，去除可调用下载地址、私有文件路径及原始失败详情，不增加业务变更版本或领域失效。

发布端从唯一版本源 `version.properties` 构建正式签名 APK，通过 `tools/release/generate_release_metadata.py` 在独立目录生成同源的 `callring-<versionName>.apk`、`update.json` 和 `SHA256SUMS.txt`，再用 `verify_release.py` 核对版本、包名、签名、对齐、大小及摘要。三个附件上传并发布为 Latest 后，应用才可通过上述准入检查；只有 APK、没有有效清单的 Release 不支持应用内更新。具体提交、签名、上传与读回步骤见 [PROJECT.md 的版本与交付](../PROJECT.md#版本与交付)。

## 验证范围

现有平台测试位于 `app/src/test/kotlin/com/greatwall/callring/platform/update/`，覆盖发布协议、网络响应、断点续传、文件及签名校验、恢复、并发和线程行为；UI 更新测试位于 `ui/src/test/kotlin/com/greatwall/callring/ui/feature/update/`，另有启动导航测试；AI 安全投影见 `backend/src/test/kotlin/com/greatwall/callring/UpdateToolTest.kt`。设备夹具在 app debug 源集的 `AppUpdateDeviceTest` 和 `AppUpdateLiveDownloadDeviceTest`。

上述测试入口不等同于完整真实下载与系统安装验收。当前验收缺口仍包括完整网络下载、摘要、授权、系统安装及安装后读回链路；已有同证书覆盖升级证据不能替代应用内更新验收。
