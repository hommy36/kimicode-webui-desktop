# KimiCode Remote（安卓客户端）

原生 Java + WebView 实现的 Kimi Code WebUI 安卓客户端，无 Kotlin / NDK / 第三方 UI 框架。

## 功能

- 扫码（ZXing）或手动输入连接：支持桌面端直连地址（`http://IP:端口?token=...`）与官方 RC 链接（`code-rc.kimi.com`，内置弹窗 WebView 完成 OAuth 登录）
- 记住上次连接，启动自动加载；加载失败回到扫码页，双击返回才退出会话
- 全屏 edge-to-edge：状态栏 / 导航栏随页面背景亮暗自动切换图标颜色
- WebUI 主题跟随系统（DayNight 主题 + `configChanges=uiMode`，切主题不断会话）
- 自绘扫码界面（圆角扫描框 + 扫描线动画）、底部输入卡片、上传选择弹层（图片 / 文件分流到相册 / 文件管理器），深浅色双主题

## 构建

要求：JDK 17、Android SDK（platform android-34、build-tools 36.0.0）。

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装）。

## 目录

- `app/src/main/java/app/kimicode/remote/MainActivity.java` — 全部主逻辑（连接、扫码、上传、主题跟随、返回保护、RC 登录弹窗）
- `app/src/main/java/app/kimicode/remote/ScanOverlayView.java` — 扫码框自绘
- `app/src/main/res/values/` + `values-night/` — 深浅色双主题色板
