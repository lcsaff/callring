# 真机补验发现与修复检查点

2026-10-21，基线092ec4c（0.3.9/12）。初次隔离真机测试：后端31项，1失败/6错误；UI83项，7失败，runner在180秒超时，未将此结果称为通过。主安装版本/安装时间及默认角色在初次后端测试前后未变。

已修复真实生产问题：SqliteCallerStore同一写事务内仅更新目标caller实际变化列，完整INSERT、缺行与影响行检查保留；档案提交对账已预热镜像中的目标白名单，镜像本已一致时不换实例，冷镜像仍保持冷状态。SQL触发器列所有权与外部写入后的旧镜像断言先RED后GREEN；不通过全状态刷新或兼容回退规避。

设备夹具修正：外键负例走真实立即约束的写会话，避免测试专用defer_foreign_keys在COMMIT失败后污染原生会话；leave_revision键对齐真实reader；初次决策快照显式refresh，后续identity/rollback断言保留；排空测试先预热再持有独立写锁，仍验证实际排空。

UI修正仅四份AndroidTest：编辑保存验证当前完整baseline/显式白名单；widget假API实现setQuietEnabled并复用已有状态和通知；旧56/48尺寸断言对齐此前0.3.1已确定的44/40布局及现有JVM断言，保留交互检查，不改生产视觉或声称48dp点击标准已验收。其余两项悬浮窗在用户关闭外部小窗后，以原APK无源码修改重跑2项通过；截图含私人信息，只在本地保存，不入库或发布。

版本准备faf8039（0.3.10/13）。12份Kotlin源码冻结8d0a57c5b0998afd29f7eb52e9ea5369dadc74339346131f946d2befd874a7f6，独立规格/质量审查均PASS。负责人核全部文件SHA与字节、日志/退出码、JUnit实际计数及哈希。一次统一门通过：backend366、UI310、app361共1037项均实际执行，失败/错误/跳过0；contracts明确NO-SOURCE；所有构建、实际类路径与lint实际执行通过，两守卫及50/5/12 Python用例通过。

重连后最终真机复测：后端31/31、UI83/83通过（UI实际198.275秒），日志退出码、摘要SHA均已核对；临时悬浮窗权限恢复、两测试包停止成功，手机主安装版本/安装时间及默认角色与测试前一致。

首次修复版UI仍有小组件双宿主同步失败。补齐UiTestApplication中与生产Application相同的application生命周期observeWidgetUpdates桥接；WidgetHostTest原10秒条件与双宿主断言保留，仅增加失败状态诊断。最终13份Kotlin冻结e934cadef2566ac852620ed495b4e8e72693827c62fe1eb8137ec2af80744f52，规格/质量复审PASS。整组runner预算从180调至300秒，因为前次整组实际180.616秒；不增加单测等待、不跳过测试。两次冷启动单独WidgetHost诊断在90秒未结束，无断言结果，不计为通过；正常完整83项中该用例已通过，独立冷启动仍保留观察缺口。

最终统一本地门再次exit0；JVM366/310/361共1037结果无失败/错误/跳过，本次均UP-TO-DATE（前一相同JVM源码门实际执行）；contracts NO-SOURCE，实际执行UI类路径与双lint。原始日志/JUnit/lint共233文件已在主工作区build/qa/device-20261021/final-local-gate按索引校验保存，红绿证据和APK另存final-worktree-evidence及final-artifacts。

A9仅隔离自动化设备测试通过。真实SIM/待机/重启、真实桌面固定小组件/后台悬浮窗、主包覆盖升级及真实下载未验证。未覆盖安装主包、清理用户数据或改变系统角色，未发布Release。详见[机器摘要](device-fix-verification-summary.json)。
