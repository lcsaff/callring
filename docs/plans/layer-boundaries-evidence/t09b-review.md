# T09b AI源与专用完整快照检查点

2026-10-21，生产基线b1bf4b0，门执行HEAD390c651。规格首审发现重叠双失败可能复活旧观察，真实反例后修复并复审PASS；主负责人补充的慢速续页handle租期反例亦已关闭。独立代码质量审查PASS，无本片待修Critical/Important问题。T09a/T09b均通过，T09完成；全分层计划仍待后片。

SQL源只取有租期事实，work/统计唯一领域计算归backend；FullSnapshotReader取专用深冻结事实并在全量分配前预检。D源版本使用真实资源last-change marker并集，scoped/full同步；完整服务额外复核内部D向量，时钟/provider与D19额外观察如实标live，不以公开RevisionVector声称物理原子。普通query/chat/discovery不捕获完整状态。

组合读取与回执验证拒绝混合版本，不重放已提交命令。coherent按attempt所有权清理资源，终止状态和稳定前驱解析防双失败/嵌套父失败恢复无效观察，并保留并发成功。D17保留真实upstream游标及有界tail，处理预算、撤权、目录清空、TTL/reset；租期过期返回CONFLICT并关闭walk。pending引用计数只回收未交付资源；完整快照成功后才交付，失败/重试回收。

旧完整状态入口和最后投影助手已删除，JSON/StrictJson迁入data且旧文件删除。剩余Context小服务/装配与recording目标查找T10、旧通知T11、初始化及fixture旧存储T13都有实际消费者和删除门；不保留新增兼容转发。

最终完整门94秒成功、147tasks（17执行/130缓存），backend296/UI298/app280全部零失败/错误/跳过；三个模块XML于本轮门开始后生成。contracts NO-SOURCE。主负责人独立核查日志SHA、XML/lint和四JSON，并重跑两守卫、17+4 Python测试及diff检查：105 JVM源、全部通过。UI lint31 Warning/3 Hint、app6 Warning，零Error；包含Debug及隔离测试Kotlin编译，未执行设备。

1千/1万无关B事实下访问窗口完全相同：scoped行1加count结果1；aggregate通话1、score设置4及zone1。全状态装载/完整捕获/修订增量均0；消费结果行不代表SQLite引擎扫描量。CSV oracle精确-text属性修正Git换行转换，保留1197-byte真实CRLF，不重写oracle或放宽断言；最终staged字节单独核查。
