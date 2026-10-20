# T09 源一致性补充准备（非验收）

2026-10-21，基于稳定 c38f937，只读预查；未修改生产代码或运行测试。本文件补充 t09-preparation.md，不作为行为已通过的证据。

## 版本的真实依赖

SqliteScopedReadAdapter.version 与 StorageResources.scopedReads.version/consistent 都只看 core，结束复核也硬编码 core；必须同步改。scoped 与完整快照的同一 D stamp 需覆盖既有投影依赖并集：

| D | 既有实际依赖 |
|---|---|
| D01 | profiles、calls 号码集合、positions；完整投影另含本人岗位 relation |
| D02 | positions、myPosition |
| D03/04 | calls；aggregate/statistics 即使 sumDuration 也读取 score、默认 zone 的 latest schedule |
| D05 | intervals；aggregate 读 calls；完整统计还读 profiles/positions/myPosition、score、zone、runtime quality 与 rolling 的时间 |
| D06 | schedules；workStatus 另读 calendar/overrides/leaves/leaveVersion、时间 |
| D07/08/09 | calendar / overrides / leaves；D09 完整投影还有 leaveVersion 与时间 |
| D10/21 | core settings、open statistics / audit |
| D11/12 | appearance / ringtone settings、playback |
| D13/14 | recording config 加 folder provider 观察 / AI config、纯派生 policy |
| D15/16 | recording event/link/tombstone/archive/index/text |
| D17/18 | folder config、provider walk / bindings、installed/access |
| D19/20 | runtime、recording playback/activity/jobs / diagnostics |

CompleteSnapshotAssembly 当前 D11/12/19 的 core stamp 同样错误。完整 D17 的 stable observation 也不能由配置 vector 证明；它是 provider_walk_not_atomic。

最小方向：复用协调器实际提交产生的全局单调 marker，为明确的源依赖资源取 last-change marker 的 max（不求和、不 hash、不借 UI token）。实现前核对现有 aggregate conflict keys 是否已足够复用；确需补资源 stamp 时用固定明确集合，不另造万能 registry。发布端必须覆盖实际写入，不能靠 UI impact 反推。RevisionVector 外形与 QueryStamp.scopeRevision 既有披露/冲突含义保留，后者仍是查询前捕获的全局 marker。完整快照继续 vector 前后复核及最多三次重试。

时间或 provider 观察不能冒充可原子版本化的存储：D06 work、D09 status、D05 rolling 同次计算只采样一次时间并准确披露观察性质。不能把 clock.millis 当 version，使前后复核不断自冲突。

## D13 平台观察

AiSettingsService.recordingView 调 folderView(systemFolder)，实际观察 provider displayName/access；ValueScopedReadSource.consistency 却仅将 D12/18/19 标为 live_probe。保持 D13 数据形状，整域标 live_probe 为最小准确修正，配置 version 与 collectedAt 各保持职责，完整快照同步披露。无需新公开 observed 字段。

## D17 字节预算与有状态续页

QueryService.query 的 maxBytes 分支（基线约63行）把最后返回 row.cursorKey 作 next；DirectoryScopedReadSource 的 row key 是资源 handle，而 query(after) 仅接受 walks 中的 walk token。source 已前进整页，完成时还删除 walk，因此预算截断后下一页会 stale，余项丢失。

可复现夹具：授权树根含3个音频文件，每个名字为512个 a 加 .wav，limit=3、maxBytes=800，短 size/modified。首项能返回，第二项超预算，next 包装文件 handle；下一页 walks[handle] 缺失。需验证 source 已完成而 QueryService 尚有 tail 的情况。

这是既有分页完整性契约的缺陷，应在 T09 迁移读取协调时修复并记录实际 RED；不新增目录功能。最小方案应在现有 cursor/session 有界保留未返回 tail 与真正 upstream continuation，或由 source 提供可恢复页 continuation。禁止把 row handle 当 continuation、盲重试整个 walk、无界缓存。仅让 source 预估 maxBytes 不足以覆盖 QueryService 添加 entityKey、引用、redaction 后的真实 UTF-8 大小。另核对 project 在预算确认前分配 text refs，对未返回行是否消耗引用容量，避免预算失败泄漏会话资源。

## 组合查询与回执窗口

ScopedToolReader.read(9) 顺序取 D06 zone、D05 aggregate、D04 pages；2/4/5/51 同样存在组合窗口。逐次查询各自一致仍可形成旧 zone 加新 score/calls。应在整个组合外取相关 source vector，结束复核，失败不披露本轮结果并恢复本轮 observations。有状态 D17 不可自动回放。

ReceiptVerifier.verify(81) 先 profileGone 后逐项 calls/archives/bindings；期间重建 profile 可导致错误 VERIFIED。63 的 archive/profile 指纹读回同理。T09 提供一致性能力，T12 整理回执消费者时不得漏掉；读回变更只能 COMMITTED_UNVERIFIED，绝不重放已提交命令。

两个精确 conflict key 缺发布者留给对应录音/runtime 片修复：OperationScopePlanner 的59依赖 runtime:recordingPlayback，而 play/stop/finishPlayback 只 changed(recording)；46/67/68/69/70 依赖 runtime:capabilities，但 runtimeChanged 只发布 runtime。不得通过把 expectedScopes 改宽来掩盖漏键。

## 必须落成的实际反例

- D04 cursor 后改 quiet 仍可续页，改 calls 则 stale；D05 aggregate 期间改 score/default zone、D06 work 期间改 override/leaves 必须复核失败。
- D09 跨 leave.start 的时钟夹具准确反映时间观察；D13 配置不变而 provider 撤权/改名时仍准确披露观察性质。
- 上述 D17 小预算分页无丢失/重复；source complete 后 tail 仍能继续。
- 组合 read(9) 在 zone 后暂停，改变 schedule/score/calls，不能成功返回混代；verify(81) 首次 absence 后重建 profile，不能 VERIFIED。
- CSV 一致性重读只包事实捕获，外部 documents.write 在重试外；冲突后文档仍只写一次，写后读回失败不得再次截断目标。
