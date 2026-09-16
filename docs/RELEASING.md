# 发布 MangaShelf

正式版本由 `.github/workflows/release.yml` 构建，不在开发者电脑上手工上传 APK。

## 一次性配置

在 GitHub 仓库的 Actions Secrets 中配置：

- `MANGASHELF_KEYSTORE_BASE64`：项目专用 JKS 文件的 Base64 内容
- `MANGASHELF_STORE_PASSWORD`：密钥库密码
- `MANGASHELF_KEY_ALIAS`：密钥别名
- `MANGASHELF_KEY_PASSWORD`：密钥密码

密钥和密码不得写入仓库、Issue、Actions 变量或工作流文件。至少在两个安全位置离线保存密钥库与恢复说明；丢失签名密钥后，已有用户无法覆盖升级。

## 每次发布

1. 更新 `versionCode`、`versionName`、README 当前版本和 CHANGELOG。
2. 从干净检出运行 `testDebugUnitTest`、`lintDebug`、`assembleDebug` 和 `assembleRelease`。
3. 合并通过 CI 的发布拉取请求。
4. 在 `main` 的目标提交上创建带签名的注释标签，例如 `git tag -s v1.2.0 -m "Release v1.2.0"`。
5. 推送标签。标签工作流会生成签名 APK、SHA-256、构建来源证明和 GitHub Release。
6. 下载 Release APK，核对包名、版本、签名证书和 SHA-256，并在至少一台真机上执行覆盖升级测试。

不要在同一个版本号下替换 APK。若发布有误，请增加版本号并发布修正版。
