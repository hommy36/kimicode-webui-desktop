package app.kimicode.jb;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.Nullable;

/** 工具窗口的 JCEF 浏览器实例按项目共享，供编辑器 action 注入文本 */
public final class KimiBrowserHolder {

    private static final Key<JBCefBrowser> KEY = Key.create("kimicode.jb.browser");

    private KimiBrowserHolder() {
    }

    public static void set(Project project, JBCefBrowser browser) {
        project.putUserData(KEY, browser);
    }

    @Nullable
    public static JBCefBrowser get(Project project) {
        return project.getUserData(KEY);
    }
}
