# 贡献指南

感谢参与 TVBox Android TV。请保持改动集中、可验证，并避免提交与问题无关的格式化或重构。

## 开发流程

1. Fork 仓库并从 `main` 创建分支。
2. 使用 JDK 11、Android SDK 28 和 Build Tools 28.0.3 构建。
3. 运行 `./gradlew --no-daemon clean assembleDebug`。
4. 涉及播放或遥控器交互的改动，请在 Android TV 真机或可靠模拟器上验证。
5. Pull Request 中说明触发条件、修改后的行为和验证结果。

## 问题报告

请提供：

- 电视型号和 Android 版本
- 应用版本
- 影片、集数和线路
- 复现步骤
- 可公开的日志或错误提示

不要提交账号、Cookie、访问令牌、私有接口密钥或受版权保护的媒体文件。
