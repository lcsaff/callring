# S01 Markdown兼容探针：需裁定测试运行时

2026-10-19，基线 `387d992edab131b7ddd2f22cf72981a0bf649915`。本文件记录部分实施证据，**不是A7或整体整改完成证明**。S02–S12尚未实施，未访问设备或调用真实模型。

## 已执行

|检查|实测结果|
|---|---|
|JDK17.0.13基线 backend/app/ui 单元测试|123/135/202项，失败0，错误0，跳过0；Gradle成功|
|新增MarkdownCompatibilityTest，旧纯文本UI|1项失败：不能显示去掉Markdown语法的独立标题，符合缺陷|
|固定core+m3 0.40.2，Kotlin/UI编译|通过|
|JDK17 UI smoke|失败：`MarkdownKt` class major 65，当前JVM最多61，UnsupportedClassVersionError|
|JDK17 assembleDebug/D8|通过，未更改JVM目标1.8或Compose BOM|
|JDK21.0.12.1 UI smoke|1项通过，失败0/错误0；测试XML time=10.706秒|
|依赖解析|core/android及m3/android均0.40.2；markdown-jvm0.7.3、immutable-jvm0.4.0；实际Compose runtime/foundation1.10.0、material3 1.4.0|

JDK21第一次复核时标题已渲染，列表被旧Screen静态内容挤出测试视口；仅将smoke配置固定为600×1200dp后通过，未放宽内容断言。窄屏/IME/可访问性仍须S10验证。首次有两个Gradle任务重叠，定向任务遇到测试输出目录文件锁；此后串行执行，不把文件锁当产品缺陷。

当前debug APK为47,274,052字节，SHA256 `855b99632e599090040d8a30186206e736c9f5dc03fdf1d78d75754e0505fe38`。本工作树尚未保存同条件基线APK，**没有可靠APK增量值**；其他目录旧APK不作比较。Android API29及真机运行未测，D8成功不等于设备运行通过。

## 复现命令

```powershell
# 默认Java17：Robolectric无法加载依赖的Java21字节码
.\gradlew.bat :ui:testDebugUnitTest --tests '*MarkdownCompatibilityTest' --console=plain

# 临时命令级JDK21，仅探针验证，不修改机器/仓库默认Java配置
.\gradlew.bat '-Dorg.gradle.java.home=D:\Apps\jdk21-temurin' :ui:testDebugUnitTest --tests '*MarkdownCompatibilityTest' --console=plain

# 原JDK17可编译、D8与打包；独立执行避免smoke失败阻止assemble
.\gradlew.bat :app:assembleDebug --console=plain

.\gradlew.bat :ui:dependencyInsight --dependency multiplatform-markdown-renderer-m3-android --configuration debugRuntimeClasspath --single-path --console=plain
```

版本与用法来源：[固定v0.40.2 README](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/v0.40.2/README.md)。class版本错误来自实际本地smoke，不从README推断。

## 给主负责人的最小裁决请求

建议保留0.40.2，让UI测试运行时使用JDK21；保持Gradle/应用编译JDK17、JVM目标1.8和现有Compose BOM不变。可在ui/build.gradle通过Test任务的javaLauncher指定21，再用本机已装JDK21验证，文档明确此测试前提；不硬编码机器绝对路径进产品。**仅UI测试launcher的最小改动尚未实施/单独验证**，现有通过证据是整个Gradle命令暂用21。

若必须维持测试运行时17，则需要另探测较早Markdown版本；本轮未换版、未改BOM、未自制parser，不能声称旧版兼容。请主负责人确认采用JDK21测试launcher方案或安排兼容版本探针，再继续后续任务。当前固定0.40.2接入与失败/通过测试均保留在隔离分支，不应在默认Java17的全测试未恢复通过时集成。
