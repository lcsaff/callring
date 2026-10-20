# T03 contracts 检查点

2026-10-20，基于83f6932。新增纯 JVM contracts，UI 全部 source set 不再依赖 backend。原始配置/厂商策略/不可用能力实现留 backend，UI 测试使用自己的协议假件。公开 folder 返回 Unit，内部命令绑定仍受保护；O51 改读安全视图，工具名/schema/外部结果不变；复制预览使用后台 AUTO 派生字段。

规格审查与独立质量审查均通过，无剩余问题。审查核对了实际代码、守卫、绑定写入、快照结构不变量、显式序列化与UI/后台真实生命周期测试的覆盖划分。

主门：contracts:test 为 NO-SOURCE；backend223/UI283/app220 项测试全部通过，无失败/错误/跳过；Debug 构建与UI/app lint成功，BUILD SUCCESSFUL31s。当前UI lint有31项警告及3项提示，app有6项警告；未将任务成功称为零警告。

额外编译：UI AndroidTest 与 app BackendQa Kotlin 编译成功，app compileDebugAndroidTestKotlin 为 NO-SOURCE；未安装设备或宣称设备测试通过。三个UI依赖报告（生产/unit/AndroidTest）都含contracts且不含backend。主负责人独立读取报告、JUnit和成功日志，并重跑实际守卫及9+4项脚本测试；规格/质量审查另做独立核对。

失败中间日志和预览/来源证明RED日志保存在本地 build/qa/layer-boundaries/t03-*.log，未删除失败记录；最终日志与逐类计数摘要见 t03-verification-summary.json。旧v1、S0 API冻结和81工具golden未修改。

T04原生领域、T05局部存储及累计失效仍待实施。contract inventory 的hash是当前代码评审护栏，不是历史版本声明；后续内部契约变更必须同步审查和更新。
