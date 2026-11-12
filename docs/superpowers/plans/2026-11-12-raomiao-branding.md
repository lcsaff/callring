# 扰喵簿名称与图标实施计划

> **For agentic workers:** 使用 executing-plans 在当前会话逐项执行；用户已确认名称及三根胡须版本，无需再次设计确认。

**Goal:** 应用显示名称改为“扰喵簿”，桌面使用用户选定的白眼猫账本图标。

**Architecture:** 名称唯一来源为 ui 的字符串资源，app Manifest 与小组件共同消费。图标素材由 app 持有，使用 Android 自适应图标及独立单色通知图标；不改包名、组件身份、版本或数据。

**Tech Stack:** Android XML 资源、Kotlin/Glance、现有 Gradle 与 Python 验证门。

---

### Task 1: 名称及文档

- [x] 在 `ui/src/main/res/values/strings.xml` 添加 `callring_app_name=扰喵簿`。
- [x] `app/src/main/AndroidManifest.xml` 的 application label 引用 `@string/callring_app_name`；`ui/src/main/kotlin/com/greatwall/callring/ui/widget/CallringWidget.kt` 使用同一资源。
- [x] 将 `ui/src/androidTest/kotlin/com/greatwall/callring/ui/widget/WidgetHostTest.kt` 的两处旧标题断言更新为新名称。
- [x] 更新 `README.md` 产品名称与定位，`PROJECT.md` 记录品牌资源归属。

### Task 2: 图标及通知

- [x] 将确认的三胡须猫账本保存为 `app/src/main/res/drawable-nodpi/ic_launcher_art.png`；仅移除预览白边，保留造型及表情。
- [x] 新增 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`，背景为暖橙色，前景通过 inset 保留裁切空间；Manifest 的 icon 与 roundIcon 共用该资源。
- [x] 在 ui 添加单色猫账本通知资源，`UiNotifications.kt` 的 smallIcon 使用该资源，避免使用彩色自适应桌面图标。
- [x] 原 `ic_callring` 若仍供隔离 QA 通知使用，则保留。

### Task 3: 验证与交付

- [ ] 运行 `git diff --check`、`python tools/verify_backend.py`，确认完整门的当前结果。
- [ ] 用 SDK `aapt dump badging` 检查最终 Debug/Release APK 的名称、包名与图标引用；检查 APK 内图标存在。
- [ ] 按本次文件提交，审查并合入 main，推送后确认该 SHA 的 GitHub CI；不发布 Release、不安装生产包。
- [ ] 保留原工作区其他任务改动及验证日志。
