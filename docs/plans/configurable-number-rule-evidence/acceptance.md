# 可配置号码规则实施验收（2026-10-23）

## 范围与当前状态

按已批准的 `docs/superpowers/plans/2026-10-22-configurable-number-rule.md` 实施，版本 `0.5.0` / `17`。规则、存储、消费者、AI 和页面已完成，最终本地统一门通过；设备验收未执行。

## 实现与阶段证据

- `306d276`：纯领域规则与匹配策略，默认兼容 3/5；backend 376 项，其中规则 7 项通过。
- `4cf215a`：规则原子持久化、严格唯一编码解释器、持久版本冲突、共享快照和生命周期；backend 384、存储 3、AssemblyLifetime 11 项通过。
- `11035ac`：迁移并删除固定规则消费者，首次匹配事实贯穿异步记录及来电复核、导入整批一致；backend 390、app JVM 368、最终 app 定向 37 项通过。
- `2cb1c58`：局部与完整 D10 共用规则读取和投影；20 项定向测试通过，D01/D16 无无关版本变化。
- `9b64b0d`：AI 规则读写、精确作用域、取消/重复提交/读回验证及真实 D10 来源证据；10 个 backend 套件 58 项通过，app 装配和 74 工具业务集成通过。
- `627994b`：算法边界守卫及违规/合规样例，53 项 Python 架构测试通过。
- `8e46132`：规则编辑页面、组件规则加载屏障、号码提示订阅与刷新隔离，完整 UI JVM 测试通过。
- `aa5b893`：Activity 冷启动规则屏障、原始导航保留、失败重试、最新请求与关闭保护，纳入 UI 定向测试。

红绿日志及 XML 位于工作树 `build/task2-evidence` 至 `build/task6-evidence`，Task1 使用 Gradle 结果 XML；最终交付保留统一门摘要和运行日志。算法替换验证复用实际主调用链测试（NumberRuleConsumerTest、CallingSqlSemanticsTest、AssemblyLifetimeTest），不添加重复包装测试类。

## 协议增量

保留所有旧工具身份、编号和参数；O01 `root_checkNumber` 的三个结果字段不变，读取策略改为权威 D10 来源，并在回执携带实际 stamp。新增 X10/82 `settings_getNumberRule`、X11/83 `settings_saveNumberRule`；普通规则保存无需单独确认，但仍经正式业务命令、精确作用域和持久读回。兼容基线仅作上述已审核增量。契约清单仅更新 ChangeFeed 和 SettingsApi 对应记录。

## 设备验证限制

`adb devices -l` 无连接设备。真实 SIM 来电、处理中改规则、AI 真模型保存、重启、旧库真机覆盖升级均未验证；JVM/Robolectric/编译结果不替代这些实测。自动化覆盖旧库缺键、配置持久重开、损坏配置、事务回滚、并发、取消、关闭和异步来电事实。

## 验证与交付

统一门 `python tools/verify_backend.py` 在提交 `0f32260b3a00c9b69b34efead468e8c59c406b8c` 通过。backend 402、UI 340、app 372 项测试全部零失败/错误/跳过（共 1114 项）；contracts 无测试源。13 项 Gradle 任务、真实 UI 类路径、Debug/Release/backendQa/双 AndroidTest APK 构建及双 lint 通过。UI 测试为 UP-TO-DATE，结果 XML 已由统一门读取并校验。两项 Python 守卫和 53+6+12 项 Python 测试通过。完整摘要见 [verification-summary.json](verification-summary.json)。本地门之后仅更新验收文档；最终提交、推送和该提交 CI 结果以交付回复及 [GitHub Actions](https://github.com/lcsaff/callring/actions/workflows/build.yml) 为准。按用户要求减少审核流程，Task5 起使用实现者自查、必要定向测试和最终统一门，不继续逐任务双轮独立审查。


## 实际变更范围

相对已批准计划提交 `35c27f6`，共 111 个文件：算法与业务表现 30、契约与接线 14、查询投影 7、测试 49、守卫与工具 3、文档与证据 7、版本 1。该分类按实际路径逐一归类；存储严格解码、冷启动导航及精确刷新均纳入，而非套用原先文件数估计。

本次不发布 GitHub Release。保留用户原有未提交改动；工作树阶段日志和统一门完整日志另存项目 `build/configurable-number-rule-evidence`。
