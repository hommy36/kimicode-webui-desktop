package app.kimicode.jb.action;

import app.kimicode.jb.KimiInputBridge;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 编辑器右键：发送选中代码到 Kimi Code（进输入框，不自动发送） */
public final class SendSelectionToKimiAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean ok = e.getProject() != null && editor != null
                && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabledAndVisible(ok);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        String payload = buildPayload(project, editor, null);
        if (payload == null) {
            return;
        }
        activateAndInsert(project, payload);
    }

    /** 拼载荷：相对路径 + 行号 + 代码块；question 非空时放在最前 */
    @Nullable
    static String buildPayload(@NotNull Project project, @NotNull Editor editor,
                               @Nullable String question) {
        SelectionModel sel = editor.getSelectionModel();
        String code = sel.getSelectedText();
        VirtualFile file = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance().getFile(editor.getDocument());

        StringBuilder b = new StringBuilder();
        if (question != null && !question.isBlank()) {
            b.append(question.trim()).append("\n\n");
        }
        if (code != null && !code.isBlank()) {
            if (file != null) {
                String path = com.intellij.openapi.vfs.VfsUtilCore
                        .getRelativePath(file, project.getBaseDir());
                if (path == null) {
                    path = file.getPath();
                }
                int startLine = editor.getDocument().getLineNumber(sel.getSelectionStart()) + 1;
                int endLine = editor.getDocument().getLineNumber(sel.getSelectionEnd()) + 1;
                b.append(path).append("（L").append(startLine);
                if (endLine > startLine) {
                    b.append("-").append(endLine);
                }
                b.append("）：\n");
            }
            b.append("```\n").append(code).append("\n```\n");
        }
        return b.length() == 0 ? null : b.toString();
    }

    static void activateAndInsert(@NotNull Project project, @NotNull String payload) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Kimi Code");
        if (tw != null) {
            tw.activate(null);
        }
        KimiInputBridge.insert(project, payload);
    }
}
