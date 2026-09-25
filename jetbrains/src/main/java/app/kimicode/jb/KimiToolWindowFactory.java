package app.kimicode.jb;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 右侧工具窗口：JCEF 全幅内嵌 WebUI；动作收进标题栏图标（刷新 / 浏览器打开） */
public final class KimiToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public boolean isApplicable(@NotNull Project project) {
        return JBCefApp.isSupported();
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JBCefBrowser browser = new JBCefBrowser();
        KimiServerService server = KimiServerService.getInstance(project);
        KimiBrowserHolder.set(project, browser);
        ThemeSync.attach(browser, project);

        toolWindow.getContentManager().addContent(
                ContentFactory.getInstance().createContent(browser.getComponent(), "", false));

        String[] currentUrl = new String[1];

        Runnable start = new Runnable() {
            @Override
            public void run() {
                browser.loadHTML(statusPage("正在启动 Kimi Code 服务…"));
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        server.invalidate();
                        String url = server.ensureServer();
                        currentUrl[0] = url;
                        ApplicationManager.getApplication().invokeLater(() ->
                                loadWithRetry(browser, url));
                    } catch (Exception e) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                browser.loadHTML(statusPage("启动失败：" + e.getMessage())));
                    }
                });
            }
        };

        toolWindow.setTitleActions(List.of(
                new AnAction("刷新", "重新接入 / 拉起 kimi web 服务", AllIcons.Actions.Refresh) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        start.run();
                    }
                },
                new AnAction("浏览器打开", "在系统浏览器中打开当前会话", AllIcons.Nodes.PpWeb) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        if (currentUrl[0] != null) {
                            BrowserUtil.browse(currentUrl[0]);
                        }
                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        e.getPresentation().setEnabled(currentUrl[0] != null);
                    }
                }));

        start.run();
    }

    /**
     * 导航 + 看门狗：JCEF 浏览器组件刚创建时会吞掉 loadURL（首次打开卡在占位页的原因），
     * 发出导航后轮询实际地址，没生效就补发，最多 10 次。
     */
    private static void loadWithRetry(JBCefBrowser browser, String url) {
        String expect = url.contains("#") ? url.substring(0, url.indexOf('#')) : url;
        browser.loadURL(url);
        int[] left = {10};
        javax.swing.Timer timer = new javax.swing.Timer(1500, null);
        timer.addActionListener(e -> {
            String cur = browser.getCefBrowser().getURL();
            if (cur != null && cur.startsWith(expect)) {
                timer.stop();
                return;
            }
            if (--left[0] > 0) {
                browser.loadURL(url);
            } else {
                timer.stop();
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    /** 加载中的占位页 / 错误页，配色跟随 IDE 明暗 */
    private static String statusPage(String text) {
        boolean dark = !com.intellij.ui.JBColor.isBright();
        String bg = dark ? "#1e1f22" : "#ffffff";
        String fg = dark ? "#bcbec4" : "#3b3d41";
        return "<html><body style='margin:0;display:flex;height:100vh;align-items:center;"
                + "justify-content:center;background:" + bg + ";color:" + fg
                + ";font:14px sans-serif'>" + text + "</body></html>";
    }
}
