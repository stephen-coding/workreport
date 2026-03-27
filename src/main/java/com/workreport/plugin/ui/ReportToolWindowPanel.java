package com.workreport.plugin.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.workreport.plugin.model.GitCommitEntry;
import com.workreport.plugin.privacy.PrivacyNoticeUtil;
import com.workreport.plugin.service.GitLogCollectorService;
import com.workreport.plugin.service.ReportPolishService;
import com.workreport.plugin.settings.ReportSettingsState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

// Main UI panel — contains date picker, Git log, report editor, polish button (disabled by default)
// 主 UI 面板 — 含日期选择、Git 日志、报告编辑、润色按钮（默认禁用）
public final class ReportToolWindowPanel extends SimpleToolWindowPanel {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Project project;
    private final GitLogCollectorService gitLogService;
    private final ReportPolishService polishService;

    private JComboBox<String> reportTypeCombo;
    private JTextField startDateField;
    private JTextField endDateField;
    private JBTextArea gitLogArea;
    private JBTextArea reportArea;
    private JButton polishButton;
    private JButton collectButton;
    private JButton copyButton;
    private JButton exportButton;
    private JBLabel statusLabel;

    // Report type mapping
    private static final Map<String, String> REPORT_TYPE_MAP = Map.of(
            "日报", "daily",
            "周报", "weekly",
            "季报", "quarterly",
            "年报", "yearly"
    );
    private static final Map<String, String> REPORT_TYPE_REVERSE = Map.of(
            "daily", "日报",
            "weekly", "周报",
            "quarterly", "季报",
            "yearly", "年报"
    );

    // Cache collected commit entries for draft generation
    // 缓存采集到的提交记录，用于生成草稿
    private List<GitCommitEntry> lastCollectedEntries;

    public ReportToolWindowPanel(Project project) {
        super(true, true);
        this.project = project;
        this.gitLogService = new GitLogCollectorService();
        this.polishService = new ReportPolishService();

        initUI();
        refreshPolishButtonState();
    }

    private void initUI() {
        // 主面板：使用 BorderLayout，带内边距
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(UIUtil.getPanelBackground());

        // ==================== 顶部工具栏 ====================
        JPanel toolbarPanel = createToolbarPanel();
        mainPanel.add(toolbarPanel, BorderLayout.NORTH);

        // ==================== 中央内容区：左右分栏 ====================
        JSplitPane centerSplitPane = createCenterSplitPane();
        mainPanel.add(centerSplitPane, BorderLayout.CENTER);

        // ==================== 底部操作栏 ====================
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContent(mainPanel);

        // ==================== 事件绑定 ====================
        collectButton.addActionListener(e -> onCollectGitLog());
        polishButton.addActionListener(e -> onPolish());
        copyButton.addActionListener(e -> onCopy());
        exportButton.addActionListener(e -> onExport());
    }

    /**
     * 创建顶部工具栏面板
     */
    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(UIUtil.getPanelBackground());
        panel.setBorder(new EmptyBorder(8, 0, 8, 0));

        // 左侧：报告类型 + 日期选择
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftPanel.setOpaque(false);

        // 报告类型下拉框（现代化样式）
        reportTypeCombo = new JComboBox<>(new String[]{"日报", "周报", "季报", "年报"});
        reportTypeCombo.setSelectedItem(REPORT_TYPE_REVERSE.getOrDefault(
                ReportSettingsState.getInstance().reportType, "周报"));
        reportTypeCombo.addActionListener(e -> onReportTypeChanged());
        reportTypeCombo.setFont(reportTypeCombo.getFont().deriveFont(Font.PLAIN, 13f));

        // 日期范围选择器
        LocalDate[] dateRange = calculateDateRange(getCurrentReportType());
        startDateField = new JBTextField(dateRange[0].format(DATE_FMT), 9);
        endDateField = new JBTextField(dateRange[1].format(DATE_FMT), 9);
        startDateField.setColumns(9);
        endDateField.setColumns(9);

