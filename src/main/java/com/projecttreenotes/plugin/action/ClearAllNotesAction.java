package com.projecttreenotes.plugin.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.projecttreenotes.core.ClearAllNotes;
import com.projecttreenotes.core.NoteEntry;
import com.projecttreenotes.core.TsvCodec;
import com.projecttreenotes.plugin.ProjectNotesService;

import java.util.List;

public final class ClearAllNotesAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        ProjectNotesService service = project.getService(ProjectNotesService.class);
        List<NoteEntry> entries = service.readEntries();
        long count = ClearAllNotes.nonBlankCount(entries);
        if (count == 0) {
            service.notify(
                    "没有可清除的中文说明",
                    "当前项目的中文说明已经全部为空。",
                    NotificationType.INFORMATION
            );
            return;
        }

        int answer = Messages.showOkCancelDialog(
                project,
                "将清除当前项目中的 " + count + " 条中文说明。\n\n"
                        + "项目文件、目录和说明表中的路径不会删除。",
                "清除所有中文说明",
                "全部清除",
                "取消",
                Messages.getWarningIcon()
        );
        if (answer != Messages.OK) {
            return;
        }

        VirtualFile storageFile = service.ensureStorageFile();
        if (storageFile == null) {
            return;
        }
        Document document = FileDocumentManager.getInstance().getDocument(storageFile);
        if (document == null) {
            service.notify(
                    "清除中文说明失败",
                    "无法打开项目中文说明表。",
                    NotificationType.ERROR
            );
            return;
        }

        String clearedText = TsvCodec.render(ClearAllNotes.apply(entries));
        WriteCommandAction.runWriteCommandAction(
                project,
                "清除所有项目中文说明",
                null,
                () -> document.setText(clearedText),
                new PsiFile[0]
        );
        FileDocumentManager.getInstance().saveDocument(document);

        // Reload the in-memory cache immediately and keep the existing structure sync behavior.
        service.start();
        service.notify(
                "已清除所有中文说明",
                "已清除 " + count + " 条说明；项目文件、目录和路径记录均已保留。",
                NotificationType.INFORMATION
        );
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
