package com.workreport.agent.ui.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Markdown to block-model parser.
// The block rules mirror the old MarkdownRenderer one-for-one (fenced code, 1-3 level lists,
// table separator rows, quotes, rules, emphasis, inline code, links) but emit values instead of
// an HTML string, so nothing has to be re-parsed or re-laid-out by the Swing HTML engine.
// Markdown 解析器：块规则与旧的 MarkdownRenderer 一一对应，但输出数据模型而非 HTML 字符串，
// 因此不再需要 Swing 的 HTML 引擎做全量重排。
public final class MarkdownParser {

    private MarkdownParser() {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL_ITEM = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");

    // One alternation so the scanner walks the source exactly once; group indices are fixed.
    // 单一交替式正则：源文本只扫描一次，分组下标固定。
    private static final Pattern INLINE = Pattern.compile(
            "`([^`\\n]+)`"
                    + "|\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)"
                    + "|\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*"
                    + "|(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])"
                    + "|(?<!\\*)\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?!\\*)"
                    + "|(?<![\\w])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w])"
                    + "|~~(?=\\S)(.+?)(?<=\\S)~~");

    // Blocks plus the source offset each block started at (needed by the incremental parser).
    // 块列表 + 每个块在源文本中的起始偏移（增量解析用）。
    public record Parsed(List<MarkdownBlock> blocks, List<Integer> offsets) {
    }

    public static List<MarkdownBlock> parse(String markdown) {
        return parseWithOffsets(markdown).blocks();
    }

    public static Parsed parseWithOffsets(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<MarkdownBlock> blocks = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int[] lineStarts = lineStarts(lines);

        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String trimmed = raw.trim();

            // Fenced code block: every whitespace character is preserved, lines never merge.
            // 围栏代码块：保留每一个空白字符，行不合并。
            if (trimmed.startsWith("```")) {
                int start = i;
                String lang = trimmed.substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) {
                    i++; // closing fence
                }
                add(blocks, offsets, new MarkdownBlock.CodeBlock(lang, stripTrailingNewline(code.toString())),
                        lineStarts[start]);
                continue;
            }

            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            if (HR.matcher(trimmed).matches()) {
                add(blocks, offsets, new MarkdownBlock.Divider(), lineStarts[i]);
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                add(blocks, offsets,
                        new MarkdownBlock.Heading(heading.group(1).length(), inline(heading.group(2))),
                        lineStarts[i]);
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                int start = i;
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String text = lines[i].trim().substring(1).trim();
                    if (quote.length() > 0 && !text.isEmpty()) {
                        quote.append(' ');
                    }
                    quote.append(text);
                    i++;
                }
                add(blocks, offsets, new MarkdownBlock.Quote(inline(quote.toString())), lineStarts[start]);
                continue;
            }

            if (isTableRow(trimmed) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
                int start = i;
                i = readTable(lines, i, blocks, offsets, lineStarts[start]);
                continue;
            }

            Matcher bullet = UL_ITEM.matcher(raw);
            Matcher ordered = OL_ITEM.matcher(raw);
            if (bullet.matches() || ordered.matches()) {
                int start = i;
                i = readList(lines, i, blocks, offsets, lineStarts[start]);
                continue;
            }

