# TVBox Android TV

面向 Android TV 8.0+ 的原生电视影视聚合客户端。项目使用 Java 和 ExoPlayer，针对电视遥控器焦点、弱网播放、线路测速、预缓存和自动换线进行了适配。

本项目基于 [TVBox Desktop](https://github.com/hgtuj5688/tvbox-deskTop) 的 MIT 许可代码和数据结构开发，将 Electron、React、ArtPlayer 及 Windows 接口替换为 Android 原生组件。

## 功能

- Android TV 8.0（API 26）及以上
- 多源首页、聚合搜索、详情、线路和选集
- 荐片 15 条线路解析与当前网络并发测速
- 首次播放预缓存 8 秒，换线预缓存 3 秒
- 512MB LRU 磁盘媒体缓存，最长向前缓冲 120 秒
- 连续缓冲超过 2 秒后自动换线，并保留集数和播放位置
- 播放失败后可跨影视源查找同名影片并继续播放
- 自动、720P、1080P、4K 轨道限制（需片源提供对应码率）
- 遥控器左右键快退/快进 15 秒
- 跳过片头和向前跳过广告
- 播放控制层 4 秒后自动隐藏，按遥控器方向键或确定键重新显示
- 1920×1080 电视界面与明确的焦点边框

## 下载

从 [Releases](https://github.com/kenliy/tvbox-android-tv/releases) 下载 APK，在 Android TV 上允许安装未知来源应用后安装。

当前包名：`com.tvbox.desktop.tv`

## 构建

要求：

- JDK 11
- Android SDK Platform 28
- Android Build Tools 28.0.3

在项目根目录创建本机 `local.properties`：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

构建 Debug APK：

```bash
./gradlew --no-daemon clean assembleDebug
```

构建 Release APK：

```bash
./gradlew --no-daemon clean assembleRelease
```

输出位于 `app/build/outputs/apk/`。Release 构建默认使用本机 Android Debug 签名，仅用于自行安装和测试；正式分发时应配置并妥善保管自己的发布签名。

## 播放与缓存说明

应用会在进入详情或点击选集时检测可用线路，并优先选择当前网络下响应更快的线路。播放器使用 ExoPlayer 磁盘缓存和预缓冲降低卡顿，播放期间若连续缓冲超过 2 秒，会尝试切换到同一集的其他线路。

本项目没有实现或伪装 P2P。真正的 P2P 播放需要公开的协议、tracker/信令服务及可连接节点；当前实现为 HTTP/HLS 缓存、预缓冲、测速与故障切换。

## 内容与隐私

本仓库不托管视频、字幕或海报资源。内容和播放地址由第三方接口返回，接口可用性、合法性和内容版权由相应服务提供者负责。使用者应遵守所在地区法律及内容服务条款。

应用不包含账号系统、广告 SDK 或遥测 SDK。播放缓存保存在应用私有目录，卸载应用或清除应用数据后会被删除。

## 贡献

提交问题时请附上电视型号、Android 版本、复现影片与集数、线路名称以及可公开的错误信息。代码贡献请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

项目采用 [MIT License](LICENSE)。第三方及上游项目声明见 [THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt)。
