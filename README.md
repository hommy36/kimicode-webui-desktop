# Kimi Code WebUI Desktop

[English](README_EN.md)

非官方的 [Kimi Code](https://www.kimi.com/code) 桌面客户端 —— 用 Tauri 2 把 Kimi Code 的 WebUI 包装成原生桌面应用。

**[⬇️ 下载最新版本（Releases）](https://github.com/hommy36/kimicode-webui-desktop/releases)**

![截图](docs/screenshot.png)

![会话界面](docs/screenshot-session.png)

## 功能

- 内嵌 Kimi Code WebUI：启动时自动拉起本机 `kimi web` 服务并导航到带 token 的地址
- 自定义无边框顶栏：可拖动窗口，集成最小化 / 最大化 / 关闭按钮
- 顶栏实时显示 CLI 当前版本与 npm 最新版本，一键更新
- 未检测到 Kimi Code CLI 时，引导通过官方安装脚本自动安装
- 界面语言跟随系统语言（中文 / English，仅应用自身界面，WebUI 部分由其自身控制）

## 运行要求

- Windows
- [Kimi Code CLI](https://www.kimi.com/code)（未安装时应用内可一键安装）

## 开发

```bash
pnpm install
pnpm dev
```

## 构建安装包

```bash
pnpm build
```

产物在 `src-tauri/target/release/bundle/` 下。

## 实现说明

- 窗口内两个 webview：窗口自带 webview 渲染顶栏（`src/index.html`），子 webview 渲染内容区（`src/main.html` 占位页 → 服务就绪后导航到 WebUI）
- `kimi web` 优先绑定固定端口 58627：WebUI 的引导状态存在 localStorage，按来源（含端口）隔离，端口稳定才能跨启动保留
- 顶栏与占位页的文案按 `navigator.language` 在中 / 英之间切换；Rust 侧只发送 i18n key（错误为 `key|detail` 格式），由前端翻译
- 设置环境变量 `KIMI_DESKTOP_SIMULATE_MISSING=1` 可模拟未安装 CLI，用于测试安装引导界面

## 许可证

MIT
