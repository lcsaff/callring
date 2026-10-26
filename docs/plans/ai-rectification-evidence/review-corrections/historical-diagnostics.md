# PR009 历史诊断缺项与最小待决项

真实付费执行绑定提交 346e896683c094d92cc3e9e4d4e19ccc610df11c、APK SHA-256 f067c13c5802ea743a156cf2c78c46d52bba857d8fcd90559a68b32abf6e4415。原 device/build.json、real-model.json、日志和截图保持原样，不能用当前 APK 或合成结果替代该运行。

原记录有 10 次真实 HTTP 200、逐请求请求/响应字节、usage/cache、HTTP 耗时、FAST 标志、聚合工作量和写入核验计数，能证明部分上下文成本与业务闭合；原步骤 ID/逐步状态、schema 数量、上下文消息数及完整阶段时序未导出。后加 schemaVersion2/null 不表示原运行拥有完整 v2 数据。

可靠恢复排查：设备 journal 仅有合成档案快照/数量和 batchStarted/batchComplete/themeComplete；StepLedger、业务 revision 和上下文只在内存，原验收之后已 force-stop。原诊断文件没有这些步骤 ID，生产日志也未持久化。当前持久化结果只能证明最终值，不能倒推出原 receipt ID、阶段边界或每次 schema/history 数量。没有读取密钥或导出业务数据库，不能伪造历史字段。

新采集路径已在真实 ConversationService + CompatibleConversationClient + debug roundJson 中实现；synthetic-diagnostics.json 明确 actualProviderRun=false，使用隔离 SQLite API fixture 和合成 HttpURLConnection 响应，覆盖多轮编码计量、阶段、回执及去业务值。它证明当前采集路径的自动回归，不是新的真实 provider 或真机付费证据。

最小待决项：在严格 S12/V12 下，原 run 的上述缺项仍未闭合，请负责人明确裁定所需补证及成本授权。本轮不自行放宽验收。若要求新增一次只读真实请求，只能证明当前新采集路径，仍无法恢复旧 run 的 ID/时序；必须先明确输入、预算与新增调用授权。禁止为了补诊断重做“添加10个随机号码档案”。其余 PR 实现、验证与新独立复验可继续。
