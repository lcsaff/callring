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
- [最终生产文件归属](docs/superpowers/specs/2026-10-20-layer-boundaries-file-map.md)覆盖全部 main 文件。T14 统一 CI、T15 设备与最终验收未关闭。

## 启动注意事项
- 在本项目根目录运行 Gradle；不依赖 android-funs。
- JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、Compose BOM 2025.12.01；minSdk 29、compileSdk 36、targetSdk 33、Build Tools 36.0.0。
- SDK 通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或本地 `local.properties` 的 `sdk.dir` 配置。
- 版本号由根 `version.properties`（`versionCode`/`versionName`）单一驱动：根 `build.gradle` 读取并注入 `app/build.gradle`，发布工具读同一文件，发布标签为 `v<versionName>`。
- 统一构建与验证入口（`backend` 是 `java-library`，测试任务为 `:backend:test`）：`.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；Release APK：`app/build/outputs/apk/release/app-release.apk`。
- Release 签名优先读取被 gitignore 的根 `signing.properties`（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`），缺失时回退本机 Android debug keystore；私钥与口令不入库。
- 发布校验脚本在 `tools/release/`（版本校验、`update.json`/`SHA256SUMS.txt` 同源生成、失败即停的发布一致性校验与失败样例自检）；CI 定义在 `.github/workflows/`（`release.yml` 签名 Secrets 缺失即失败，默认只构建校验不上传）。

## 已知限制
- 真实 SIM 来电、长时间待机、重启后行为尚待验证，详见 README。
- 当前发行身份是本机 debug 证书（证书 SHA-256 `5a901cad…24b4`）；v0.2 正式包的发行密钥已丢失，任何新构建都无法覆盖 v0.2 安装，覆盖升级只在同 debug 证书的 0.3.0 及后续版本之间成立。
- 应用内更新链路（下载/校验/安装）、真实下载与真机覆盖升级尚未验收：等 debug.keystore 备份与隔离测试设备后再执行，不能凭 Debug 构建或单测通过宣称更新链路通过。

## 结构校验与补充编译
```powershell
python tools/check_architecture.py
python tools/check_backend_boundaries.py
python -m unittest discover -s tools -p "test_architecture.py"
python -m unittest discover -s tools -p "test_backend_boundaries.py"
.\gradlew.bat :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac --console=plain
```
上述为当前可运行命令；统一 CI 脚本整合属于 T14。设备结果不可由编译/单测替代。
