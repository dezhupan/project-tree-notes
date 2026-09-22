package com.projecttreenotes.plugin.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.projecttreenotes.core.AiPromptBatch;
import com.projecttreenotes.core.AiPromptBuilder;
import com.projecttreenotes.core.AiParseResult;
import com.projecttreenotes.core.AiResponseParser;
import com.projecttreenotes.core.NoteEntry;
import com.projecttreenotes.core.RejectedAiNote;
import com.projecttreenotes.core.ScannedItem;
import com.projecttreenotes.plugin.ProjectNotesService;
import com.projecttreenotes.plugin.ScanResult;
import com.projecttreenotes.plugin.ai.AiClient;
import com.projecttreenotes.plugin.settings.ApiKeyStore;
import com.projecttreenotes.plugin.settings.ProjectNotesConfigurable;
import com.projecttreenotes.plugin.settings.ProjectNotesSettings;
import com.projecttreenotes.plugin.ui.AiPreviewDialog;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AiGenerateNotesAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile selected = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || selected == null) return;
        ProjectNotesService service = project.getService(ProjectNotesService.class);
        if (!service.canAnnotate(selected)) return;

        ProjectNotesSettings.StateData settings = ProjectNotesSettings.getInstance().data();
        if (settings.baseUrl == null || settings.baseUrl.isBlank() || settings.model == null || settings.model.isBlank()) {
            int open = Messages.showOkCancelDialog(project,
                    "AI 服务尚未配置。是否现在打开“项目中文说明”设置？",
                    "需要配置 AI 服务", "打开设置", "取消", Messages.getInformationIcon());
            if (open == Messages.OK) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectNotesConfigurable.class);
            }
            return;
        }

        VirtualFile scope = chooseScope(project, service, selected);
        if (scope == null) return;

        String range = service.typeOf(scope) == com.projecttreenotes.core.NoteType.PROJECT
                ? "整个项目" : service.relativePath(scope);
        int confirmed = Messages.showOkCancelDialog(project,
                "将递归扫描：" + range + "\n\n"
                        + "会发送目录结构和安全筛选、脱敏后的源码片段到：\n" + settings.baseUrl + "\n\n"
                        + "凭据、私钥、二进制、虚拟环境和构建缓存不会发送；生成结果会先预览，确认后才写入说明表。",
                "AI 扫描并生成中文说明", "开始扫描", "取消", Messages.getWarningIcon());
        if (confirmed != Messages.OK) return;
        if (!service.beginAi()) {
            service.notify("AI 任务正在运行", "请等待当前生成任务结束。", NotificationType.WARNING);
            return;
        }

        SettingsSnapshot snapshot = new SettingsSnapshot(settings);
        Map<String, NoteEntry> existing = service.entriesByPath();
        new Task.Backgroundable(project, "AI 生成项目中文说明", true) {
            private List<NoteEntry> generated = List.of();
            private int requestedCount;
            private int skippedCount;
            private List<String> skippedReasons = List.of();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("正在扫描项目结构和安全源码片段…");
                ScanResult scan = service.scanner().scan(scope, snapshot.maxCharsPerFile,
                        snapshot.maxTotalContentChars, indicator);
                List<ScannedItem> pending = scan.items().stream()
                        .filter(item -> snapshot.overwriteExisting
                                || existing.get(item.path()) == null
                        || existing.get(item.path()).comment().isBlank())
                        .toList();
                requestedCount = pending.size();
                if (pending.isEmpty()) return;

                List<AiPromptBatch> batches = AiPromptBuilder.batches(pending,
                        snapshot.maxItemsPerRequest, snapshot.maxCharsPerRequest);
                AiClient client = new AiClient();
                String key = ApiKeyStore.get();
                List<NoteEntry> collected = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                for (int index = 0; index < batches.size(); index++) {
                    indicator.checkCanceled();
                    indicator.setText("正在请求 AI（" + (index + 1) + "/" + batches.size() + "）…");
                    indicator.setFraction((double) index / Math.max(1, batches.size()));
                    AiPromptBatch batch = batches.get(index);
                    try {
                        String response = client.request(snapshot.baseUrl, key, snapshot.model,
                                snapshot.timeoutSeconds, batch.prompt());
                        AiParseResult parsed = AiResponseParser.parseDetailed(response, batch.items());
                        collected.addAll(parsed.accepted());
                        for (RejectedAiNote rejected : parsed.rejected()) {
                            indicator.checkCanceled();
                            indicator.setText("正在修正不合格标签：" + rejected.item().path());
                            retryRejected(client, key, rejected, collected, failures);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("AI 请求已取消", interrupted);
                    } catch (ProcessCanceledException canceled) {
                        throw canceled;
                    } catch (Exception error) {
                        for (ScannedItem item : batch.items()) {
                            failures.add(item.path() + "：第 " + (index + 1) + " 批失败（"
                                    + compactReason(error) + "）");
                        }
                    }
                }
                indicator.setFraction(1.0);
                generated = List.copyOf(collected);
                skippedCount = failures.size();
                skippedReasons = List.copyOf(failures);
            }

            private void retryRejected(AiClient client,
                                       String key,
                                       RejectedAiNote rejected,
                                       List<NoteEntry> collected,
                                       List<String> failures) throws InterruptedException {
                try {
                    String retried = client.request(snapshot.baseUrl, key, snapshot.model,
                            snapshot.timeoutSeconds, AiPromptBuilder.retryPrompt(rejected));
                    AiParseResult retryResult = AiResponseParser.parseDetailed(
                            retried, List.of(rejected.item()));
                    if (!retryResult.accepted().isEmpty()) {
                        collected.add(retryResult.accepted().getFirst());
                    } else {
                        String reason = retryResult.rejected().isEmpty()
                                ? "重试后仍无可用标签" : retryResult.rejected().getFirst().reason();
                        failures.add(rejected.item().path() + "：" + reason);
                    }
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (ProcessCanceledException canceled) {
                    throw canceled;
                } catch (Exception error) {
                    failures.add(rejected.item().path() + "：重试失败（" + compactReason(error) + "）");
                }
            }

            @Override
            public void onSuccess() {
                service.endAi();
                if (generated.isEmpty()) {
                    if (requestedCount == 0) {
                        service.notify("没有需要生成的条目",
                                snapshot.overwriteExisting ? "扫描范围为空。" : "所选范围已经全部有中文说明。",
                                NotificationType.INFORMATION);
                    } else {
                        service.notify("AI 未生成可用标签",
                                "请求 " + requestedCount + " 条，成功 0 条，跳过 " + skippedCount + " 条。"
                                        + reasonSummary(skippedReasons), NotificationType.WARNING);
                    }
                    return;
                }
                AiPreviewDialog dialog = new AiPreviewDialog(project, generated, existing,
                        requestedCount, skippedCount, reasonSummary(skippedReasons));
                if (dialog.showAndGet()) {
                    List<NoteEntry> selected = dialog.selectedEntries();
                    if (!selected.isEmpty()) service.applyGenerated(selected);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                service.endAi();
                service.notify("AI 生成失败", rootMessage(error), NotificationType.ERROR);
            }

            @Override
            public void onCancel() {
                service.endAi();
                service.notify("AI 任务已取消", "未写入任何 AI 说明。", NotificationType.INFORMATION);
            }
        }.queue();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = project != null && file != null
                && project.getService(ProjectNotesService.class).canAnnotate(file);
        event.getPresentation().setEnabledAndVisible(enabled);
        if (enabled) {
            ProjectNotesService service = project.getService(ProjectNotesService.class);
            if (service.typeOf(file) == com.projecttreenotes.core.NoteType.PROJECT) {
                event.getPresentation().setText("AI 扫描整个项目并生成中文短标签…");
            } else {
                event.getPresentation().setText("AI 生成中文短标签（选择范围）…");
            }
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String compactReason(Throwable error) {
        String message = AiClient.readableFailure(error).replaceAll("\\s+", " ").trim();
        return message.length() <= 80 ? message : message.substring(0, 80) + "…";
    }

    private static String reasonSummary(List<String> reasons) {
        if (reasons.isEmpty()) return "";
        int shown = Math.min(3, reasons.size());
        String summary = String.join("；", reasons.subList(0, shown));
        if (reasons.size() > shown) summary += "；另有 " + (reasons.size() - shown) + " 条";
        return " 原因：" + summary;
    }

    private static VirtualFile chooseScope(Project project,
                                           ProjectNotesService service,
                                           VirtualFile selected) {
        VirtualFile projectRoot = ProjectUtil.guessProjectDir(project);
        if (projectRoot == null || !projectRoot.isValid()) return null;
        if (service.typeOf(selected) == com.projecttreenotes.core.NoteType.PROJECT) return projectRoot;

        String selectedKind = selected.isDirectory() ? "所选目录" : "当前文件";
        String relative = service.relativePath(selected);
        int choice = Messages.showDialog(project,
                "当前选中：" + relative + "\n\n请选择这次 AI 生成的扫描范围。",
                "选择 AI 生成范围",
                new String[]{selectedKind, "整个项目", "取消"},
                0, Messages.getQuestionIcon());
        return switch (choice) {
            case 0 -> selected;
            case 1 -> projectRoot;
            default -> null;
        };
    }

    private record SettingsSnapshot(String baseUrl,
                                    String model,
                                    int timeoutSeconds,
                                    int maxCharsPerFile,
                                    int maxTotalContentChars,
                                    int maxItemsPerRequest,
                                    int maxCharsPerRequest,
                                    boolean overwriteExisting) {
        private SettingsSnapshot(ProjectNotesSettings.StateData settings) {
            this(settings.baseUrl, settings.model, settings.timeoutSeconds, settings.maxCharsPerFile,
                    settings.maxTotalContentChars, settings.maxItemsPerRequest,
                    settings.maxCharsPerRequest, settings.overwriteExisting);
        }
    }
}

