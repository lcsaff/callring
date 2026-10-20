# T07 档案切片准备（非验收）

基于b3f4ab7稳定源码的主负责人只读检查；未实施、未验收。

- Caller search只搜索profiles的number/name/department，ignoreCase，trim查询，按number排序后分页。不要直接复用AI D01的callers UNION calls作为业务语义。SQLite查询必须匹配既有Unicode大小写及字面通配字符语义；复用经过验证的UnicodeSearchPattern时仍补原业务等价样本。
- editor需要目标档案、全部可选岗位（active或该档案当前停用岗位）、本人岗位；读取岗位列表是明确必要输入，不能误认为所有列表都必须只返回目标一行。
- profile投影含岗位relation（比较对方/本人level），改岗位/本人岗位需传播真实关系展示；普通name/department不影响决策输入，白名单才影响。
- saveProfile的baseline/dirtyFields需保留field白名单、baseline身份、未dirty沿用current、dirty冲突后验证及trim；whitelisted=null保留旧值。校验停用岗位允许保留原id，不能重选其他停用岗位。
- visible/detail投影有linkedScreenId依赖：公开originalClassification、screenedAt、decision/decisionNote取screen记录，source变MERGED；当前classification仍取可见provider记录。窄详情/报表必须读取这个直接关联闭包，不能只把单行raw当完整CallDetail。
- latest顺序time DESC,id DESC且hidden=0；search number为规范化数字，岗位name排序若改SQL需留意JDK与数据库字符串排序差异，可保留小规模岗位投影在应用层的旧排序。
- numberDataImpact/deleteNumberCore当前对call.number做NumberIdentity规范化后比较，而profile按规范化key查找。改WHERE number=?前需核对历史持久化是否保证canonical；不能默默舍弃非canonical旧行，也不能为此重建全状态。

## 删除外键顺序

CallStore.save先清除所有旧linked_screen_id变更，再插入/更新行，最后deleteMissing。calls.linked_screen_id为ON DELETE RESTRICT自引用，不能把旧顺序简化为单条DELETE WHERE number。

主负责人在内存SQLite上实测：同号码screen(id1)与linked provider(id2)用单条DELETE会触发RESTRICT；先删子再删父可行。此为机制预查，不能代替Android适配器最终回归。生产迁移宜保留同事务先解除目标关联再删除，检查真实预期数量及跨号码异常引用行为，不能关闭外键或清库。

证据入口：application/CallersService.kt、CallerProjection.kt、data/CallStore.kt、CallringDatabase.kt。后续以CallerAccessContractTest/删除关联设备或Robolectric回归验证。
