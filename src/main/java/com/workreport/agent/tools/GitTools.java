package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.workreport.plugin.model.GitCommitEntry;
import com.workreport.plugin.service.GitLogCollectorService;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.time.LocalDate;
import java.util.List;

// git_log / git_diff implementations built on the existing git4idea plumbing.
// git_log / git_diff 实现，复用现有 git4idea 调用链路。
public final class GitTools {

    private final Project project;

    public GitTools(Project project) {
        this.project = project;
    }

    public ToolResult log(JsonObject args) {
        if (!args.has("start_date") || !args.has("end_date")) {
            return ToolResult.fail("缺少参数 start_date / end_date（yyyy-MM-dd）");
        }
        try {
            LocalDate start = LocalDate.parse(args.get("start_date").getAsString());
            LocalDate end = LocalDate.parse(args.get("end_date").getAsString());
            if (end.isBefore(start)) {
                return ToolResult.fail("end_date 早于 start_date");
            }
            GitLogCollectorService service = new GitLogCollectorService();
            List<GitCommitEntry> entries = service.collect(project, start, end, "daily");
            return ToolResult.ok(service.formatEntries(entries));
        } catch (Exception e) {
            return ToolResult.fail("git_log 失败: " + e.getMessage());
        }
    }

    public ToolResult diff(JsonObject args) {
        GitRepository repository = firstRepository();
        if (repository == null) {
            return ToolResult.fail("未找到 Git 仓库");
        }
        GitLineHandler handler = new GitLineHandler(project, repository.getRoot(), GitCommand.DIFF);
        if (args.has("path")) {
            handler.addParameters(args.get("path").getAsString());
        }
        GitCommandResult result = Git.getInstance().runCommand(handler);
        if (!result.success()) {
            return ToolResult.fail("git diff 失败: " + result.getErrorOutputAsJoinedString());
        }
        String output = result.getOutputAsJoinedString();
        if (output == null || output.isBlank()) {
            return ToolResult.ok("（无未提交变更）");
        }
        if (output.length() > 20000) {
            output = output.substring(0, 20000) + "\n...(已截断)";
        }
        return ToolResult.ok(output);
    }

    private GitRepository firstRepository() {
        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        return repositories.isEmpty() ? null : repositories.get(0);
    }
}
