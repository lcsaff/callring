# T05a SQL 观测检查点

2026-10-20，业务基线a0b420a。只加入可选实例级SQL读取探针和真实Graph基线，不改变业务读写、SQL过滤/排序、schema或事务。独立规格与质量审查均通过，无剩余问题。

审查独立核对了SqlAccess透明转发、六类移动计量、close幂等、SQL失败/提前退出、原始类型/BLOB、无探针行为、安全标签、真实JOIN/DatabaseUtils与旧Store/TableSnapshot/preflight接线。B档案及全部原始通话事实断言保留。两档安全JSON在app/build、根build和docs的SHA256一致。

主负责人独立读取JUnit与lint XML，并重跑15+4项守卫测试及两个架构检查。backend238/UI283/app226全部通过，失败/错误/跳过均0；contracts:test为NO-SOURCE。最终完整验证42s成功，额外backendQa及AndroidTest编译6s成功。UI31 Warning与3 Hint、app6 Warning，无Error；存在既有弃用提示。未执行设备测试。

实际基线与指标局限见t05a-implementation.md；消费行数不是SQLite扫描量。T05b资源、窄会话和累计通知尚未实施，P1/P5尚未解决，T05整体保持未完成。
