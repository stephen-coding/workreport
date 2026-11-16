package com.workreport.agent.ui.markdown;

import java.util.List;

// Block model produced by MarkdownParser and consumed by the block views.
// Replaces the old "render everything to an HTML string" approach: a block is a small immutable
// value object, so the view can reuse the blocks whose text did not change while streaming.
// 块模型：解析器输出、块视图消费。
// 取代旧的「整段渲染成 HTML 字符串」：块是不可变小对象，流式时未变化的块可直接复用视图。
public sealed interface MarkdownBlock {

    // Headings keep their level; the view maps it to a font size and a top margin.
    // 标题保留层级，由视图映射字号与上间距。
    record Heading(int level, List<InlineSpan> spans) implements MarkdownBlock {
    }

    record Paragraph(List<InlineSpan> spans) implements MarkdownBlock {
    }

    // One list item: nesting level (0..3), the marker to print ("•" / "3.") and the text.
    // 单个列表项：层级（0~3）、要显示的符号（• 或 3.）与正文。
    record ListItem(int level, String marker, List<InlineSpan> spans) {
    }

    record ListBlock(boolean ordered, List<ListItem> items) implements MarkdownBlock {
    }

    record CodeBlock(String language, String code) implements MarkdownBlock {
    }

    record Quote(List<InlineSpan> spans) implements MarkdownBlock {
    }

    // Table cells stay plain strings: cell text is short and rarely carries emphasis.
    // 表格单元格保留纯文本：单元格内容短，很少需要行内格式。
    record Table(List<List<String>> header, List<List<String>> rows) implements MarkdownBlock {
    }

    record Divider() implements MarkdownBlock {
    }
}
