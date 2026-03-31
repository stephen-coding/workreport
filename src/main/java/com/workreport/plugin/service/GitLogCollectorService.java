package com.workreport.plugin.service;

import com.intellij.openapi.project.Project;
import com.workreport.plugin.model.GitCommitEntry;
import com.workreport.plugin.settings.ReportSettingsState;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitCommandResult;
import git4idea.commands.Git;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// Git log collector service — execute git log via GitLineHandler and parse output
// Git 日志采集服务 — 通过 GitLineHandler 执行 git log 命令并解析输出
public final class GitLogCollectorService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] DAY_NAMES_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    // git log output format: hash|author|date|subject
    // git log 输出格式：hash|author|date|subject
    private static final String LOG_FORMAT = "%h|%an|%ad|%s";
    private static final String FIELD_SEPARATOR = "\\|";

    /**
     * Collect git commits (called on background thread).
     * 采集 Git 提交记录（在后台线程调用）。
     */
    public String collectFormatted(Project project, LocalDate startDate, LocalDate endDate, String reportType) {
        List<GitCommitEntry> entries = collect(project, startDate, endDate, reportType);
        return formatEntries(entries);
    }

    // Key logic: Execute git log command via GitLineHandler
    // 关键逻辑：通过 GitLineHandler 执行 git log 命令
    public List<GitCommitEntry> collect(Project project, LocalDate startDate, LocalDate endDate, String reportType) {
        List<GitCommitEntry> allEntries = new ArrayList<>();
        GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
        List<GitRepository> repos = manager.getRepositories();

        ReportSettingsState settings = ReportSettingsState.getInstance();
        String authorFilter = settings.authorFilter;

        for (GitRepository repo : repos) {
            try {
                // Key logic: Build git log command
                // 关键逻辑：构建 git log 命令
                GitLineHandler handler = new GitLineHandler(project, repo.getRoot(), GitCommand.LOG);
                
                // 日报：精确查询当天（since/until 是左闭右开区间）
                // 其他类型：扩展 1 天以包含边界日期
                boolean isDaily = "daily".equals(reportType);
                
                LocalDate queryStart;
                LocalDate queryEnd;
                if (isDaily) {
                    // 日报：精确日期，添加时间确保包含当天整天
                    queryStart = startDate;
                    queryEnd = endDate.plusDays(1); // 不包含 endDate+1 当天
                } else {
                    // 周报/季报/年报：扩展 1 天包含边界
                    queryStart = startDate.minusDays(1);
                    queryEnd = endDate.plusDays(1);
                }
                
                handler.addParameters(
                        "--pretty=format:" + LOG_FORMAT,
                        "--date=short",
                        "--after=" + queryStart + " 00:00:00",
                        "--before=" + queryEnd + " 00:00:00",
                        "--no-merges"
                );
                if (authorFilter != null && !authorFilter.isBlank()) {
                    handler.addParameters("--author=" + authorFilter);
                }

                // Key logic: Execute command and parse output
                // 关键逻辑：执行命令并解析输出
                GitCommandResult result = Git.getInstance().runCommand(handler);
                if (result.success()) {
                    parseGitOutput(result.getOutputAsJoinedString(), allEntries);
                } else {
                    allEntries.add(new GitCommitEntry(
                            "ERROR", "system", LocalDate.now(),
                            "git log 命令失败: " + result.getErrorOutputAsJoinedString()
                    ));
                }
            } catch (Exception e) {
                // Key logic: Don't interrupt on failure, record error
                // 关键逻辑：采集失败时不中断，记录错误
                allEntries.add(new GitCommitEntry(
                        "ERROR", "system", LocalDate.now(),
                        "采集仓库 [" + repo.getRoot().getName() + "] 失败: " + e.getMessage()
                ));
            }
        }

        allEntries.sort(Comparator.comparing(GitCommitEntry::date).reversed());
        return allEntries;
    }

    // Key logic: Parse git log output lines (format: hash|author|date|subject)
    // 关键逻辑：解析 git log 输出行（格式: hash|author|date|subject）
    private void parseGitOutput(String output, List<GitCommitEntry> entries) {
        if (output == null || output.isBlank()) return;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(FIELD_SEPARATOR, 4);
            if (parts.length >= 4) {
                try {
                    LocalDate date = LocalDate.parse(parts[2].trim(), DATE_FMT);
                    entries.add(new GitCommitEntry(
                            parts[0].trim(),
                            parts[1].trim(),
                            date,
                            parts[3].trim()
                    ));
                } catch (Exception ignored) {
                    // Skip lines with format errors
                    // 跳过格式异常的行
                }
            }
        }
    }

    // Key logic: Group commits by date, format as Chinese date headers + list
    // 关键逻辑：将提交按日期分组，格式化为中文日期标题 + 列表
    public String formatEntries(List<GitCommitEntry> entries) {
        if (entries.isEmpty()) {
            return "该日期范围内没有找到提交记录。";
        }

        Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(
                        GitCommitEntry::date,
                        TreeMap::new,
                        Collectors.toList()
                ));

        var sb = new StringBuilder();
        for (var entry : new TreeMap<>(grouped).entrySet()) {
            LocalDate date = entry.getKey();
            int dayIdx = date.getDayOfWeek().getValue() - 1;
            String dayName = dayIdx >= 0 && dayIdx < DAY_NAMES_CN.length ? DAY_NAMES_CN[dayIdx] : "";

            sb.append("## ").append(date.format(DATE_FMT))
              .append("（").append(dayName).append("）\n");

            for (GitCommitEntry commit : entry.getValue()) {
                sb.append("- ").append(commit.subject())
                  .append(" (").append(commit.author()).append(")\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Generate report draft template from commit entries based on report type.
     * 基于提交记录和报告类型生成草稿模板。
     *
     * @param entries    commit entries / 提交记录
     * @param reportType report type: daily, weekly, quarterly, yearly / 报告类型
     */
    public String generateDraftReport(List<GitCommitEntry> entries, String reportType) {
        return switch (reportType) {
            case "daily" -> generateDailyReport(entries);
            case "quarterly" -> generateQuarterlyReport(entries);
            case "yearly" -> generateYearlyReport(entries);
            default -> generateWeeklyReport(entries);
        };
    }

    private String generateDailyReport(List<GitCommitEntry> entries) {
        if (entries.isEmpty()) {
            return "今日工作内容：\n\n（暂无 Git 提交记录，请手动填写）";
        }

        var sb = new StringBuilder();
        sb.append("# 日报\n\n");
        sb.append("## 今日完成工作\n\n");

        for (GitCommitEntry commit : entries) {
            sb.append("- ").append(commit.subject()).append("\n");
        }

        sb.append("\n## 明日计划\n\n");
        sb.append("- （请填写明日计划）\n");
        sb.append("\n## 遇到的问题\n\n");
        sb.append("- 无\n");

        return sb.toString();
    }

    private String generateWeeklyReport(List<GitCommitEntry> entries) {
        if (entries.isEmpty()) {
            return "本周工作内容：\n\n（暂无 Git 提交记录，请手动填写）";
        }

        var sb = new StringBuilder();
        sb.append("# 周报\n\n");
        sb.append("## 本周工作内容\n\n");

        Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(GitCommitEntry::date, TreeMap::new, Collectors.toList()));

        for (var entry : new TreeMap<>(grouped).entrySet()) {
            for (GitCommitEntry commit : entry.getValue()) {
                sb.append("- ").append(commit.subject()).append("\n");
            }
        }

        sb.append("\n## 下周计划\n\n");
        sb.append("- （请填写下周计划）\n");
        sb.append("\n## 需要协调的问题\n\n");
        sb.append("- 无\n");

        return sb.toString();
    }

    private String generateQuarterlyReport(List<GitCommitEntry> entries) {
        if (entries.isEmpty()) {
            return "本季度工作内容：\n\n（暂无 Git 提交记录，请手动填写）";
        }

        var sb = new StringBuilder();
        sb.append("# 季度报告\n\n");
        sb.append("## 本季度主要成果\n\n");

        Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(GitCommitEntry::date, TreeMap::new, Collectors.toList()));

        for (var entry : new TreeMap<>(grouped).entrySet()) {
            for (GitCommitEntry commit : entry.getValue()) {
                sb.append("- ").append(commit.subject()).append("\n");
            }
        }

        sb.append("\n## 项目进展总结\n\n");
        sb.append("- （请填写项目进展总结）\n");
        sb.append("\n## 下季度规划\n\n");
        sb.append("- （请填写下季度规划）\n");
        sb.append("\n## 风险与挑战\n\n");
        sb.append("- 无\n");

        return sb.toString();
    }

    private String generateYearlyReport(List<GitCommitEntry> entries) {
        if (entries.isEmpty()) {
            return "本年度工作内容：\n\n（暂无 Git 提交记录，请手动填写）";
        }

        var sb = new StringBuilder();
        sb.append("# 年度报告\n\n");
        sb.append("## 年度工作回顾\n\n");

        Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(GitCommitEntry::date, TreeMap::new, Collectors.toList()));

        for (var entry : new TreeMap<>(grouped).entrySet()) {
            for (GitCommitEntry commit : entry.getValue()) {
                sb.append("- ").append(commit.subject()).append("\n");
            }
        }

        sb.append("\n## 年度成果总结\n\n");
        sb.append("- （请填写年度成果总结）\n");
        sb.append("\n## 来年目标\n\n");
        sb.append("- （请填写来年目标）\n");
        sb.append("\n## 个人成长与反思\n\n");
        sb.append("- （请填写个人成长与反思）\n");

        return sb.toString();
    }
}
