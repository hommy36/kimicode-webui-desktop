# KimiCode WebUI（JetBrains IDE 插件）

在 JetBrains IDE（CLion / PyCharm / IDEA 等，2025.2+）的工具窗口里内嵌 Kimi Code WebUI，基于 JCEF（JetBrains Runtime 自带 Chromium）。

## 功能

- 右侧「Kimi Code」工具窗口，完整 WebUI 体验
- **attach-or-spawn**：`kimi web` 已在运行（如桌面版 KimiCode WebUI Desktop）时直接接入，否则以当前项目根目录为 cwd 自动拉起
- 主题跟随 IDE 明暗设置（写入 WebUI 的 `kimi-web.color-scheme`）
- 工具栏：刷新（重新接入/拉起服务）、在系统浏览器打开
- 编辑器右键菜单：
  - **发送选中代码到 Kimi Code**（Ctrl+Alt+K）：选中代码连同文件路径、行号进入 WebUI 输入框，不自动发送，可继续补充说明
  - **向 Kimi Code 提问…**（Ctrl+Shift+Alt+K）：弹框输入问题，选中代码自动作为上下文
- 首次冷启动注入有重试兜底；快捷键与其他插件冲突时可在 IDE Keymap 设置中自行修改

## 安装

Settings → Plugins → ⚙ → Install Plugin from Disk，选择 `build/distributions/kimicode-webui-jb-x.y.z.zip`。

要求：本机已安装 [Kimi Code CLI](https://www.kimi.com/code)。

## 构建

复用仓库 android/ 下的 JDK 17 与 Gradle 8.9；IntelliJ Platform 依赖指向本机 CLion 安装目录（`build.gradle` 里的 `local(...)`，按需改成你的 IDE 路径）。

```bash
cd jetbrains
JAVA_HOME="$PWD/../android/.jdk" ../android/.gradle-dist/gradle-8.9/bin/gradle buildPlugin
```

产物：`build/distributions/kimicode-webui-jb-x.y.z.zip`。

## 已知边界

- 桌面版启动时会清理所有 `kimi web --no-open` 残留进程，可能误杀本插件拉起的服务；点工具栏「刷新」即可恢复
- 多个 IDE 项目共享固定端口 58627 的服务实例（cwd 以首个启动者为准），WebUI 内可切换目录
