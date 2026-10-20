# T08 来电关联准备（非验收）

基于b3f4ab7稳定源码，只读预查，未实施。

T06完成后的删除审计补充：StorageMigration.upgrade 仍调用 StorageSession.load/saveCalls，并对 logId==null 的 current 重分类，v1还调用旧 CallCorrelation.reconcile。T08删除旧关联入口时必须同步该真实升级消费者，使用同一原生分类/关联算法和定向存储，保留现有v1/v2→v3迁移行为，不新增schema版本。CallringDatabase.onCreate 也通过 StorageSession.load/save 导入初始日历；T09/T13须改定向初始化，不能在最终旧协议守卫豁免。见legacy-removal-ledger。

- CallCorrelation的候选度数使用完整同号码索引；直接匹配±5000ms且account相等或任一unknown，只有双向各唯一才关联。缓存候选最多两个以判断歧义，不能把LIMIT1误作唯一。
- 指定center只限制待写集合为±10000ms，候选索引不因此截断。首版可按设计允许读取该号码全部候选，不读其他号码；若要进一步窗口化，必须证明反向候选度数闭包，不可仅加载写窗口。
- 旧reconcile按time排序，旧SQLite装载按id建立插入顺序；窄查询用time,id稳定顺序保留同时间行为。hidden/count/result/legacyResult仅按原规则变更，不改original事实。
- 系统导入先按批次logId查现存行（可能属于其他号码），旧新number/time/account变化均进入受影响闭包；不能仅按新号码查询漏掉全局logId唯一性及旧关联。
- 登记screeningKey保留规范化号码+timestamp+可选trim account的现有字符串编码；重复已有会话返回原id并保留首次originalFacts。不启用统计的事件仍返回-1。
- 旧登记重复返回id后仍notifyChanged是当前额外通知；新实际MutationDelta应无写零失效，保留命令完成证据，不机械复制这个耦合。
- updateDecision仅目标id，id<=0或缺失/相同值不产生变更；同步保留decisionNote与legacyDecision清除规则。
- 关联写继续先解除旧linked_screen_id再插入/更新、最后建立新关联，避免UNIQUE引用交换和RESTRICT删除失败。ID使用原SQLite自增，不能max+1替代。

决策读取继续专用不可变镜像；普通通话结果和关联变化不重建镜像。系统期限、prepared/commit授权、降级和调度位置需用已有SessionGate/PreparationGate/IncomingCallCoordinator测试验证。

证据入口：domain/CallCorrelation.kt、application/BackendContext.recordIncoming/updateDecision/importSystemCalls、data/CallStore.kt及平台来电coordinator。最终用CallAccessContractTest的1千/1万无关号码数据及关联旧新闭包反例验收。
