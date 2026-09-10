fn main() {
    // 声明 app 命令以生成 allow-*/deny-* 权限标识。
    // 远程来源（WebUI 页面 http://127.0.0.1:port）调用 app 命令必须有 capability 显式授权，
    // 仅靠 remote.urls 匹配不够（tauri::webview 对非本地来源强制检查 ACL）。
    // 注意：一旦定义了 app ACL，本地页面调用这些命令同样需要下方 capability 里的 allow-* 权限。
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(
            tauri_build::AppManifest::new().commands(&[
                "get_version_info",
                "do_update",
                "install_kimi",
                "get_remote_state",
                "set_remote_channel",
                "show_remote_page",
                "show_webui",
                "report_theme",
                "allow_firewall",
            ]),
        ),
    )
    .expect("error while building tauri application");
}
