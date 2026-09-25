package app.kimicode.jb.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/** 编辑器右键：向 Kimi Code 提问（选中代码自动作为上下文） */
public final class AskKimiAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        boolean hasCode = editor.getSelectionModel().hasSelection();
        String question = Messages.showInputDialog(project,
                hasCode ? "输入问题（选中代码将作为上下文一并发送）：" : "输入问题：",
                "向 Kimi Code 提问", null);
        if (question == null || question.isBlank()) {
            return;
        }
        String payload = SendSelectionToKimiAction.buildPayload(project, editor, question);
        if (payload != null) {
            SendSelectionToKimiAction.activateAndInsert(project, payload);
        }
    }
}
