# AdGuardHome WebView

[AdGuardHome for Root](https://github.com/twoone-3/AdGuardHomeForRoot) 的配套管理面板。一个只做一件事的 Android WebView 外壳：打开手机本机的 AdGuard Home 后台。

> 由 AI 辅助开发，GitHub Actions 自动构建。

## 功能

- 默认打开 `http://127.0.0.1:3000`
- 地址可修改，并可自动探测 Magisk 模块里配置的监听地址
- 返回手势跟随网页历史，退无可退时才退出应用
- 连接失败时显示可重试的错误页，而不是 Chromium 的白屏
- 支持文件上传（过滤器导入）与下载（配置、日志导出，自动携带登录 Cookie）
- 站外链接交给系统浏览器，`intent://` 链接交给对应应用
- 深色模式交给网页自身的 `color-scheme`，不做强制反色
- WebView 渲染进程被系统回收后自动重建，不会连带崩溃
- 自适应图标、edge-to-edge 布局

## 构建

需要 JDK 17+ 和 Android SDK 37。

```bash
./gradlew assembleDebug    # 调试包
./gradlew assembleRelease  # 正式包（R8 压缩 + 正式签名）
```

产物位于 `app/build/outputs/apk/`。本地构建前在 `local.properties` 里填好 `sdk.dir`。

正式签名需要在项目根目录放一份 `keystore.properties`（已被忽略，不会提交）：

```properties
storeFile=C:/path/to/lisrain.jks
storePassword=******
keyAlias=lisrain
keyPassword=******
```

缺少该文件时，release 会退回 debug 签名，保证产物仍可安装。

## 发布

推送 `v*` 标签即可自动构建并创建 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI 从仓库 Secrets 还原正式签名（`KEYSTORE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），`versionName` 取自标签名，`versionCode` 取自 Actions 运行序号。

## 地址配置

连不上时，错误页提供两个入口：

- **更改地址**：手动填写，例如 `http://127.0.0.1:3000`
- **自动检测**：通过 `su` 读取 `/data/adb/agh/bin/AdGuardHome.yaml` 中的 `http.address`，需要授予 root 权限

## 版本

| 项目 | 值 |
|------|-----|
| applicationId | `org.adguardhome` |
| minSdk | 23（Android 6.0） |
| targetSdk / compileSdk | 37（Android 17） |
| Gradle | 9.8 |
| AGP | 9.5.1 |

## 许可证

仅供学习与个人使用。
