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
- 设备测试与音频生成：`tools/`

## 启动注意事项
- 在本项目根目录运行 Gradle；不依赖 android-funs。
- JDK 17、Gradle 7.5、AGP 7.3.1、Android SDK 33 / Build Tools 33.0.0。
- SDK 通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 或本地 `local.properties` 的 `sdk.dir` 配置。
- 构建入口：`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 已知限制
- 真实 SIM 来电、长时间待机、重启后行为尚待验证，详见 README。
- Release APK 默认未签名。
