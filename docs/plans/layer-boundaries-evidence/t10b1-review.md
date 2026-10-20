# T10b1 录音读取与AI音频边界检查点

2026-10-21，生产基线c188167，门执行HEAD0e2846f。规格修复复审与独立质量复审均PASS，无未决问题。T10b2录音业务/发布、T10b3组合删除/采用及T10c装配仍待实施，本片不代表T10整体完成。

Graph目标录音查找退出全状态，复用唯一CallingRowCodec并在同租期读取目标及必要hidden关联。普通metadata/generation/调度/发现/号码影响退出全归档列表；backend音频接口使用AudioReference，文件IO留app。旧all、collection resolver、File接口和无消费者默认删除。

未索引历史按4KiB源块流式提取元数据，不加载无关全文对象、不写D16索引完成标记；保持原号码匹配、必要字段、nullable和后续全文读取。冷热范围如实区分：warm号码投影仍遍历原号码，cold仍需扫描未索引源；计数不代表SQLite引擎扫描量。SQL目标行复用完整codec字段，不声称最少列。

三项实际反例修复：播放final只读generation，避免第二次目标全文解码；重复可空字段最后null清空旧值；分页两个有序分区分别取够limit个到期项再合并，避免全due数据的重复全剩余扫描。真实SQLite多页1万候选无漏重，两分区各9968消费行；future仍可额外读取，未承诺所有分布常量或线性成本。

主完整门110秒成功，315/298/297测试均显式新执行；首补门70秒，app新增两反例后299项实际执行；质量分页补门69秒，backend315和app302实际执行、UI298沿用主门XML。各次日志/任务状态/SHA独立记录，最终均零失败/错误/跳过，contracts NO-SOURCE。UI lint31 Warning/3 Hint、app6 Warning，零Error；Debug及隔离测试编译通过，未设备验证。

主负责人独立核对最终26源码指纹、三模块XML/时间戳、日志SHA、lint及10份安全访问JSON；两个架构守卫108 JVM、17+4 Python测试通过。新反例确实在修前断言失败，未以编译失败代替RED，也未删除冻结81工具/12API契约。详细命令、边界与后续真实消费者见t10b1-implementation.md；机器摘要见t10b1-verification-summary.json和分页专项摘要。
