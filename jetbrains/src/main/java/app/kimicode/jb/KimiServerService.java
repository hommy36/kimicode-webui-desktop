package app.kimicode.jb;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * kimi web 服务生命周期：attach-or-spawn。
 * 58627 已在监听且 token 文件存在时直接接入现有服务（如桌面版正在跑的），
 * 否则自行拉起；固定端口保证 WebUI 的 localStorage 状态跨启动保留。
 */
@Service(Service.Level.PROJECT)
public final class KimiServerService implements Disposable {

    private static final Logger LOG = Logger.getInstance(KimiServerService.class);
    private static final int PREFERRED_PORT = 58627;

    private final Project project;
    /** 当前可用地址（含 token 片段），null 表示尚未就绪 */
    private volatile String url;
    /** 本插件拉起的子进程；attach 到别人服务时为 null（不归我们管） */
    private Process child;

    public KimiServerService(Project project) {
        this.project = project;
    }

    public static KimiServerService getInstance(Project project) {
        return project.getService(KimiServerService.class);
    }

    /** 返回可用 WebUI 地址；失败抛异常（消息可直接展示给用户）。后台线程调用。 */
    public synchronized String ensureServer() throws IOException, InterruptedException {
        if (url != null && isAlive(child)) {
            return url;
        }
        url = null;

        int port = PREFERRED_PORT;
        String token = readToken(0);
        if (token != null && tcpReady(port)) {
            LOG.info("attach to existing kimi web on " + port);
            url = buildUrl(port, token);
            return url;
        }

        Path kimi = resolveKimi();
        if (kimi == null) {
            throw new IOException("未检测到 Kimi Code CLI，请先安装：https://www.kimi.com/code");
        }

        port = pickFreePort();
        LOG.info("spawn kimi web on port " + port);
        ProcessBuilder pb = new ProcessBuilder(
                kimi.toString(), "web", "--no-open", "--port", String.valueOf(port));
        String base = project.getBasePath();
        if (base != null) {
            pb.directory(Paths.get(base).toFile());
        }
        pb.redirectInput(ProcessBuilder.Redirect.from(new FileNull()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        child = pb.start();

        token = readToken(10_000);
        if (token == null) {
            destroyChild();
            throw new IOException("kimi web 启动超时（未生成 server.token）");
        }
        if (!waitTcp(port, 15_000)) {
            destroyChild();
            throw new IOException("kimi web 启动超时（端口 " + port + " 无响应）");
        }
        url = buildUrl(port, token);
        return url;
    }

    /** 丢弃当前连接状态，下次 ensureServer 重新 attach-or-spawn（刷新按钮用） */
    public synchronized void invalidate() {
        url = null;
        if (child != null && !child.isAlive()) {
            child = null;
        }
    }

    private static String buildUrl(int port, String token) {
        return "http://127.0.0.1:" + port + "/#token=" + token;
    }

    // ---------- kimi CLI 探测 ----------

    private static Path resolveKimi() {
        // PATH 里的 kimi（先跑 --version 验证可用）
        try {
            Process p = new ProcessBuilder("kimi", "--version")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                return Paths.get("kimi");
            }
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
        // 官方脚本安装位置
        String home = System.getenv("USERPROFILE") != null
                ? System.getenv("USERPROFILE") : System.getProperty("user.home");
        Path exe = Paths.get(home, ".kimi-code", "bin", "kimi.exe");
        return Files.exists(exe) ? exe : null;
    }

    private static Path kimiHome() {
        String custom = System.getenv("KIMI_CODE_HOME");
        if (custom != null && !custom.isEmpty()) {
            return Paths.get(custom);
        }
        String home = System.getenv("USERPROFILE") != null
                ? System.getenv("USERPROFILE") : System.getProperty("user.home");
        return Paths.get(home, ".kimi-code");
    }

    // ---------- 端口与 token ----------

    /** 优先固定端口；重启后旧监听可能未释放（TIME_WAIT），短暂重试后退化随机端口 */
    private static int pickFreePort() throws IOException {
        for (int i = 0; i < 20; i++) {
            try (ServerSocket s = new ServerSocket(PREFERRED_PORT, 50,
                    InetAddress.getByName("127.0.0.1"))) {
                return PREFERRED_PORT;
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** 轮询读 server.token；timeoutMs=0 时只读一次（attach 探测用） */
    private static String readToken(long timeoutMs) throws IOException, InterruptedException {
        Path path = kimiHome().resolve("server.token");
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            try {
                String s = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static boolean tcpReady(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean waitTcp(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tcpReady(port)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static boolean isAlive(Process p) {
        return p == null || p.isAlive(); // attach 模式（p==null）视为可用，由 TCP 探测兜底
    }

    private void destroyChild() {
        if (child != null) {
            child.destroyForcibly();
            child = null;
        }
        url = null;
    }

    @Override
    public void dispose() {
        destroyChild();
    }

    /** 进程 stdin 接空设备，Windows 下为 NUL */
    private static final class FileNull extends java.io.File {
        FileNull() {
            super(System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "NUL" : "/dev/null");
        }
    }
}
