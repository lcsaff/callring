# T06a 简单设置检查点

2026-10-21，生产基线 b99c5ae；中间父提交仅为准备文档。score、overlay、statistics、appearance、positions/myPosition、settingsSummary 及相应草稿/命令入口完成窄端口迁移。独立规格审查和质量审查均 PASS；没有剩余本片阻断问题。T06b 历史设置尚未开始，不将 T06 整体勾选完成。

各服务仅获取所需 typed 能力，SQL 复用原连接与租期；草稿最终事务内合并。实际变化单次发布，无操作零推进。外观唯一发布且零核心库打开；统计保留历史事实与锁外调度；摘要对实际读取的作用域进行跨资源复核。真实 Graph 的 1千/1万数据证据，除 unrelatedCallCount 外两组 JSON 内容逐项相同；局部操作全状态/完整快照增量均零，无关 B 事实保持。实际设置 VM 的累计订阅与加载计数证据见实施记录。

已删除对应旧服务 bodies、草稿 premerge、appearanceDirty、saveAppearanceExact、validateQuiet、偏好双发布、unsupported 默认装配、双 core/quiet 参数，以及先前只用于能力证明的全量统计入口。用户追加的“兼容代码/旧实现删净”纳入主计划与 legacy-removal-ledger；仍有真实消费者的 BackendContext/全状态/UI 旧分支按 T06b–T13 删除门追踪，不能作为最终交付保留。

主负责人独立重跑两个守卫和17+4项 Python 测试，均 PASS，并独立读取完整门日志/XML/SQL证据。完整门 t06a-freeze-final.log：BUILD SUCCESSFUL46s、146 tasks；backend257/UI293/app236，失败/错误/跳过均0。包含 Debug/lint、BackendQa Kotlin 和 UI AndroidTest Kotlin 编译；contracts:test为 NO-SOURCE。UI31 Warning/3 Hint、app6 Warning，无 Error。

质量收尾随后将 AndroidPreferences 中单调用的 saveAppearanceInternal 合并到同步 override。该机械清理后定向运行 PublicationAdaptersTest 和 SimpleSettingsAccessContractTest，共3项，全部通过；t06a-cleanup-followup.log 为 SUCCESS13s、52tasks。最终质量复核 PASS。没有把清理前的完整门声称为清理后的全量重跑；当前 app XML 已被定向运行替换，完整门计数为此前主负责人实际读取的结果。未执行设备测试。

检查点后的追加复核：主负责人发现已取消 Job 在 quiet/score/position 纯预验证失败时可提前返回 VALIDATION，绕过原入口的取消检查；独立质量审查通过只读源码复核确认同源差异。该场景未被本检查点测试覆盖，已纳入唯一 T06b 实现者的首组真实反例和最小修复，不能将此前 PASS 解释为该边界已验证。执行与修复证据随后见 T06b 记录。

后续收口：T06b 已真实复现上述三处取消差异并修复，包含在其最终263/295/243完整门和双审中，见 t06b-review.md。
