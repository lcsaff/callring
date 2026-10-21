# T15 生命周期缺口修复审查

基线 c426dd1。规格审查及证据补审 /root/t15_lifecycle_spec、随后独立质量审查 /root/t15_lifecycle_quality 均 PASS；无剩余可操作问题。负责人独立核验 11 路径冻结 766d13eb2053a48a7980c717583dcc0ae60159dc1d080f9c40dfbc376a8c521d，清单 SHA 3d52171eb28b653dce3853a3a6484a4d1773cb1dd8dd2d79943fdd3f81cd9299，无漂移。

已确认的 A6 缺口关闭：查询最终关闭与会话 reset 分离；关闭排空同步读，挂起的 coherent 不能在恢复后再读版本或发布观察；图与录音初始化互斥关闭；保留的存储引用不能重新打开 SQLite。完整快照在取密钥前检查 owner。来电统计/录音关联排空测试保持不变，物理存储关闭仍在排空之后。无新生命周期框架、兼容层、生产测试钩子、schema/协议变化。

回归 RED→GREEN 有实际日志；定向 3+23 项通过。完整统一入口 exit 0，backend 366 与 app 360 实际执行，UI 310 UP-TO-DATE，无失败/错误/跳过；contracts NO-SOURCE。两守卫与 50/5/12 Python 测试、实际类路径、Debug/Release/backendQa/AndroidTest 构建及 lint 通过。原始日志、JUnit 和 lint 已归档，未执行设备。

验证摘要 SHA：357c2af86846608ae4dce293a441193d258ba4c874fbaf6e040f9ce152de894b。接续最终版本及统一验收，不将本检查点称作已完成远端交付。