        leftPanel.add(new JBLabel("报告类型:"));
        leftPanel.add(reportTypeCombo);
        leftPanel.add(Box.createHorizontalStrut(10));
        leftPanel.add(new JBLabel("起始日期:"));
        leftPanel.add(startDateField);
        leftPanel.add(new JBLabel("结束日期:"));
        leftPanel.add(endDateField);

        // 右侧：采集按钮 + 设置按钮
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setOpaque(false);

        collectButton = new JButton("采集 Git 日志");
        collectButton.setFont(collectButton.getFont().deriveFont(Font.BOLD, 13f));
        collectButton.setFocusPainted(false);

        rightPanel.add(collectButton);

        panel.add(leftPanel, BorderLayout.CENTER);
        panel.add(rightPanel, BorderLayout.EAST);

        return panel;
    }

    /**
     * 创建中央分栏面板（左：Git 日志，右：报告编辑）
     */
    private JSplitPane createCenterSplitPane() {
        // Git 日志区域
        gitLogArea = new JBTextArea(10, 40);
        gitLogArea.setEditable(false);
        gitLogArea.setLineWrap(true);
        gitLogArea.setWrapStyleWord(true);
        // 关键修复：使用支持中文的字体，避免乱码
        // JetBrains Mono 对中文支持不好，改用系统默认字体并增大字号
        Font defaultFont = UIManager.getFont("TextArea.font");
        if (defaultFont != null) {
            gitLogArea.setFont(defaultFont.deriveFont(13f));
        }
        gitLogArea.setText("点击 [采集 Git 日志] 按钮获取提交记录...");
        gitLogArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        
        JBScrollPane gitLogScroll = new JBScrollPane(gitLogArea);
        gitLogScroll.setBorder(LineBorder.createGrayLineBorder());
        
        JPanel gitLogPanel = new JPanel(new BorderLayout());
        gitLogPanel.add(new JBLabel("Git 提交记录 (只读)"), BorderLayout.NORTH);
        gitLogPanel.add(gitLogScroll, BorderLayout.CENTER);
        gitLogPanel.setBorder(new EmptyBorder(0, 0, 8, 0));

        // 报告编辑区域
        reportArea = new JBTextArea(10, 40);
        reportArea.setEditable(true);
        reportArea.setLineWrap(true);
        reportArea.setWrapStyleWord(true);
        // 关键修复：使用支持中文的字体，避免乱码
        Font reportDefaultFont = UIManager.getFont("TextArea.font");
        if (reportDefaultFont != null) {
            reportArea.setFont(reportDefaultFont.deriveFont(13f));
        }
        reportArea.setText("采集 Git 日志后将自动生成报告草稿，您也可以手动编辑...");
        reportArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        
        JBScrollPane reportScroll = new JBScrollPane(reportArea);
        reportScroll.setBorder(LineBorder.createGrayLineBorder());
        
        JPanel reportPanel = new JPanel(new BorderLayout());
        reportPanel.add(new JBLabel("报告编辑器 (支持 Markdown)"), BorderLayout.NORTH);
        reportPanel.add(reportScroll, BorderLayout.CENTER);
        reportPanel.setBorder(new EmptyBorder(0, 8, 0, 0));

        // 分栏面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gitLogPanel, reportPanel);
        splitPane.setResizeWeight(0.4); // 左侧占 40%
        splitPane.setDividerLocation(0.4);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);

        return splitPane;
    }

    /**
     * 创建底部操作面板
     */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBackground(UIUtil.getPanelBackground());
        panel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // 按钮区域
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttonPanel.setOpaque(false);

        // AI 润色按钮（主按钮，高亮显示）
        polishButton = new JButton("AI 润色");
        polishButton.setEnabled(false);
        polishButton.setToolTipText("需要先在设置中配置 API 端点");
        polishButton.setFont(polishButton.getFont().deriveFont(Font.BOLD, 13f));
        polishButton.setFocusPainted(false);
        polishButton.setBackground(new Color(79, 121, 255));
        polishButton.setForeground(Color.WHITE);

        // 复制按钮
        copyButton = new JButton("复制报告");
        copyButton.setFont(copyButton.getFont().deriveFont(Font.PLAIN, 13f));
        copyButton.setFocusPainted(false);

        // 导出按钮
        exportButton = new JButton("导出为文件");
        exportButton.setFont(exportButton.getFont().deriveFont(Font.PLAIN, 13f));
        exportButton.setFocusPainted(false);

        buttonPanel.add(polishButton);
        buttonPanel.add(Box.createHorizontalStrut(10));
        buttonPanel.add(copyButton);
        buttonPanel.add(exportButton);

        // 状态标签
        statusLabel = new JBLabel("提示：润色功能需要在 Settings → Tools → Work Report AI Assistant 中配置 API");
        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));

        panel.add(buttonPanel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    // Get current report type from combo box
    private String getCurrentReportType() {
        String selected = (String) reportTypeCombo.getSelectedItem();
        return REPORT_TYPE_MAP.getOrDefault(selected, "weekly");
    }

    // Calculate default date range based on report type
    private LocalDate[] calculateDateRange(String reportType) {
        LocalDate today = LocalDate.now();
        LocalDate start, end;

        switch (reportType) {
            case "daily":
                // Daily report: today only
                start = today;
                end = today;
                break;
            case "quarterly":
                // Quarterly report: current quarter
                int month = today.getMonthValue();
                int quarterStartMonth = ((month - 1) / 3) * 3 + 1;
                start = LocalDate.of(today.getYear(), quarterStartMonth, 1);
                end = start.plusMonths(3).minusDays(1);
                break;
            case "yearly":
                // Yearly report: current year
                start = LocalDate.of(today.getYear(), 1, 1);
                end = LocalDate.of(today.getYear(), 12, 31);
                break;
            case "weekly":
            default:
                // Weekly report: Monday to today
                start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = today;
                break;
        }
        return new LocalDate[]{start, end};
    }

    // Handle report type change
    private void onReportTypeChanged() {
        String reportType = getCurrentReportType();
        // Save to settings
        ReportSettingsState.getInstance().reportType = reportType;

        // Update date range
        LocalDate[] dateRange = calculateDateRange(reportType);
        startDateField.setText(dateRange[0].format(DATE_FMT));
        endDateField.setText(dateRange[1].format(DATE_FMT));

        // Update UI labels
        statusLabel.setText("已切换到" + REPORT_TYPE_REVERSE.get(reportType) + "模式");
        statusLabel.setForeground(JBColor.BLUE);
    }

    // Key logic: Enable/disable polish button based on API configuration state
    // 关键逻辑：根据 API 配置状态决定润色按钮是否可用
    private void refreshPolishButtonState() {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        boolean enabled = settings.isApiConfigured();
        polishButton.setEnabled(enabled);
        if (enabled) {
            statusLabel.setText("就绪：API 已配置为 " + settings.apiEndpoint);
            statusLabel.setForeground(new JBColor(new Color(0, 120, 0), new Color(80, 200, 80)));
        } else {
            statusLabel.setText("提示：润色功能需要在 Settings -> Tools -> Work Report AI Assistant 中配置 API");
            statusLabel.setForeground(JBColor.GRAY);
        }
    }

    // Key logic: Async git log collection, don't block EDT
    // 关键逻辑：异步采集 Git 日志，不阻塞 EDT
    private void onCollectGitLog() {
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateField.getText().trim(), DATE_FMT);
            endDate = LocalDate.parse(endDateField.getText().trim(), DATE_FMT);
        } catch (Exception ex) {
            statusLabel.setText("日期格式错误，请使用 yyyy-MM-dd 格式");
            statusLabel.setForeground(JBColor.RED);
            return;
        }

        String reportType = getCurrentReportType();
        collectButton.setEnabled(false);
        statusLabel.setText("正在采集 Git 日志...");
        statusLabel.setForeground(JBColor.BLUE);

        // Key logic: Execute Git operations on pooled thread
        // 关键逻辑：在后台线程执行 Git 操作
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<GitCommitEntry> entries = gitLogService.collect(project, startDate, endDate);
            String formatted = gitLogService.formatEntries(entries);
            String draft = gitLogService.generateDraftReport(entries, reportType);

            // Key logic: Return to EDT for UI update
            // 关键逻辑：回到 EDT 更新 UI
            ApplicationManager.getApplication().invokeLater(() -> {
                lastCollectedEntries = entries;
                gitLogArea.setText(formatted);
                reportArea.setText(draft);
                collectButton.setEnabled(true);
                statusLabel.setText("采集完成，共 " + entries.size() + " 条提交记录");
                statusLabel.setForeground(new JBColor(new Color(0, 120, 0), new Color(80, 200, 80)));
                refreshPolishButtonState();
            });
        });
    }

    // Key logic: Polish flow — privacy check -> async AI call with streaming output
    // 关键逻辑：润色流程 — 隐私检查 -> 异步 AI 调用（流式输出）
    private void onPolish() {
        String reportText = reportArea.getText();
        if (reportText == null || reportText.isBlank()) {
            statusLabel.setText("报告内容为空，无法润色");
            statusLabel.setForeground(JBColor.RED);
            return;
        }
        String reportType = getCurrentReportType();

        // Key logic: Must show privacy confirmation dialog on first use
        // 关键逻辑：首次使用必弹隐私确认框
        if (!PrivacyNoticeUtil.ensurePrivacyAccepted()) {
            statusLabel.setText("用户取消了润色操作");
            statusLabel.setForeground(JBColor.ORANGE);
            return;
        }

        polishButton.setEnabled(false);
        statusLabel.setText("正在 AI 润色中...");
        statusLabel.setForeground(JBColor.BLUE);

        // 清空编辑区，准备流式输出
        reportArea.setText("");

        // Key logic: Async AI polish with streaming (typewriter effect)
        // 关键逻辑：异步 AI 润色，流式输出实现打字机效果
        polishService.polishAsyncStreaming(project, reportText, reportType,
                // Token callback - 每收到一个 token 立即追加到文本区
                token -> reportArea.append(token),
                // Success callback / 成功回调
                polishedText -> {
                    polishButton.setEnabled(true);
                    statusLabel.setText("润色完成！");
                    statusLabel.setForeground(new JBColor(new Color(0, 120, 0), new Color(80, 200, 80)));
                },
                // Error callback / 失败回调
                error -> {
                    polishButton.setEnabled(true);
                    statusLabel.setText("润色失败: " + error.getMessage());
                    statusLabel.setForeground(JBColor.RED);
                    ReportPolishService.notifyError(project, "润色失败: " + error.getMessage());
                }
        );
    }

    // Copy report content to system clipboard
    // 复制报告内容到系统剪贴板
    private void onCopy() {
        String text = reportArea.getText();
        if (text != null && !text.isBlank()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            statusLabel.setText("已复制到剪贴板");
            statusLabel.setForeground(new JBColor(new Color(0, 120, 0), new Color(80, 200, 80)));
        }
    }

    // Export report as .md file
    // 导出报告为 .md 文件
    private void onExport() {
        String text = reportArea.getText();
        if (text == null || text.isBlank()) {
            statusLabel.setText("报告内容为空，无法导出");
            statusLabel.setForeground(JBColor.RED);
            return;
        }

        FileSaverDescriptor descriptor = new FileSaverDescriptor("导出报告", "将报告保存为 Markdown 文件", "md");
        FileSaverDialog dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project);

        String defaultName = getCurrentReportType() + "-report-" + LocalDate.now().format(DATE_FMT);
        VirtualFileWrapper wrapper = dialog.save(defaultName);
        if (wrapper != null) {
            try {
                Files.writeString(wrapper.getFile().toPath(), text, StandardCharsets.UTF_8);
                statusLabel.setText("已导出至: " + wrapper.getFile().getAbsolutePath());
                statusLabel.setForeground(new JBColor(new Color(0, 120, 0), new Color(80, 200, 80)));
            } catch (IOException e) {
                statusLabel.setText("导出失败: " + e.getMessage());
                statusLabel.setForeground(JBColor.RED);
            }
        }
    }
}