            // Plain text: consecutive lines join into one paragraph, like real Markdown.
            // 普通文本：连续行合并成一段，与标准 Markdown 一致。
            int paragraphStart = i;
            StringBuilder paragraph = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty() && !startsBlock(lines, i)) {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(lines[i].trim());
                i++;
            }
            add(blocks, offsets, new MarkdownBlock.Paragraph(inline(paragraph.toString())),
                    lineStarts[paragraphStart]);
        }
        return new Parsed(List.copyOf(blocks), List.copyOf(offsets));
    }

    private static void add(List<MarkdownBlock> blocks, List<Integer> offsets, MarkdownBlock block,
                            int offset) {
        blocks.add(block);
        offsets.add(offset);
    }

    private static int[] lineStarts(String[] lines) {
        int[] starts = new int[lines.length];
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = offset;
            offset += lines[i].length() + 1;
        }
        return starts;
    }

    // True when the line opens a block of its own, which terminates a running paragraph.
    // 该行是否开启一个新块（会终止当前段落）。
    private static boolean startsBlock(String[] lines, int index) {
        String trimmed = lines[index].trim();
        if (trimmed.startsWith("```") || trimmed.startsWith(">") || HEADING.matcher(trimmed).matches()
                || HR.matcher(trimmed).matches()) {
            return true;
        }
        String raw = lines[index];
        if (UL_ITEM.matcher(raw).matches() || OL_ITEM.matcher(raw).matches()) {
            return true;
        }
        return isTableRow(trimmed) && index + 1 < lines.length && isTableSeparator(lines[index + 1]);
    }

    // Lists become one block with per-item nesting; blank lines only continue when a list item
    // follows, and indented continuation lines are folded into the item above.
    // 列表合成一个块，逐项记录层级；空行只有后面仍是列表项时才继续，缩进续行并入上一条。
    private static int readList(String[] lines, int start, List<MarkdownBlock> blocks,
                                List<Integer> offsets, int offset) {
        List<MarkdownBlock.ListItem> items = new ArrayList<>();
        boolean ordered = false;
        int i = start;
        while (i < lines.length) {
            String raw = lines[i];
            String trimmed = raw.trim();
            Matcher bullet = UL_ITEM.matcher(raw);
            Matcher number = OL_ITEM.matcher(raw);
            if (bullet.matches()) {
                items.add(new MarkdownBlock.ListItem(indentLevel(bullet.group(1)), "\u2022",
                        inline(bullet.group(2))));
            } else if (number.matches()) {
                ordered = true;
                items.add(new MarkdownBlock.ListItem(indentLevel(number.group(1)),
                        number.group(2) + ".", inline(number.group(3))));
            } else if (trimmed.isEmpty()) {
                int next = nextListItem(lines, i + 1);
                if (next < 0) {
                    break;
                }
                i = next;
                continue;
            } else if (!items.isEmpty() && (raw.startsWith("  ") || raw.startsWith("\t"))
                    && !trimmed.startsWith("```")) {
                MarkdownBlock.ListItem last = items.remove(items.size() - 1);
                List<InlineSpan> merged = new ArrayList<>(last.spans());
                merged.add(InlineSpan.plain(" "));
                merged.addAll(inline(trimmed));
                items.add(new MarkdownBlock.ListItem(last.level(), last.marker(), List.copyOf(merged)));
            } else {
                break;
            }
            i++;
        }
        add(blocks, offsets, new MarkdownBlock.ListBlock(ordered, List.copyOf(items)), offset);
        return i;
    }

    private static int nextListItem(String[] lines, int from) {
        int i = from;
        while (i < lines.length && lines[i].trim().isEmpty()) {
            i++;
        }
        if (i >= lines.length) {
            return -1;
        }
        String raw = lines[i];
        return UL_ITEM.matcher(raw).matches() || OL_ITEM.matcher(raw).matches() ? i : -1;
    }

    private static int readTable(String[] lines, int start, List<MarkdownBlock> blocks,
                                 List<Integer> offsets, int offset) {
        List<List<String>> header = new ArrayList<>();
        header.add(splitRow(lines[start]));
        List<List<String>> rows = new ArrayList<>();
        int i = start + 2;
        while (i < lines.length && isTableRow(lines[i].trim())) {
            rows.add(splitRow(lines[i]));
            i++;
        }
        add(blocks, offsets, new MarkdownBlock.Table(List.copyOf(header), List.copyOf(rows)), offset);
        return i;
    }

    private static boolean isTableRow(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.length() > 2;
    }

    private static boolean isTableSeparator(String line) {
        String trimmed = line.trim();
        if (!trimmed.contains("-")) {
            return false;
        }
        for (char c : trimmed.toCharArray()) {
            if (c != '-' && c != '|' && c != ':' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return trimmed.replace("|", "").replace(":", "").replace(" ", "").length() >= 3;
    }

    private static List<String> splitRow(String line) {
        String body = line.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static int indentLevel(String leadingWhitespace) {
        int width = 0;
        for (char c : leadingWhitespace.toCharArray()) {
            width += c == '\t' ? 4 : 1;
        }
        return Math.min(3, width / 2);
    }

    private static String stripTrailingNewline(String code) {
        return code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
    }

    // ---------------------------------------------------------------- inline

    // Split one line of text into styled spans. Never throws: a parser bug degrades to plain text.
    // 把一行文本拆成带样式的 span；解析异常时降级为纯文本，绝不把异常抛给调用方。
    public static List<InlineSpan> inline(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        try {
            return inlineInternal(text);
        } catch (RuntimeException e) {
            return List.of(InlineSpan.plain(text));
        }
    }

    private static List<InlineSpan> inlineInternal(String text) {
        List<InlineSpan> spans = new ArrayList<>();
        Matcher matcher = INLINE.matcher(text);
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() > index) {
                spans.add(InlineSpan.plain(text.substring(index, matcher.start())));
            }
            if (matcher.group(1) != null) {
                spans.add(InlineSpan.code(matcher.group(1)));
            } else if (matcher.group(2) != null) {
                spans.add(InlineSpan.link(matcher.group(2), matcher.group(3)));
            } else if (matcher.group(4) != null) {
                spans.add(styled(matcher.group(4), Style.BOLD));
            } else if (matcher.group(5) != null) {
                spans.add(styled(matcher.group(5), Style.BOLD));
            } else if (matcher.group(6) != null) {
                spans.add(styled(matcher.group(6), Style.ITALIC));
            } else if (matcher.group(7) != null) {
                spans.add(styled(matcher.group(7), Style.ITALIC));
            } else if (matcher.group(8) != null) {
                spans.add(styled(matcher.group(8), Style.STRIKE));
            }
            index = matcher.end();
        }
        if (index < text.length()) {
            spans.add(InlineSpan.plain(text.substring(index)));
        }
        return merge(spans);
    }

    private enum Style {BOLD, ITALIC, STRIKE}

    private static InlineSpan styled(String text, Style style) {
        return switch (style) {
            case BOLD -> InlineSpan.of(text, true, false, false, false, null);
            case ITALIC -> InlineSpan.of(text, false, true, false, false, null);
            case STRIKE -> InlineSpan.of(text, false, false, true, false, null);
        };
    }

    // Adjacent plain runs are glued together so the layout engine sees minimal span churn.
    // 相邻的同样式片段合并，减少排版时的碎片。
    private static List<InlineSpan> merge(List<InlineSpan> spans) {
        List<InlineSpan> merged = new ArrayList<>(spans.size());
        for (InlineSpan span : spans) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            InlineSpan last = merged.get(merged.size() - 1);
            if (last.hasSameStyle(span)) {
                merged.set(merged.size() - 1, InlineSpan.of(last.text + span.text, last.bold,
                        last.italic, last.strike, last.code, last.href));
            } else {
                merged.add(span);
            }
        }
        return List.copyOf(merged);
    }
}
