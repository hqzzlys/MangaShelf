# 安全政策

## 支持范围

安全修复优先提供给最新 GitHub Release。旧版本可能不再单独修补，建议先复现并确认问题在最新版本中仍存在。

## 私下报告漏洞

请使用 GitHub 的[私有漏洞报告](https://github.com/hqzzlys/MangaShelf/security/advisories/new)。不要在公开 Issue、讨论区或拉取请求中发布漏洞细节、恶意压缩包、签名材料或用户数据。

报告中请包含：受影响版本、设备与 Android 版本、最小复现步骤、实际影响，以及不含私人内容的测试文件或日志。维护者确认问题后会协调修复和披露时间。

## 发布与签名

正式 APK 只通过仓库的标签发布工作流生成。工作流从 GitHub Actions Secrets 读取专用签名，执行测试与 Lint，发布 APK、SHA-256 文件和 Artifact Attestation。签名密钥、密码及恢复包不得提交到仓库或附加到 Issue。
