# 全部已有通话的录音匹配实施计划

**Goal:** 为所有已有通话匹配授权目录中的录音，找不到则跳过。

**Architecture:** 修改 backend 的 SystemRecordingDiscovery 候选策略，复用既有窄查询和 app SQLite 数据源，不新增接口。独立录音仍保留原导入规则，补配路径单独复核已有通话身份。

**Tech Stack:** Kotlin、JUnit、Robolectric、SQLite、Gradle。

## 任务 1：红灯回归

- [x] 在 `app/src/test/kotlin/com/greatwall/callring/platform/ai/SystemRecordingDiscoveryTest.kt` 增加历史通话、当前规则拒绝、无匹配目标、目标变化和去重测试。
- [x] 执行 `./gradlew.bat :app:testDebugUnitTest --tests com.greatwall.callring.platform.ai.SystemRecordingDiscoveryTest --console=plain`，核对历史通话和规则不匹配用例因未导入而失败。

## 任务 2：最小修复

- [x] 在 `backend/src/main/kotlin/com/greatwall/callring/application/recording/SystemRecordingDiscovery.kt` 复用 `findCalls` 与 `linkedRecording` 判定唯一历史目标；扫描、提交、复制复核此条件。
- [x] 保留独立导入、稳定观察、取消、歧义及墓碑判断，策略签名增加历史补配版本。
- [x] 重跑任务 1 命令，确认新旧用例通过（26 项）；设置页专项 12 项通过。

## 任务 3：验证和交付

- [x] 同步 PROJECT.md 和 README.md 的实际行为，审查差异及 `git diff --check`；独立代码审查无阻塞项。
- [ ] 执行 `python tools/verify_backend.py`，核对本轮结果；ADB 离线如实记录。
- [ ] 只提交本次文件，合并远端 main，若源码变更重新验证；推送并确认最终 SHA 的 CI。
