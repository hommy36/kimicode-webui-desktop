package app.kimicode.jb;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

/**
 * 向 WebUI 的 ProseMirror 输入框注入文本。
 * 页面/服务可能尚未就绪：同一段幂等 JS 每 500ms 重发、最多 20 次，
 * JS 里以 token 去重，重复执行只插入一次；页面重载后标记消失自然补插。
 */
public final class KimiInputBridge {

    private KimiInputBridge() {
    }

    public static void insert(Project project, String text) {
        String token = Integer.toHexString(text.hashCode()) + Long.toHexString(System.nanoTime());
        String js = buildJs(token, text);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (int i = 0; i < 20; i++) {
                JBCefBrowser browser = KimiBrowserHolder.get(project);
                if (browser != null && !browser.getCefBrowser().getURL().startsWith("about:")) {
                    browser.getCefBrowser().executeJavaScript(
                            js, browser.getCefBrowser().getURL(), 0);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private static String buildJs(String token, String text) {
        return "(function(){"
                + "var t='" + token + "';if(window.__kimiLastInsert===t)return;"
                + "var el=document.querySelector('.ProseMirror[contenteditable=true]');"
                + "if(!el)return;"
                + "window.__kimiLastInsert=t;"
                + "var r=document.createRange();r.selectNodeContents(el);r.collapse(false);"
                + "var s=getSelection();s.removeAllRanges();s.addRange(r);el.focus();"
                + "document.execCommand('insertText',false," + toJsString(text) + ");"
                + "})()";
    }

    /** 转成 JS 字符串字面量（双引号包裹） */
    private static String toJsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
