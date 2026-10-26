# AI 三个主题预设修复验收

## 范围与实现

修复 AI 声称没有联通红预设的问题，同时覆盖天空蓝与青绿色。三者的名称、标识和 RGB 由 contracts 的 ThemePresets.builtIn 唯一维护，UI 默认注册表和 AI 查询/工具描述共同使用。D11 的 scoped/current/full 查询保留原字段，新增只读 presets；写入仍走原有 edits.appearance 和持久化读回验证。

| 预设 | 标识 | RGB |
| --- | --- | --- |
| 联通红 | unicom_red | #E60012 |
| 天空蓝 | sky_blue | #1976D2 |
| 青绿色 | teal_green | #008577 |

未改变存储格式、默认颜色、预设顺序、自定义颜色、深浅模式和 UI 回退策略。外部工具兼容基线只精准调整 O36/O37 两个 description；参数、身份、确认、回执和默认值保持不变。新增守卫例外仅限共享目录中静态 ThemePreset 的名称参数，正反例覆盖其他文件、错误包、标识中的中文、普通提示文案和计算表达式。

## 回归证据

- RED：修改生产代码前运行 `gradlew.bat :backend:test --tests com.greatwall.callring.AiAppearancePresetsTest --console=plain`，3 项测试全部以断言失败，分别证明名称发现、D11 目录和工具读取缺失；无编译错误。
- GREEN：同类 3 项全部通过。涵盖三个中文名称发现读写工具、查询全部目录且无存储变更、从工具读取的 ID 执行保存；切换覆盖三个预设 × 三个深浅模式，读回 VERIFIED，保留既有自定义 RGB，非外观查询为零。
- UI：AppearanceThemeMapperTest 3 项通过，覆盖共享目录到显示主题的名称/ID/RGB、一致的回退、不可变注册表以及深浅模式矩阵。
- 守卫新增测试先失败再通过；test_backend_boundaries.py 共 6 项通过。
- 独立只读审查：未发现必须修复问题；确认共享源、字段兼容及 golden 仅两个描述变动。完整快照目录的直接测试可选补强，scoped/full 已共用同一投影函数。

统一验证 `python tools/verify_backend.py` 已通过：两项架构守卫、50 项架构/6 项后端守卫/12 项验证工具 Python 测试，backend 369 / UI 311 / app 361 项 JVM 测试（共 1041 项，零失败），真实 UI 编译类路径，Debug/Release/backendQa/双 AndroidTest APK 和双 lint。contracts 无测试源码，单列 NO-SOURCE；设备未运行。构建证据位于 app/build/qa/backend-refactor/summary.json 与其引用的日志、JVM XML 和 lint 报告。合并推送及远端 CI 的最终提交与结果以本次交付回复为准。

## 验证边界

此次未调用真实付费模型、未安装或覆盖手机应用、未执行设备测试。自动测试证明目录可见、工具调用和 UI 映射正确，不保证每个外部模型都能正确理解每次自然语言请求。尚未发布 APK，手机现有安装版本不会自动获得本修复。
