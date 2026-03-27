package com.workreport.plugin.model;

import java.time.LocalDate;

// Java 21 record — immutable data carrier for a single git commit
// Java 21 record — 单条 Git 提交记录的不可变数据载体
public record GitCommitEntry(
        String hash,     // 提交哈希值（短格式）
        String author,   // 提交作者
        LocalDate date,  // 提交日期
        String subject   // 提交说明（首行）
) {
    // Format as a readable text line
    // 格式化为可读文本行
    public String toDisplayLine() {
        return "- %s %s (%s)".formatted(date, subject, author);
    }
}
