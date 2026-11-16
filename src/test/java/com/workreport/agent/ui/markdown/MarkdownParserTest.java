package com.workreport.agent.ui.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The parser is the only piece of the new UI with real branching logic, so it is the piece that
// gets unit tests: one case per block kind, one per inline style, plus the incremental contract.
// 解析器是新 UI 里唯一有实质分支逻辑的部分：每类块、每类行内样式各一个用例，
// 再加一组增量一致性用例。
class MarkdownParserTest {

    @Test
    void headingKeepsLevelAndText() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("### 三级标题");
        MarkdownBlock.Heading heading = assertInstanceOf(MarkdownBlock.Heading.class, blocks.get(0));
        assertEquals(3, heading.level());
        assertEquals("三级标题", text(heading.spans()));
    }

    @Test
    void consecutiveLinesJoinIntoOneParagraph() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("第一行\n第二行\n\n第三段");
        assertEquals(2, blocks.size());
        MarkdownBlock.Paragraph first = assertInstanceOf(MarkdownBlock.Paragraph.class, blocks.get(0));
        assertEquals("第一行 第二行", text(first.spans()));
    }

    @Test
    void paragraphStopsAtNextBlock() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("正文\n# 标题");
        assertInstanceOf(MarkdownBlock.Paragraph.class, blocks.get(0));
        assertInstanceOf(MarkdownBlock.Heading.class, blocks.get(1));
    }

    @Test
    void unorderedListKeepsNesting() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("- 一级\n  - 二级\n    - 三级");
        MarkdownBlock.ListBlock list = assertInstanceOf(MarkdownBlock.ListBlock.class, blocks.get(0));
        assertEquals(3, list.items().size());
        assertEquals(0, list.items().get(0).level());
        assertEquals(1, list.items().get(1).level());
        assertEquals(2, list.items().get(2).level());
        assertEquals("\u2022", list.items().get(0).marker());
    }

    @Test
    void orderedListKeepsNumbers() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("1. 第一步\n2. 第二步");
        MarkdownBlock.ListBlock list = assertInstanceOf(MarkdownBlock.ListBlock.class, blocks.get(0));
        assertTrue(list.ordered());
        assertEquals("1.", list.items().get(0).marker());
        assertEquals("2.", list.items().get(1).marker());
    }

    @Test
    void fencedCodeKeepsWhitespaceAndLanguage() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("```java\n  int a = 1;\n\tint b = 2;\n```");
        MarkdownBlock.CodeBlock code = assertInstanceOf(MarkdownBlock.CodeBlock.class, blocks.get(0));
        assertEquals("java", code.language());
        assertEquals("  int a = 1;\n\tint b = 2;", code.code());
    }

    @Test
    void openFenceStillRendersWhileStreaming() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("```python\nprint(1)");
        MarkdownBlock.CodeBlock code = assertInstanceOf(MarkdownBlock.CodeBlock.class, blocks.get(0));
        assertEquals("print(1)", code.code());
    }

    @Test
    void tableParsesHeaderAndRows() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("| A | B |\n| --- | --- |\n| 1 | 2 |");
        MarkdownBlock.Table table = assertInstanceOf(MarkdownBlock.Table.class, blocks.get(0));
        assertEquals(List.of("A", "B"), table.header().get(0));
        assertEquals(1, table.rows().size());
        assertEquals(List.of("1", "2"), table.rows().get(0));
    }

    @Test
    void quoteAndDivider() {
        List<MarkdownBlock> blocks = MarkdownParser.parse("> 引用一句\n\n---");
        assertInstanceOf(MarkdownBlock.Quote.class, blocks.get(0));
        assertInstanceOf(MarkdownBlock.Divider.class, blocks.get(1));
    }

    @Test
    void inlineStylesAreRecognised() {
        List<InlineSpan> spans = MarkdownParser.inline(
                "普通 **粗** *斜* ~~删~~ `code` [链接](https://example.com)");
        assertTrue(spans.stream().anyMatch(span -> span.bold && span.text.equals("粗")));
        assertTrue(spans.stream().anyMatch(span -> span.italic && span.text.equals("斜")));
        assertTrue(spans.stream().anyMatch(span -> span.strike && span.text.equals("删")));
        assertTrue(spans.stream().anyMatch(span -> span.code && span.text.equals("code")));
        assertTrue(spans.stream().anyMatch(span -> span.isLink()
                && span.text.equals("链接") && span.href.equals("https://example.com")));
    }

    @Test
    void inlineMarkersNeverLeakIntoPlainText() {
        String joined = text(MarkdownParser.inline("a **b** c"));
        assertEquals("a b c", joined);
    }

    @Test
    void incrementalMatchesFullParseForEveryPrefix() {
        String document = "# 标题\n\n第一段 **粗体** 文本。\n\n- 一\n- 二\n\n```java\nint a = 1;\n```\n\n"
                + "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n> 引用\n\n结尾段落";
        IncrementalParser parser = new IncrementalParser();
        for (int end = 0; end <= document.length(); end++) {
            String prefix = document.substring(0, end);
            assertEquals(MarkdownParser.parse(prefix), parser.feed(prefix),
                    "prefix mismatch at " + end);
        }
        assertEquals(MarkdownParser.parse(document), parser.blocks());
    }

    @Test
    void incrementalReusesLeadingBlocks() {
        IncrementalParser parser = new IncrementalParser();
        List<MarkdownBlock> before = parser.feed("# 标题\n\n第一段。\n\n第二");
        List<MarkdownBlock> after = parser.feed("# 标题\n\n第一段。\n\n第二段继续写。");
        assertSame(before.get(0), after.get(0));
        assertSame(before.get(1), after.get(1));
    }

    @Test
    void clearThenReplayResetsCleanly() {
        IncrementalParser parser = new IncrementalParser();
        parser.feed("# 标题");
        parser.reset();
        List<MarkdownBlock> blocks = parser.feed("普通段落");
        assertEquals(1, blocks.size());
        assertInstanceOf(MarkdownBlock.Paragraph.class, blocks.get(0));
    }

    private static String text(List<InlineSpan> spans) {
        StringBuilder builder = new StringBuilder();
        for (InlineSpan span : spans) {
            builder.append(span.text);
        }
        return builder.toString();
    }
}
