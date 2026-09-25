package app.kimicode.jb;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;

/**
 * IDE 明暗主题 → WebUI：WebUI 启动时从 localStorage 的 kimi-web.color-scheme
 * 恢复主题（light/dark/system）。加载完成后写入当前 IDE 主题，变化时 reload 生效。
 */
public final class ThemeSync {

    private static final String KEY = "kimi-web.color-scheme";

    private ThemeSync() {
    }

    public static void attach(JBCefBrowser browser, Project project) {
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cef, org.cef.browser.CefFrame frame,
                                  int httpStatusCode) {
                apply(cef);
            }
        }, browser.getCefBrowser());

        project.getMessageBus().connect().subscribe(
                LafManagerListener.TOPIC, (LafManagerListener) laf -> apply(browser.getCefBrowser()));
    }

    private static void apply(CefBrowser cef) {
        if (cef == null || cef.getURL() == null || cef.getURL().startsWith("about:")) {
            return;
        }
        String desired = JBColor.isBright() ? "light" : "dark";
        String js = "try{var k='" + KEY + "';"
                + "if(localStorage.getItem(k)!=='" + desired + "'){"
                + "localStorage.setItem(k,'" + desired + "');location.reload()}"
                + "}catch(e){}";
        cef.executeJavaScript(js, cef.getURL(), 0);
    }
}
