# T06 剩余设置切片准备（非验收）

基于b3f4ab7的独立只读复核。T05b正在实现quiet首片；本文件未声称其他设置已迁移。

| 入口 | 数据闭包及既有语义 |
|---|---|
| statistics | 开放interval、MAX已关闭end；启用max(clock,end)、关闭max(clock,start)，增/改一行；不重算旧calls.counted，真正启用后锁外调度，调度失败不回滚 |
| overlay | 单key；启用需OVERLAY权限，禁用不要求；更新决策输入 |
| score | 四key、最终事务dirty merge；不重分类或更新决策镜像 |
| appearance | SharedPreferences，零核心库；直接save的null保留旧seed，edits显式null可清除；必须唯一发布者，保留磁盘失败的内存回滚 |
| position | 目标行、trim名称唯一性EXISTS、本人岗位；删除还需caller引用EXISTS，同事务并保留FK；SQLite自增，不改calls |
| myPosition | 当前id/目标行；停用岗位只允许保留当前id；返回完整岗位列表是API明确需要 |
| schedule | 新增历史版本，effective=max(clock,maxEffective+1)；相同内容仍新增version，不重分类旧calls；reset同样append |
| summary | stats/overlay/quiet/myPosition/currentSchedule/score/appearance一致聚合；不能顺次调用七个公开API拼跨版本结果 |

## 历史更正的真实闭包

leave每次真实变化都增加全局leaveVersion，连仅改note也如此；WorkClassifier把它写入所有current事实，旧reclassify遍历hidden/uncounted在内的全部calls。因此不能只读旧新请假区间。必须保留一次事务内全历史分批读取、定向current classification更新，original绝不能改；这不是允许恢复BackendState全库副本。

calendar metadata/全年days任一变化才audit/reclassify。新增年份或版本变化影响全年的verified/version，不仅节假日；前一日跨午夜会影响次日及下一年Jan1。日期应按每通事件选择的历史schedule.zone计算，不能套当前zone。同步相同source/version保留published；最终事务复核准备期间并发metadata，网络/文件准备锁外可取消。

override实际变化才audit/reclassify，直接影响目标本地日和次日跨夜，显式REST优先级不变；数据库只存windows，空数组即REST，不能新增kind编码。

**当前不能直接假定“只按上述直接影响区间重分类”完全等价。** 旧reclassify本身会全库纠正其他陈旧事实。例如saveWorkSchedule不重分类，已有未来时间call可能仍指向旧config；随后任意calendar/override更正会顺带修正它。未证明所有current事实已与当前历史输入一致前，应保留原有全库批流式纠正语义，同时去掉全状态装载、档案加载和无关列写入。若后续证明更窄候选闭包足以覆盖这些副作用，再附证据收窄；不能无声改变算法。

## 实施约束

- calendar/work/leave更新与audit、current事实在同库单事务可见，不能拆多次提交以躲开批处理。
- ScheduleStore保留已有makeup day的working-name，不统一替换默认文案；保留config/leaves/interval/audit自增和唯一约束。
- 当前classification写入应按实际字段差异定向更新，保留未触及raw编码。尤其旧CallStore.putFact在reason与holidayName均未变时保留原始reason字符串；不能仅因leaveVersion推进就把未知reason强制改写成默认“未知”。original列不进入历史更正UPDATE。
- work/score/position/leave的dirty merge移最终事务，保持draft.field_changed/deleted/identity_changed区别；未改period沿用当前值。
- 建议顺序score/overlay→statistics→appearance→positions/myPosition→append schedule→override/calendar→leaves；用旧新样本对照original/legacy/DST/跨午夜。

证据入口：application/SettingsService.kt、SettingsValidation.currentSchedule、CallerProjection.validatePosition、domain/WorkClassifier.classify与reclassify、data/SettingsStore.kt和ScheduleStore.kt、platform/AndroidPreferences.kt。T06实施前核对T05b的最终窄端口签名。
