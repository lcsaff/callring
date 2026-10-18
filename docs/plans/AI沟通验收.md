# AI 沟通实施证据（进行中）

2026-10-18；任务 ai-implementation，execution epoch7。此文档记录当前证据，不代表 A1–A6 已完成或通过验收。架构/设计/计划仍使用各自已批准对象。

<a id="progress"></a>
## 已保留成果

- `049dc17`：公开 API、安全 DTO、MICROPHONE、版本协调器与主库提交接入。
- `b15a145`：原密文页补丁、凭据保留、录音/偏好/诊断/铃声发布、全量源预检/审计、显式安全投影、目录完整枚举、21域采样服务、72项严格工具定义及业务路由、前台票据、两协议客户端与会话执行核心。
- 当前补充：平台快照装配及使用实际 SQLite/录音存储的容量回归用例。

上述是组件成果。生产根装配、录音命令上下文完整接入、Android前台宿主、两设置/四导航/沟通界面、全部72项逐项真实操作复验、最终构建/lint及文档尚未完成；不能用注册表有72项替代全面操作验收。

<a id="q18-budget"></a>
## Q18：默认预算的实测阻塞

已批准默认 context=32768、输出预留4096，估算公式见架构#capacity。使用完整字段/全文/历史/工具 schema，未裁剪或省工具。

|用例|请求UTF8字节|保守估算|默认预算|结果|
|---|---:|---:|---:|---|
|仅21个空域manifest＋72工具，schema精简冗余表示前|24487|32056|32768|通过编码预算|
|同样空域、语义等价精简后|21009|28230|32768|通过编码预算|
|实际少量数据：1档案、1独立录音归档及短转写、实际21域/运行状态、72工具|29128|37161|32768|超限，预算门阻止首轮HTTP|

实际快照 JSON 为11165字节。精简只去掉空required/空properties，并用限定1–7键的共享日期窗口结构表达相同约束；全部72项仍存在且本地严格校验，不减少正文。真实少量数据仍不能通过，因此合成空域用例不能证明应用默认可用。

失败用例：`app/src/test/kotlin/com/greatwall/callring/bootstrap/CompleteSnapshotAssemblyTest.kt#realSmallStoresAnd72ToolsCanIssueFirstRequestUnderDefaultBudget`。假 HttpURLConnection 计数，仅在预算通过后才调用 exchange；没有真实付费API调用。失败发生在 ContextBudget.check，数据捕获和完整性/密钥哨兵断言已先通过。

报告：`app/build/test-results/testDebugUnitTest/TEST-com.greatwall.callring.bootstrap.CompleteSnapshotAssemblyTest.xml`，含 `REAL_DEFAULT_MEASURE bytes=29128 estimate=37161 snapshot=11165`。文件为构建输出，不提交或伪造测试成功。

最小建议：主负责人裁定将架构/设计默认客户端预算调整为49152（现有允许区间内），保留完整估算、全部数据/schema与服务端拒绝处理，然后在新批准下复验真实首轮HTTP。该数值仅为客户端限制，不承诺任意模型有该上下文能力。不自行变更已批准32768，也不通过摘要/删字段/删工具绕过。

<a id="verification"></a>
## 当前验证与边界

已跑 backend 全测试、app 全测试；在加入真实容量回归前均通过。当前真实容量回归为已知红项，后续最终结果须重新跑完整套件，不把历史通过当当前完成证明。

已有行为测试涵盖双页交错及冲突/非法清凭据、失败CAS不增版本、独立录音/审计与诊断尾部、目录500/501/变化/循环、协议配对及整批非法、预算/脱敏、前台重复迟到、共用资料补丁保留其它字段、会话同参重复不重写/旧版本拒绝。完整 V01–V09 与72项矩阵仍需补齐。

`tools/check_backend_boundaries.py` 已通过；生产根 API 与合同同步。设备 `192.168.10.149:5555` 实际检查为offline；没有覆盖安装生产包。实机权限/角色/SAF/CSV/tree/widget/overlay、真实付费模型、SIM/ROM后台及通话双方录音效果均未实测。assemble/lint最终证据尚未完成。
