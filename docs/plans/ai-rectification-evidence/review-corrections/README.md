# Epoch 8 独立审查整改证据

基线 3303dabb7b0c747af12e6ccd0720e66cb134fa43；PR001–PR009 全部接受处置，原审查 verdict 不改写。本文记录实现与复验证据，最终验收由负责人和新的独立审查决定。

|意见|实现与验证|剩余限制|
|---|---|---|
|PR001|SecretRedactor 对 JSON 短转义/百分号/unicode/混合编码脱敏；合法 tool arguments 丢弃 wireArguments，双协议编码只用安全结构；Service replay、SpecialSecretWireRegression、ChunkRedaction 及原 encodedSpecialSecretMatrix 通过。|凭据均为合成哨兵，未读真实密钥。|
|PR002|ArchiveStreamParser 持久保存 JSON/字段/码点解析状态，源按 UTF-8 字节偏移分块；ArchiveBoundedParserTest 覆盖巨大转义字段、代理对、预算 0/1/2/3、中断/重开/坏数据/generation/v3 已完成与部分迁移。|线程中断与关闭重开证据；未宣称真实进程写中强杀。|
|PR003|OperationScopePlanner 从 D16 归档取号码，原生负 ID 无需 D03；原生/统计别名都保留模型已读版本，按 archive/recordingLink/profile 字段检查冲突。|确认后与确认前 generation 变化均拒绝写入。|
|PR004|采纳基线从权威归档有界读取 TextRef、比较 trimmed 指纹；空字段遵循原业务保留语义；姓名/部门不一致不能 VERIFIED。|不将长业务值写进诊断。|
|PR005|D01 搜索字段独立于全文字段，号码/姓名/部门 trim、子串、Unicode 忽略大小写、GLOB 字面量和分页 total 与 UI 谓词对比。|API29 SQLite 生产查询适配器测试。|
|PR006|新回复跟随消息及 Markdown 异步测量变化；用户浏览历史暂停跟随，可回到最新；十项成功回执可展开，异常和待处理仍可见。|真实历史滚动由 Compose 触摸回归；本轮不生成付费历史。|
|PR007|默认只显示目标模型/主机；服务详情展开协议/路由/profile，sendTo 仍使用同一完整目标快照，目标改变关闭旧详情。|真实默认页/键盘/详情以单独只读 device 证据为准。|
|PR008|边界例外限定原类、准确可选后缀、字段类型/默认值/顺序及目的列表/描述参数字符串位置；错类/重复/移位/同行无关中文均拒绝。|test_backend_boundaries.py 四项测试含多组反例。|
|PR009|新诊断导出逐步 opaque ID/状态/核验、每次实际编码的 schema/context 数量和单调阶段时序；生产 Service+codec 的合成 HTTP 边界验证，业务哨兵不泄漏。|原真实运行缺项不能恢复，详见 historical-diagnostics.md；未补付费调用，未重跑新增十条。|

13 种生产采纳流水线场景包含短/空/长 Unicode、旧未索引、正别名、统计关闭、缺失、确认前后变更、无关修改及故意不匹配。新“模型披露后归档变化”测试先失败，修复后通过，见 adoption-baseline red/green 日志。

原独立探针通过 4 项 app + 3 项 UI，见 review-external-green.log。原 PR002 两项 white-box 探针依赖已移除的 StagedReader/parse 私有成员，并在零预算时要求旧表行存在；原文件与断言未修改，不能声称它们全部变绿。其原失败记录在 review-red.log；替代行为验证使用永久 ArchiveBoundedParserTest，需独立审查对新解析器复验。

归档查询每次最多读取 4096 源字节，低于 4 字节预算不做 SQL；UTF-8 不完整尾部最多重读 3 字节，实际读取计入预算。解析字符串缓冲最多 4097 码点；检查点最多 128 KiB，深度32、每容器512项、字段名256、路径2048，超限明确失败，保留原 payload，不发布半成品。每块同事务持久化进度和派生文本；原 v3 raw staging 按每次64行清理。业务写入时原有完整对象投影路径保持不变。

本轮禁止新增付费模型请求、业务批次、卸载/清库。原十条合成档案、DARK、预算和凭据保留。原 device 目录未覆盖，原文件 SHA-256 单列以便复验。

最终本地全量检查：`python -u tools/verify_backend.py` 退出0，backend185/app169/UI210，失败、错误、跳过均0；Debug、Release、AndroidTest、backendQa 构建及 app/UI lint 通过。详见 full-summary.json、full-build.log、full-boundaries.log。另 `python tools/test_backend_boundaries.py` 四项通过。
