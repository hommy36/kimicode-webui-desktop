#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::env;
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{
    AppHandle, Manager, PhysicalPosition, PhysicalSize, Position, Rect, RunEvent, Size, Webview,
    WebviewBuilder, WebviewUrl, Window, WindowEvent,
};

const TOPBAR_HEIGHT: f64 = 40.0;
const CONTENT_LABEL: &str = "content";
/// Windows 下 Tauri v2 的应用内页面地址（自定义协议映射为 http://tauri.localhost）
const MAIN_HTML_URL: &str = "http://tauri.localhost/main.html";
const REGISTRY_LATEST_URL: &str = "https://registry.npmjs.org/@moonshot-ai/kimi-code/latest";
const INSTALL_PS_CMD: &str = "irm https://code.kimi.com/kimi-code/install.ps1 | iex";

/// 应用独占启动的 `kimi web` 子进程
struct ServerChild(Mutex<Option<Child>>);

#[derive(Serialize)]
struct VersionInfo {
    current: Option<String>,
    latest: Option<String>,
}

fn main() {
    tauri::Builder::default()
        .manage(ServerChild(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            get_version_info,
            do_update,
            install_kimi
        ])
        .setup(|app| {
            let window = app.get_window("main").expect("main window missing");
            let size = window.inner_size().unwrap_or(PhysicalSize::new(1280, 800));
            // 顶栏 = 窗口自带 webview（label "main"），内容区 = 子 webview
            window.add_child(
                WebviewBuilder::new(CONTENT_LABEL, WebviewUrl::App("main.html".into())),
                PhysicalPosition::new(0, 0),
                PhysicalSize::new(1, 1),
            )?;
            layout_webviews(&window, size.width, size.height);
            // 布局完成后再显示窗口，避免启动瞬间顶栏撑满全窗的闪烁
            window.show()?;
            let _ = window.set_focus();

            let app_handle = app.handle().clone();
            std::thread::spawn(move || {
                if resolve_kimi().is_none() {
                    if let Some(content) = app_handle.get_webview(CONTENT_LABEL) {
                        eval_with_retry(&content, "showInstall()");
                    }
                } else if let Err(e) = start_and_navigate(&app_handle) {
                    show_error_on(&app_handle, &e);
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::Resized(size) = event {
                layout_webviews(window, size.width, size.height);
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app_handle, event| {
            if matches!(event, RunEvent::ExitRequested { .. } | RunEvent::Exit) {
                kill_server(&app_handle);
            }
        });
}

// ---------- 布局 ----------

fn layout_webviews(window: &Window, w: u32, h: u32) {
    let scale = window.scale_factor().unwrap_or(1.0);
    let bar_h = ((TOPBAR_HEIGHT * scale).round() as u32).min(h);
    if let Some(bar) = window.get_webview("main") {
        let _ = bar.set_bounds(Rect {
            position: Position::Physical(PhysicalPosition::new(0, 0)),
            size: Size::Physical(PhysicalSize::new(w, bar_h)),
        });
    }
    if let Some(content) = window.get_webview(CONTENT_LABEL) {
        let _ = content.set_bounds(Rect {
            position: Position::Physical(PhysicalPosition::new(0, bar_h as i32)),
            size: Size::Physical(PhysicalSize::new(w, h - bar_h)),
        });
    }
}

// ---------- Tauri 命令 ----------

#[tauri::command]
fn get_version_info() -> VersionInfo {
    VersionInfo {
        current: current_version(),
        latest: latest_version(),
    }
}

#[tauri::command]
fn do_update(app: AppHandle) -> Result<String, String> {
    // 先回到本地占位页（更新期间 WebUI 会断开）
    if let Some(c) = app.get_webview(CONTENT_LABEL) {
        let _ = c.navigate(MAIN_HTML_URL.parse().unwrap());
    }
    std::thread::sleep(Duration::from_millis(800));
    show_status_on(&app, "正在更新 Kimi Code CLI（服务将短暂中断）…");
    kill_server(&app);

    let kimi = resolve_kimi().ok_or_else(|| "未检测到 Kimi Code CLI".to_string())?;
    let home = kimi_home()?;
    // 官方脚本安装（~/.kimi-code/bin 下）走安装脚本更新；npm 全局安装走 npm
    let native = kimi.starts_with(home.join("bin"))
        || (kimi == PathBuf::from("kimi") && home.join("bin").join("kimi.exe").exists());
    if native {
        run_powershell_install()?;
    } else {
        run_npm_update()?;
    }

    start_and_navigate(&app)?;
    current_version().ok_or_else(|| "更新完成，但读取新版本号失败".to_string())
}

#[tauri::command]
fn install_kimi(app: AppHandle) -> Result<(), String> {
    run_powershell_install()?;
    if resolve_kimi().is_none() {
        return Err("安装脚本已执行，但仍未找到 kimi，请重启应用或到官网手动安装".to_string());
    }
    start_and_navigate(&app)
}

// ---------- 启动 / 更新流程 ----------

/// 启动 `kimi web` 并把内容区导航到带 token 的 WebUI 地址
fn start_and_navigate(app: &AppHandle) -> Result<(), String> {
    show_status_on(app, "正在启动 Kimi Code 服务…");
    let home = kimi_home()?;
    let port = pick_free_port()?;
    let mut child = spawn_kimi_web(port)?;

    let token = read_token(&home, &mut child)?;
    show_status_on(app, "等待服务就绪…");
    wait_until_ready(port, &mut child)?;

    app.state::<ServerChild>().0.lock().unwrap().replace(child);
    let url = format!("http://127.0.0.1:{port}/#token={token}");
    let content = app
        .get_webview(CONTENT_LABEL)
        .ok_or_else(|| "content webview 不存在".to_string())?;
    content
        .navigate(url.parse().expect("invalid url"))
        .map_err(|e| format!("导航到 WebUI 失败: {e}"))
}

fn run_powershell_install() -> Result<(), String> {
    let mut cmd = Command::new("powershell");
    cmd.args(["-NoProfile", "-Command", INSTALL_PS_CMD]);
    run_long(&mut cmd, "官方安装脚本")
}

fn run_npm_update() -> Result<(), String> {
    let mut cmd = Command::new("npm.cmd");
    cmd.args(["install", "-g", "@moonshot-ai/kimi-code@latest"]);
    run_long(&mut cmd, "npm 更新")
}

fn run_long(cmd: &mut Command, what: &str) -> Result<(), String> {
    no_window(cmd);
    let out = cmd
        .output()
        .map_err(|e| format!("执行{what}失败: {e}"))?;
    if out.status.success() {
        return Ok(());
    }
    let stderr = String::from_utf8_lossy(&out.stderr);
    let tail: String = stderr.chars().take(300).collect();
    Err(format!("{what}执行失败（{}）: {tail}", out.status))
}

// ---------- kimi CLI 探测与版本 ----------

fn kimi_candidates() -> Vec<PathBuf> {
    let mut v = vec![PathBuf::from("kimi")];
    if let Some(up) = env::var_os("USERPROFILE").or_else(|| env::var_os("HOME")) {
        v.push(PathBuf::from(up).join(".kimi-code").join("bin").join("kimi.exe"));
    }
    v
}

/// 找到本机 kimi 可执行文件；KIMI_DESKTOP_SIMULATE_MISSING=1 时假装未安装（测试安装界面用）
fn resolve_kimi() -> Option<PathBuf> {
    if env::var_os("KIMI_DESKTOP_SIMULATE_MISSING").is_some() {
        return None;
    }
    for p in kimi_candidates() {
        let ok = if p == Path::new("kimi") {
            probe_version(&p).is_some()
        } else {
            p.exists()
        };
        if ok {
            return Some(p);
        }
    }
    None
}

/// 运行 `kimi --version` 并解析版本号；kimi 不存在或失败返回 None
fn probe_version(bin: &Path) -> Option<String> {
    let mut cmd = Command::new(bin);
    cmd.arg("--version").stdout(Stdio::piped()).stderr(Stdio::null());
    no_window(&mut cmd);
    let out = cmd.output().ok()?;
    if !out.status.success() {
        return None;
    }
    let s = String::from_utf8_lossy(&out.stdout);
    s.split_whitespace()
        .last()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn current_version() -> Option<String> {
    probe_version(&resolve_kimi()?)
}

/// 从 npm registry 查最新版本（用系统自带 curl.exe，不引入 HTTP 依赖）
fn latest_version() -> Option<String> {
    let mut cmd = Command::new("curl.exe");
    cmd.args(["-s", "--max-time", "15", REGISTRY_LATEST_URL])
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    no_window(&mut cmd);
    let out = cmd.output().ok()?;
    if !out.status.success() {
        return None;
    }
    let body = String::from_utf8_lossy(&out.stdout);
    let key = "\"version\":\"";
    let start = body.find(key)? + key.len();
    let end = body[start..].find('"')? + start;
    Some(body[start..end].to_string())
}

// ---------- kimi web 子进程 ----------

fn kimi_home() -> Result<PathBuf, String> {
    if let Some(dir) = env::var_os("KIMI_CODE_HOME") {
        return Ok(PathBuf::from(dir));
    }
    let home = env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .ok_or_else(|| "无法确定用户主目录（USERPROFILE/HOME 均未设置）".to_string())?;
    Ok(PathBuf::from(home).join(".kimi-code"))
}

/// 优先使用固定端口：WebUI 的引导完成标记等状态存在 localStorage，
/// 按来源（含端口）隔离，端口稳定标记才能跨启动保留。
/// 仅当固定端口被占用时才退化为随机空闲端口。
fn pick_free_port() -> Result<u16, String> {
    const PREFERRED_PORT: u16 = 58627; // kimi web 的默认端口
    if TcpListener::bind(("127.0.0.1", PREFERRED_PORT)).is_ok() {
        return Ok(PREFERRED_PORT);
    }
    TcpListener::bind("127.0.0.1:0")
        .and_then(|l| l.local_addr().map(|a| a.port()))
        .map_err(|e| format!("分配本地端口失败: {e}"))
}

fn spawn_kimi_web(port: u16) -> Result<Child, String> {
    let kimi = resolve_kimi()
        .ok_or_else(|| "未检测到 Kimi Code CLI（kimi）。请先安装：https://www.kimi.com/code/docs/en/".to_string())?;
    let mut cmd = Command::new(kimi);
    cmd.args(["web", "--no-open", "--port", &port.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    no_window(&mut cmd);
    cmd.spawn().map_err(|e| format!("启动 kimi web 失败: {e}"))
}

/// 轮询读取 <home>/server.token（首次运行时文件可能尚未生成）
fn read_token(home: &Path, child: &mut Child) -> Result<String, String> {
    let path = home.join("server.token");
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        match std::fs::read_to_string(&path) {
            Ok(s) if !s.trim().is_empty() => return Ok(s.trim().to_string()),
            _ => {
                if let Some(status) = child.try_wait().ok().flatten() {
                    return Err(format!("kimi web 进程异常退出（{status}），无法获取 token"));
                }
                if Instant::now() > deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!("等待 {} 生成超时", path.display()));
                }
                std::thread::sleep(Duration::from_millis(200));
            }
        }
    }
}

/// TCP 可连即视为服务就绪
fn wait_until_ready(port: u16, child: &mut Child) -> Result<(), String> {
    let deadline = Instant::now() + Duration::from_secs(15);
    let addr = format!("127.0.0.1:{port}");
    loop {
        if TcpStream::connect(&addr).is_ok() {
            return Ok(());
        }
        if let Some(status) = child.try_wait().ok().flatten() {
            return Err(format!("kimi web 进程异常退出（{status}），服务未就绪"));
        }
        if Instant::now() > deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("等待 kimi web 监听 {addr} 超时"));
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn kill_server(app_handle: &AppHandle) {
    if let Some(mut child) = app_handle.state::<ServerChild>().0.lock().unwrap().take() {
        let _ = child.kill();
        let _ = child.wait();
    }
}

// ---------- 工具 ----------

/// 避免 Windows 下弹出控制台黑窗
fn no_window(cmd: &mut Command) {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = cmd;
    }
}

fn show_status_on(app: &AppHandle, msg: &str) {
    if let Some(c) = app.get_webview(CONTENT_LABEL) {
        eval_with_retry(&c, &format!("showStatus({msg:?})"));
    }
}

fn show_error_on(app: &AppHandle, msg: &str) {
    if let Some(c) = app.get_webview(CONTENT_LABEL) {
        eval_with_retry(&c, &format!("showError({msg:?})"));
    }
}

/// 页面可能尚未加载完，重试几次保证 JS 注入成功
fn eval_with_retry(webview: &Webview, js: &str) {
    for _ in 0..10 {
        if webview.eval(js).is_ok() {
            return;
        }
        std::thread::sleep(Duration::from_millis(300));
    }
}
