package app.kimicode.jb;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** 右侧工具窗口：JCEF 内嵌 WebUI + 顶部工具栏（状态 / 刷新 / 浏览器打开） */
public final class KimiToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public boolean isApplicable(@NotNull Project project) {
        return JBCefApp.isSupported();
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JBCefBrowser browser = new JBCefBrowser();
        KimiServerService server = KimiServerService.getInstance(project);
        ThemeSync.attach(browser, project);

        JLabel status = new JLabel("正在启动 Kimi Code 服务…");
        JButton reload = new JButton("刷新");
        JButton openExternal = new JButton("浏览器打开");
        openExternal.setEnabled(false);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(status);
        bar.add(reload);
        bar.add(openExternal);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(bar, BorderLayout.NORTH);
        panel.add(browser.getComponent(), BorderLayout.CENTER);

        toolWindow.getContentManager().addContent(
                ContentFactory.getInstance().createContent(panel, "", false));

        // 监听器只挂一次，地址随启动更新，避免每次刷新叠加监听
        String[] currentUrl = new String[1];
        openExternal.addActionListener(e -> {
            if (currentUrl[0] != null) {
                BrowserUtil.browse(currentUrl[0]);
            }
        });

        Runnable start = () -> {
            status.setText("正在启动 Kimi Code 服务…");
            openExternal.setEnabled(false);
            browser.loadURL("about:blank");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    server.invalidate();
                    String url = server.ensureServer();
                    currentUrl[0] = url;
                    SwingUtilities.invokeLater(() -> {
                        status.setText("Kimi Code");
                        openExternal.setEnabled(true);
                        browser.loadURL(url);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() ->
                            status.setText("启动失败：" + e.getMessage()));
                }
            });
        };

        reload.addActionListener(e -> start.run());
        start.run();
    }
}
