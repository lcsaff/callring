# 功能与通话详情整理 Implementation Plan

> 按 subagent-driven-development 执行后台迁移并独立审查；主代理负责界面整合与最终验证。用户已确认设计，连续执行。

**Goal:** 移除旧功能链路，统一本地转写并落实分区阅读界面。

**Architecture:** contracts 删除专属旧 API；backend 保持唯一命令与事务；app 注入本地转写；ui 只展示与调用正式能力。旧录音内容及共有备份设施保持兼容。

**Tech Stack:** Kotlin、Compose Material 3、JVM/Robolectric、Android、Python 架构守卫。

## 后台迁移

- [x] 在 `backend/src/test/kotlin/com/greatwall/callring/RecordingWorkflowTest.kt` 增加自动流程本地转写及不上传音频的失败回归；运行 `./gradlew.bat :backend:test --tests '*RecordingWorkflowTest'` 确认红灯。
- [x] 修改 `RecordingWorkflow.kt`、`RecordingConfiguration.kt`、`AiSettingsService.kt`、`RecordingAssembly.kt` 及 AI 客户端，将缺少全文时的转写调用迁到 `OfflineTranscriber.transcribe`。共用全局互斥、取消、方向与 generation 检查，先保存全文再分析，失败不回退网络。
- [x] 删除 cloud transcription 专属公开字段、复制和测试能力及无消费者平台上传适配，逐项迁移 AI 工具消费者及协议基线，保留唯一旧编码读取。
- [x] 在 `SystemRecordingDiscovery.kt` 取消独立录音自动准入和天数窗口，保留已有通话精确关联与稳定性门；删除对应配置和调度字段，迁移测试。
- [x] 删除 storage 管理专属 contracts/backend/app 实现与测试，保留备份恢复复用的基础设施；迁移组合根及 Fake。
- [x] 运行 backend/app 专项，审查全部契约变化。

## UI 整合

- [x] 修改 `AiSettingsScreen.kt`、`AiSettingsViewModel.kt`、`CredentialDialog.kt` 和 `AiRoutes.kt`，只提供分析连接与本地转写说明、自动分析开关。
- [x] 修改 `RecordingSettingsScreen.kt` 和 `RecordingRoutes.kt`，删除天数草稿与重复导入意图。
- [x] 拆分 `ArchiveScreen.kt` 为通话摘要、录音文本、分析结果与菜单组件；保留分析字段确认和错误状态，不引入音频进度等新业务能力。
- [x] 修改 `CallerListScreen.kt`、`HomePager.kt`、`SettingsOverviewScreen.kt`、`SettingsRoute.kt`，迁移职位入口、删除数据管理路由。
- [x] 更新现有 UI 测试覆盖可访问图标、菜单、文本展开和建议确认；移除已退役功能测试。

## 交付

- [x] 同步 PROJECT.md 与 README 产品行为，保留用户原有改动。
- [x] 按顺序执行规格审查和质量审查，解决发现。
- [x] 完成同一完整统一门、隔离真机后端及录音专项、模拟器 UI 整套；记录实际执行、缓存和超时重跑结果。
- [x] 精确暂存本轮文件、提交并集成远端 main。

最终推送与对应 SHA 的 CI 结果由交付回执记录；不自动发布 Release。
