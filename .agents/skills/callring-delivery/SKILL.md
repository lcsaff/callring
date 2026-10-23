---
name: callring-delivery
description: Use when callring development reaches completion, changes need committing or merging and pushing, or a version bump, APK delivery, or GitHub Release is requested. 开发收尾、提交合并推送、版本递增、发包时调用。
---

# 开发交付规则

## 开发收尾

- 以用户本次明确的交付范围为准；完成开发默认包含提交、合并 main、推送 GitHub；交付安装包时包含发布 GitHub Release。
- 审查最终变更，执行与变更相关的测试、构建及边界检查；涉及设备行为时补充对应设备验证，并如实记录验证范围。
- 保留已有工作区改动，按任务范围提交；使用开发分支时，提交后合并 main，验证合并结果，再推送并等待该提交的远端 CI 成功。
- 检查失败时保留成果与日志，修复并复验成功后继续；遇到外部阻塞时说明具体原因与剩余步骤。
- 交付报告列明提交、推送、验证和发布的实际结果；收尾时清理已合并分支，保留工作树数据。

## 按需发包

- 交付安装包时，先确定版本并提交版本及发布说明，再对最终提交执行验证、合并和推送流程。
- 使用仓库发布工具和流水线，执行测试、lint、Release 构建、签名、包信息与摘要一致性校验。
- 发布标签、构建源码、验证证据必须对应同一提交；使用项目既有发行证书。
- 将 APK、update.json、SHA256SUMS.txt 上传至 GitHub Release，发布并设为 Latest，核对标签、附件及 Latest 指向。

## 版本递增

- version.properties 是唯一版本来源；versionName 使用 MAJOR.MINOR.PATCH，标签使用 vMAJOR.MINOR.PATCH。
- 修复、小幅 UI 调整、兼容性优化及内部重构交付：PATCH 加 1。
- 完整新功能或较大能力升级：MINOR 加 1，PATCH 归零。
- 稳定阶段验收通过后进入 1.0.0；1.0 后的不兼容变更：MAJOR 加 1，MINOR、PATCH 归零；0.x 阶段的不兼容变更提升 MINOR，并说明迁移影响。
- 每个新发行版本的 versionCode 取已发行最高值加 1，并核实高于目标升级安装的版本；versionName 必须高于已发布版本。
- 一个 Release 可包含多个提交，按影响最大的变更确定版本；纯文档、测试、开发工具及中间检查点保持发行版本，仅完成相应提交、合并和推送。
- 同一候选版本在首次公开或分发安装前，允许保留版本号重试；已公开或分发的版本保持原样，后续修复使用新版本。
