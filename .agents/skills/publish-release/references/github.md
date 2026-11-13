# GitHub 发布细节

仅在目标平台为 GitHub 时读取。优先复用仓库已有发布流水线；以下为需要手动编排时的检查点，具体参数先核对本机工具帮助。

## 确认身份与构建

- 从 Git remote 确认主机及 `OWNER/REPO`，GitHub Enterprise 使用对应主机，不能硬编码 github.com。
- 复用现有连接器或 `gh` 登录状态。运行 `gh auth status` 时不要输出原始令牌，不读取私钥内容。
- 使用 `gh run list --commit SHA` 或等价 API 找到相关运行，核对 `headSha`、工作流及检查结论；不能仅凭最近一次绿灯。等待查询断线后重新读取运行状态，不把监控进程退出当成 CI 失败，也不无故重跑构建。
- 标签可能为轻量或附注标签；比较其解析后的提交，而非把附注标签对象 SHA 当源码 SHA。

## 草稿与附件

使用 gh 手动发布时，可按下列顺序操作。大写参数代表从项目发现的值，须按实际 shell 正确引用；每条写操作后检查结果，先完成校验再执行下一条。

```text
gh release create TAG --repo OWNER/REPO --target SHA --verify-tag --draft --notes-file NOTES_FILE
gh release upload TAG FILE... --repo OWNER/REPO
```

`--verify-tag` 要求远端标签已经存在。标签触发构建的项目仍沿用其流程；在公开 Release 前确认标签和构建来源一致。

- 创建成功后，从 Release 列表或返回对象中保存数字 ID。草稿 URL 可能含 `untagged-*`，按标签查询也可能返回 404；这两者不能证明创建失败。用已知 ID 读取 `repos/OWNER/REPO/releases/ID`，或从列表定位同标签、同目标源码的草稿，不重复创建。
- 恢复操作可按稳定 ID 读取 `repos/OWNER/REPO/releases/ID/assets`，保存各附件 ID，并使用附件 ID 的已认证下载入口核对内容；不要反复依赖失败的标签查询。保持二进制下载完整，不用可能改变编码的文本管道转存文件。
- 附件列表核对 `name`、`size`、`state` 及 `digest`。仅 `uploaded` 算上传完成；远端 SHA-256 应等于本地最终文件的摘要。`digest` 缺失时下载该附件计算 SHA-256，不能只核对大小。
- 大文件上传可能没有进度回显。持续观察当前上传及远端状态；未确认结果前不启动重复上传。上传超时后先读回，已完成且匹配的附件保留；未完成或不匹配的草稿附件仅在确认其身份后按授权修复，不覆盖已发行内容。
- 附件清单由项目决定，无附件发布跳过此步骤。发布说明通过文件或结构化参数传递，避免 shell 转义破坏换行或插值。

## 公开与确认

全部必需检查通过后公开原草稿：

```text
gh release edit TAG --repo OWNER/REPO --draft=false
```

若标签解析失败但已确认草稿 ID，可直接使用稳定 ID：

```text
gh api --method PATCH repos/OWNER/REPO/releases/ID -F draft=false
```

Enterprise API 操作显式传入对应的 `--hostname HOST`，CLI 仓库参数使用对应主机的仓库标识。

需要 Latest 的项目才在 CLI 附加 `--latest`，或使用等价 API 参数。设置前检查当前指针，若已被并发新版本推进，先比较项目的版本与渠道规则，不直接抢回。预发布版遵守其渠道规则。

公开后核对稳定 ID、`draft=false`、`prerelease`、标签及附件摘要；需要 Latest 时另查 `repos/OWNER/REPO/releases/latest`。涉及更新元数据或校验文件时，可下载小文件与本地内容比较，确保消费者读到同一发行包。公开请求超时同样先读回原 ID，不能据此重建 Release。

私有仓库附件使用已认证的下载入口；不要将凭据写入可分享链接或发布说明。完成报告给出用户可使用的 Release、CI 和产物链接。
