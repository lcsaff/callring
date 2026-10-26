# T09 报表、源版本及完整快照准备（非验收）

独立只读复核基于b99c5ae；未实施。当前T06a正在进行，实际端口签名以其后检查点为准。

## 实际源与依赖

| D域 | 当前版本 | 实际输入 |
|---|---|---|
| 01 | core | callers、calls号码并集、positions |
| 02 | core | positions/my_position；current仅本人岗位 |
| 03/04 | core | 全calls/visible calls；aggregate还涉及score与默认zone |
| 05 | core | query为statistics intervals，aggregate实际转D04 calls，score/reportTotals还依赖score及最新zone |
| 06 | core | config历史/current；work还需calendar/override/leaves/version及观测时间 |
| 07/08 | core | calendar years/days；overrides |
| 09 | core | leaves，status随当前时间变化 |
| 10/21 | core | settings与开放interval；audit |
| 11 | preferences | appearance，当前绑定变化也误使其失效 |
| 12 | ringtone | ringtone及播放观察，live_probe |
| 13/14 | recordingConfig/aiConfig | 安全views；目录授权/displayName、派生profile/thinking |
| 15/16 | recording | events/links/deleted及archive元数据/文本 |
| 17 | recordingConfig | provider目录外部变化，已有provider_walk_not_atomic |
| 18 | preferences | 存储绑定及installed/access外部观察，live_probe |
| 19 | preferences（不覆盖实际输入） | runtime、recording播放、workflow activity、scheduledJobs |
| 20 | diagnostics | 已存diagnostics与文本 |

D01–10/21当前共享core会使quiet变更误失效通话游标；拆版本不能漏联表和聚合输入。D19版本不应继续与preferences等同；D06 work、D09 status随时钟改变，不能错误声明稳定source_snapshot。外部权限/播放/目录无法靠数据库版本证明原子性，保留或准确收紧live/provider声明，不伪造全局一致。

CompleteSnapshotAssembly当前D01–12/21默认core，其中D11应关联preferences、D12应关联ringtone；D19运行观察也不能盲贴core。全局vector前后复核不能修正错误的每域sourceVersion标签。full D19只有runtime/playback，scoped D19还有processing/jobs，保留各自既有投影，勿强行统一。

## 读一致性与职责

QueryService已在调用source前捕获version与scopeRevision、结束后复核；保留顺序。StorageResources内部三次重读不替代外层cursor版本保护。ScopedToolReader多个source的组合需要相应跨源vector复核；SourceStamp.scopeRevision是披露/写冲突证据，不能换成UItoken。

SQL adapter仍直接调用safeValue、classifier/accumulators并编排计算，row还依赖platform.ai.parseConversationJson。SQL只保留过滤/排序/原始typed facts，业务计算/公开JSON归application，持久JSON codec归data；保留Long、长文本引用、Unicode码点、游标及完整性限制。

## 完整快照闭包

FullSnapshotReader现仍经captureFull加载BackendState+audit；替换专用模型必须保留profiles及仅calls号码、positions/myPosition、全部rawcalls（hidden/关联/current/original/legacy）、intervals/score/quiet/lastMode/overlay、全历史schedule/calendar/override/leaves/version/audit。保留大小预检、20000项/字节预算、D域计数、秘密脱敏及全局vector前后最多三次只读重试。preferences/audio/runtime仍独立source，不能塞进SQL会话。

## 报表与导出

报表需目标visible calls及linked screen原始事实、profile/positions/myPosition、score、默认zone、stats与独立runtime quality。Home需latest/workstatus/开关/ringtone/runtime，不能只改latest SQL就称无全状态。

原CSV在一致读中捕获后退出读取再documents.write，这一点正确。流式方案可在WAL只读snapshot内输出但不持协调fence/写事务，也可先写临时产物再复制目标。**目标文档写入绝不能置于自动版本重试块**，否则冲突重读可能重复/截断外部文件。CSV公式和重复评分仅推进区间内previous，不引入区间前扣分。

证据入口：bootstrap/ScopedQueryAssembly、ValueScopedReadSource、CompleteSnapshotAssembly，data/SqliteScopedReadAdapter，application/QueryService、ScopedToolReader、CoreSnapshotProjection、ReportsService、HomeService。
