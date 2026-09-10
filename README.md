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
- 远程控制：顶栏「远程」页一个开关 + 通道下拉，两种通道互斥（kimi 要求 `--remote-control` 绑定 loopback，与直连的 `--host 0.0.0.0` 冲突，同时只能开一个）：
  - **直连（局域网 / Tailscale）**：顶栏「远程」页一键开启后，手机扫码即可在同一 WiFi 下访问 WebUI（基于 `kimi web` 自带的 `--host 0.0.0.0` 与 token 鉴权，开关状态持久化；连不上时可一键添加防火墙规则）。免费、无需账号，适合没开 Kimi 会员或接其他模型源的用户
  - **官方 Remote Control**（实验，需 kimi ≥ v0.39.0）：经 code-rc.kimi.com 云端中继，自带手机界面，跨网络可用；需 Kimi 账号登录。开启即以 `kimi web --remote-control` 启动服务，扫码/点链接即可使用
- 应用界面（顶栏 / 占位页 / 远程页）主题自动跟随 WebUI 的亮暗设置
- 界面语言跟随系统语言（中文 / English，仅应用自身界面，WebUI 部分由其自身控制）

## 跨网络远程访问（Tailscale）

校园网 / 企业网通常开启客户端隔离，同一网络下设备互不可达。要脱离局域网远程控制（4G、外地）：

1. 电脑和手机上安装 [Tailscale](https://tailscale.com)（免费）
2. 两端登录同一账号
3. 应用内开启远程，IP 下拉选择 `100.x` 开头的地址，手机扫码即可——任何网络都能连

安全模型：WireGuard 端到端加密 + Tailscale 账号准入 + 链接内 token，三重保护。

不想安装任何东西时，也可用 `cloudflared tunnel --url http://localhost:58627` 临时生成公网链接（自行承担公网暴露风险）。

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
