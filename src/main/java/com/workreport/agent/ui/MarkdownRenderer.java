package com.workreport.agent.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Minimal Markdown to HTML renderer for Swing's JEditorPane.
// Swing only understands an HTML 3.2 subset (no flex/grid, limited CSS), so blocks are
// emitted with tables, lists-as-divs and <pre> instead of modern constructs.
// 轻量 Markdown 渲染器：只输出 Swing JEditorPane 能正确显示的 HTML 子集，
// 重点解决列表缩进、代码块保留空白、表格与标题层级——这些是纯文本追加时最乱的部分。
public final class MarkdownRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL_ITEM = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");

    private MarkdownRenderer() {
    }

    public static String toHtml(String markdown) {
        Palette p = Palette.current();
        String body = renderBody(markdown == null ? "" : markdown, p);
        return "<html><head><style type=\"text/css\">"
                + "body{font-family:" + p.font + ";font-size:" + p.size + "px;color:" + p.text + ";}"
                + "p{margin-top:0;margin-bottom:" + p.paragraphGap + "px;}"
                + "td{font-family:" + p.font + ";font-size:" + p.size + "px;color:" + p.text + ";}"
                + "a{color:" + p.link + ";}"
                + "pre{font-family:" + p.mono + ";font-size:" + p.codeSize + "px;color:" + p.codeText + ";}"
                + "code{font-family:" + p.mono + ";}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static String renderBody(String markdown, Palette p) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() * 2);
        StringBuilder paragraph = new StringBuilder();
        int i = 0;

        while (i < lines.length) {
            String raw = lines[i];
            String trimmed = raw.trim();

            // Fenced code block: keep every whitespace character, never merge lines.
            if (trimmed.startsWith("```")) {
                flushParagraph(paragraph, out, p);
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
                out.append(codeBlock(code.toString(), lang, p));
                continue;
            }

            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, out, p);
                i++;
                continue;
            }

            if (HR.matcher(trimmed).matches()) {
                flushParagraph(paragraph, out, p);
                out.append("<hr>");
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushParagraph(paragraph, out, p);
                out.append(heading(heading.group(1).length(), heading.group(2), p));
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                flushParagraph(paragraph, out, p);
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String text = lines[i].trim().substring(1).trim();
                    if (quote.length() > 0 && !text.isEmpty()) {
                        quote.append(' ');
                    }
                    quote.append(text);
                    i++;
                }
                out.append(quoteBlock(quote.toString(), p));
                continue;
            }

            if (isTableRow(trimmed) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
                flushParagraph(paragraph, out, p);
                i = renderTable(lines, i, out, p);
                continue;
            }

            if (UL_ITEM.matcher(raw).matches() || OL_ITEM.matcher(raw).matches()) {
                flushParagraph(paragraph, out, p);
                i = renderList(lines, i, out, p);
                continue;
            }

            // Plain text: consecutive lines join into one paragraph, like real Markdown.
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
            i++;
        }
        flushParagraph(paragraph, out, p);
        return out.toString();
    }

    private static void flushParagraph(StringBuilder paragraph, StringBuilder out, Palette p) {
        if (paragraph.length() == 0) {
            return;
        }
        out.append("<p>").append(inline(paragraph.toString(), p)).append("</p>");
        paragraph.setLength(0);
    }

    // Headings scale from the label font so they stay proportional across IDE font sizes.
    // 标题字号按 IDE 基础字号推算，保证不同字号设置下的层级比例一致。
    private static String heading(int level, String text, Palette p) {
        int size = p.size + (level == 1 ? 6 : level == 2 ? 4 : level <= 4 ? 2 : 1);
        int top = level <= 2 ? p.paragraphGap + 4 : p.paragraphGap + 2;
        return "<p style=\"margin-top:" + top + "px;margin-bottom:4px\">"
                + "<b><span style=\"font-size:" + size + "px\">" + inline(text, p)
                + "</span></b></p>";
    }

    // A list becomes one two-column table: a fixed gutter for the marker and a wrapping cell
    // for the text. Swing indents <ul>/<ol> inconsistently, while a table keeps wrapped lines
    // aligned under the text at any nesting depth.
    // 列表统一渲染为两列表格：左列固定宽度放符号，右列放可换行正文。
    // Swing 自带 <ul>/<ol> 缩进不可靠，用表格可保证续行与符号严格对齐。
    private static int renderList(String[] lines, int start, StringBuilder out, Palette p) {
        List<String[]> rows = new ArrayList<>();
        int i = start;
        while (i < lines.length) {
            String raw = lines[i];
            String trimmed = raw.trim();
            Matcher bullet = UL_ITEM.matcher(raw);
            Matcher ordered = OL_ITEM.matcher(raw);
            if (bullet.matches()) {
                rows.add(new String[]{"\u2022", Integer.toString(indentLevel(bullet.group(1))),
                        bullet.group(2)});
            } else if (ordered.matches()) {
                rows.add(new String[]{orderedPrefix(raw),
                        Integer.toString(indentLevel(ordered.group(1))), ordered.group(2)});
            } else if (trimmed.isEmpty()) {
                int next = nextListItem(lines, i + 1);
                if (next < 0) {
                    break;
                }
                i = next;
                continue;
            } else if (!rows.isEmpty() && (raw.startsWith("  ") || raw.startsWith("\t"))
                    && !trimmed.startsWith("```")) {
                // Indented continuation of the previous item stays in the same row.
                // 缩进的续行归并到上一条，避免每行都变一个新项目。
                String[] last = rows.get(rows.size() - 1);
                last[2] = last[2] + " " + trimmed;
            } else {
                break;
            }
            i++;
        }

        // Column widths in Swing tables are shared by every row, so nesting is expressed as
        // leading padding inside the marker cell; wrapped text then starts at one common x.
        // Swing 表格的列宽是整表共享的，所以层级靠符号前的空白表现，正文始终从同一列开始。
        out.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"2\" border=\"0\">");
        for (String[] row : rows) {
            int level = Integer.parseInt(row[1]);
            String marker = "&nbsp;".repeat(level * 3) + row[0] + "&nbsp;";
            out.append("<tr><td valign=\"top\" align=\"left\" nowrap>").append(marker)
                    .append("</td><td valign=\"top\">").append(inline(row[2], p))
                    .append("</td></tr>");
        }
        out.append("</table><br>");
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

    private static String quoteBlock(String text, Palette p) {
        return "<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tr>"
                + "<td width=\"3\" bgcolor=\"" + p.quoteBar + "\"></td>"
                + "<td style=\"padding-left:8px;color:" + p.muted + "\">"
                + inline(text, p) + "</td></tr></table>";
    }

    private static String codeBlock(String code, String lang, Palette p) {
        String caption = lang.isEmpty() ? "" : "<div style=\"color:" + p.muted
                + ";font-size:" + (p.codeSize - 1) + "px;margin-bottom:3px\">"
                + escape(lang) + "</div>";
        return "<table width=\"100%\" cellspacing=\"0\" cellpadding=\"6\" border=\"0\" bgcolor=\""
                + p.codeBlockBg + "\"><tr><td>" + caption
                + "<pre style=\"margin:0\">" + escape(stripTrailingNewline(code))
                + "</pre></td></tr></table>" + "<br>";
    }

    private static int renderTable(String[] lines, int start, StringBuilder out, Palette p) {
        List<String> header = splitRow(lines[start]);
        List<List<String>> rows = new ArrayList<>();
        int i = start + 2;
        while (i < lines.length && isTableRow(lines[i].trim())) {
            rows.add(splitRow(lines[i]));
            i++;
        }
        StringBuilder table = new StringBuilder();
        table.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\" bordercolor=\"")
                .append(p.border).append("\"><tr bgcolor=\"").append(p.codeBlockBg).append("\">");
        for (String cell : header) {
            table.append("<td><b>").append(inline(cell, p)).append("</b></td>");
        }
        table.append("</tr>");
        for (List<String> row : rows) {
            table.append("<tr>");
            for (int column = 0; column < header.size(); column++) {
                String cell = column < row.size() ? row.get(column) : "";
                table.append("<td>").append(inline(cell, p)).append("</td>");
            }
            table.append("</tr>");
        }
        table.append("</table><br>");
        out.append(table);
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

    private static String orderedPrefix(String raw) {
        Matcher m = Pattern.compile("^\\s*(\\d+)[.)]").matcher(raw);
        return m.find() ? m.group(1) + "." : "-";
    }

    // Inline spans: escape first, protect code spans, then apply emphasis patterns.
    private static String inline(String text, Palette p) {
        String s = escape(text);
        List<String> codes = new ArrayList<>();
        Matcher code = INLINE_CODE.matcher(s);
        StringBuffer buffer = new StringBuffer();
        while (code.find()) {
            codes.add(code.group(1));
            code.appendReplacement(buffer, Matcher.quoteReplacement(
                    "\u0001" + (codes.size() - 1) + "\u0001"));
        }
        code.appendTail(buffer);
        s = buffer.toString();

        s = LINK.matcher(s).replaceAll("<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("(?<![\\w])__(?=\\S)(.+?)(?<=\\S)__(?![\\w])", "<b>$1</b>");
        s = s.replaceAll("(?<!\\*)\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("(?<![\\w])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w])", "<i>$1</i>");
        s = s.replaceAll("~~(?=\\S)(.+?)(?<=\\S)~~", "<strike>$1</strike>");

        for (int i = 0; i < codes.size(); i++) {
            s = s.replace("\u0001" + i + "\u0001",
                    "<code style=\"background-color:" + p.inlineCodeBg + ";color:" + p.codeText
                            + "\">&nbsp;" + codes.get(i) + "&nbsp;</code>");
        }
        return s;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stripTrailingNewline(String code) {
        return code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
    }

    // Theme-aware colours resolved on the EDT so dark/light switching keeps working.
    // 每次渲染读取当前主题色，明暗主题切换后下一次刷新即生效。
    private static final class Palette {
        final String text;
        final String muted;
        final String link;
        final String border;
        final String quoteBar;
        final String codeText;
        final String codeBlockBg;
        final String inlineCodeBg;
        final String font;
        final String mono = "Monospaced";
        final int size;
        final int codeSize;
        final int paragraphGap = 8;

        private Palette(Color text, Color muted, Color link, Color border, Color codeBg) {
            this.text = hex(text);
            this.muted = hex(muted);
            this.link = hex(link);
            this.border = hex(border);
            this.quoteBar = hex(border);
            this.codeText = hex(text);
            this.codeBlockBg = hex(codeBg);
            this.inlineCodeBg = hex(codeBg);
            Font base = UIUtil.getLabelFont();
            this.font = quoteFamily(base != null ? base.getFamily() : "Dialog");
            this.size = base != null ? base.getSize() : 13;
            this.codeSize = Math.max(11, this.size - 1);
        }

        static Palette current() {
            Color text = UIUtil.getLabelForeground();
            Color muted = JBColor.namedColor("Component.infoForeground", new Color(0x8C8C8C));
            Color link = JBColor.namedColor("Link.activeForeground", new Color(0x2470B3));
            Color border = JBColor.namedColor("Borders.color", new Color(0xD1D1D1));
            Color codeBg = JBColor.namedColor("EditorPane.background", new Color(0xF2F3F5));
            return new Palette(text, muted, link, border, codeBg);
        }

        private static String quoteFamily(String family) {
            return "'" + family.replace("'", "") + "'";
        }

        private static String hex(Color color) {
            return String.format("#%06X", color.getRGB() & 0xFFFFFF);
        }
    }
}
