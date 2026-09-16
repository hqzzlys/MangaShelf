# 为 MangaShelf 贡献代码

感谢你帮助改进漫匣。提交改动前，请先搜索现有 Issue 和拉取请求，避免重复工作。较大的界面或数据格式变更建议先开 Issue 说明用途、兼容性和迁移方案。

## 本地准备

- JDK 17
- Android SDK 35
- Git

仓库包含经过校验的 Gradle Wrapper，不需要全局安装 Gradle。

```bash
git clone https://github.com/hqzzlys/MangaShelf.git
cd MangaShelf
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 请把最后一条命令中的 `./gradlew` 换成 `.\gradlew.bat`。

## 修改要求

- 每个改动保持单一目的，避免顺带格式化无关文件。
- 修复缺陷时补充能复现问题的测试；涉及 ZIP、文件替换或备份时覆盖失败和中断路径。
- 用户可见文案放入 Android 字符串资源；交互控件应提供明确语义和至少 48dp 的触摸区域。
- 不增加网络权限、遥测或第三方服务，除非变更先经过公开讨论。
- 不提交 APK、签名密钥、密码、私有漫画或本地配置。

## 拉取请求

拉取请求应说明：问题、解决方式、用户可见变化、数据兼容性和验证结果。提交前请确保单元测试、Lint、Debug 与 Release 构建均通过。CI 成功后再合并到 `main`。

发现安全问题时不要提交公开 Issue，请使用 [安全报告流程](SECURITY.md)。
