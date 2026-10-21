# AI 三个主题预设修复实施计划

> 按 executing-plans 在当前会话逐项执行；沿用当前 checkout，独立开发分支，保留已有改动。

**Goal:** AI 可发现、读取并切换联通红、天空蓝、青绿色。

**Architecture:** contracts 保存内置预设元数据；ui 生成调色板；backend 的 D11 源投影及外观工具描述共用目录。外观写入、并发保护和刷新仍走原入口。

**Tech Stack:** Kotlin/JVM、Compose、JUnit、Gradle、Python 验证工具。

## 1. 复现与回归

- [x] 在 backend/src/test/kotlin/com/greatwall/callring/AiAppearancePresetsTest.kt 加入三个名称的工具发现、D11 查询目录、按读取标识写入并验证深浅模式保留的测试。真实 AppearanceQueryProjection → QueryService → ToolExecutor/ToolBatchEngine → Settings API；非外观查询计数须为零。
- [x] 运行 `./gradlew.bat :backend:test --tests com.greatwall.callring.AiAppearancePresetsTest`，确认因缺预设元数据失败。

## 2. 最小修复

- [x] 新建 contracts/settings/ThemePresets.kt：ThemePreset(id,name,seedRgb)，内置目录使用不可变列表，内容为 unicom_red/联通红/0xE60012、sky_blue/天空蓝/0x1976D2、teal_green/青绿色/0x008577。
- [x] ui/app/bridge/AppearanceThemeMapper.kt 的默认 registry 从该目录 map 到 ThemeDefinition.fromSeed，保留动态注册能力及回退。
- [x] backend/conversation/query/SettingsQueryProjections.kt 提供共享 appearanceQueryValue，合并 safeValue(appearance) 的现有字段及 presets；D11 源投影和 FullSnapshotProjection 共同使用。
- [x] OperationCatalog.kt 的外观读写说明从目录生成可选项，明确 PRESET/presetId 组合与保持深浅模式；不改工具参数或身份。
- [x] 扩展 UI mapper 测试，核对三个共享预设的显示名称和实际主题色。
- [x] 复跑回归并核查 ToolCompatibilityBaselineTest。外部协议仅增加只读 presets 和说明；如基线变化，逐字段审查并精准修改，禁止重生成。

## 3. 验证和交付

- [x] README 同步 AI 主题预设能力；设计文档补充三个颜色及实际证据。
- [x] 运行 `python tools/verify_backend.py`，检查统一门及 XML 结果，明确设备/真实模型未验证范围。
- [x] 运行 `git diff --check` 并审查本次文件，记录验收证据。
- [ ] 仅提交本次文件，合并 main、推送并等待对应 CI；保留用户文件。不默认发布或安装 APK。
