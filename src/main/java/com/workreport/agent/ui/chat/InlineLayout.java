package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

// Wraps and paints a list of inline spans with java.awt.font.TextLayout.
// TextLayout gives us real line breaking for mixed Latin/CJK text, per-span fonts and colours,
// underline for links and hit testing for hover/click, without the Swing HTML engine in the way.
// 用 TextLayout 排布行内 span：中英混排换行、每段字体与颜色、链接下划线、点击命中判定，
// 全部由 Java2D 完成，不再经过 Swing 的 HTML 引擎。
public final class InlineLayout {

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private final List<InlineSpan> spans;
    private final Font font;
    private final boolean muted;

    private AttributedString attributed;
    private int[] spanEnds;
    private String plain = "";

    private final List<TextLayout> lines = new ArrayList<>();
    private final List<Integer> lineStarts = new ArrayList<>();
    private final List<Float> lineTops = new ArrayList<>();
    private int measuredWidth = -1;
    private int height;
    private int lineGap = 2;

    private Font builtFont;
    private Color builtText;
    private Color builtMuted;
    // Optional live colour override: user bubbles paint light text on the accent fill, and the
    // supplier keeps that decision theme-aware instead of freezing a colour at construction.
    // 可选的实时文字色：用户气泡在强调色底上写字，用 supplier 保证换主题后颜色跟着变。
    private java.util.function.Supplier<Color> colorOverride;

    public InlineLayout(List<InlineSpan> spans, Font font, boolean muted) {
        this.spans = spans == null ? List.of() : spans;
        this.font = font;
        this.muted = muted;
    }

    public void setColorOverride(java.util.function.Supplier<Color> supplier) {
        colorOverride = supplier;
        builtText = null;
        measuredWidth = -1;
    }

    public String plainText() {
        ensureAttributed();
        return plain;
    }

    // Returns the height at this width; results are cached until the width or the theme changes.
    // 返回该宽度下的高度；宽度或主题变化前结果一直有效。
    public int measure(int availableWidth) {
        ensureAttributed();
        int width = Math.max(1, availableWidth);
        if (width == measuredWidth) {
            return height;
        }
        measuredWidth = width;
        lines.clear();
        lineStarts.clear();
        lineTops.clear();

        AttributedCharacterIterator iterator = attributed.getIterator();
        LineBreakMeasurer measurer = new LineBreakMeasurer(iterator, FRC);
        float y = 0f;
        int position = 0;
        int end = iterator.getEndIndex();
        while (position < end) {
            TextLayout layout = measurer.nextLayout(width);
            if (layout == null) {
                break;
            }
            lines.add(layout);
            lineStarts.add(position);
            lineTops.add(y);
            y += layout.getAscent() + layout.getDescent() + layout.getLeading() + lineGap;
            int next = measurer.getPosition();
            if (next <= position) {
                break;
            }
            position = next;
        }
        height = lines.isEmpty() ? 0 : Math.max(1, Math.round(y - lineGap));
        return height;
    }

    public int height() {
        return height;
    }

    // Widest line after measuring; bubbles use it to shrink to their content instead of always
    // taking the full row width.
    // 测量后最宽一行的宽度；气泡据此自适应宽度，而不是永远占满整行。
    public int maxAdvance() {
        int max = 0;
        for (TextLayout layout : lines) {
            max = Math.max(max, Math.round(layout.getAdvance()));
        }
        return max;
    }

    public int lineCount() {
        return lines.size();
    }

    // Draw with the top-left corner at (x, y); returns the y coordinate below the last line.
    // 以 (x, y) 为左上角绘制，返回最后一行下方的 y。
    public int paint(Graphics2D g2, int x, int y) {
        for (int i = 0; i < lines.size(); i++) {
            TextLayout layout = lines.get(i);
            float baseline = y + lineTops.get(i) + layout.getAscent();
            layout.draw(g2, x, baseline);
        }
        return y + Math.max(height, 1);
    }

    // Blinking caret right after the last character of the last line; used while a message is
    // still streaming, so the user sees where the writer is.
    // 在最后一行末绘制闪烁光标，流式输出时提示“正在这里继续写”。
    public void paintCaret(Graphics2D g2, int x, int y, Color color) {
        if (lines.isEmpty()) {
            return;
        }
        TextLayout last = lines.get(lines.size() - 1);
        float top = y + lineTops.get(lineTops.size() - 1) + last.getLeading() / 2f;
        float caretX = x + last.getAdvance() + 1f;
        float caretHeight = Math.max(2f, last.getAscent() + last.getDescent());
        Graphics2D caret = (Graphics2D) g2.create();
        caret.setColor(color);
        caret.fill(new java.awt.geom.Rectangle2D.Float(caretX, top, 2f, caretHeight));
        caret.dispose();
    }

    // Link href under the given local point, or null.
    // 该局部坐标下的链接地址；没有则返回 null。
    public String linkAt(int x, int y) {
        int index = characterIndexAt(x, y);
        if (index < 0) {
            return null;
        }
        int spanIndex = spanIndexAt(index);
        if (spanIndex < 0) {
            return null;
        }
        InlineSpan span = spans.get(spanIndex);
        return span.isLink() ? span.href : null;
    }

    private int characterIndexAt(int x, int y) {
        for (int i = 0; i < lines.size(); i++) {
            float top = lineTops.get(i);
            TextLayout layout = lines.get(i);
            float lineHeight = layout.getAscent() + layout.getDescent() + layout.getLeading();
            if (y < top || y > top + lineHeight) {
                continue;
            }
            TextHitInfo hit = layout.hitTestChar(x, y);
            if (hit == null) {
                return -1;
            }
            return lineStarts.get(i) + hit.getInsertionIndex();
        }
        return -1;
    }

    private int spanIndexAt(int characterIndex) {
        if (spanEnds == null) {
            return -1;
        }
        for (int i = 0; i < spanEnds.length; i++) {
            if (characterIndex < spanEnds[i]) {
                return i;
            }
        }
        return -1;
    }

    // Rebuild the attributed text when the theme (fonts/colours) moved on.
    // 主题（字体/颜色）变化时重建属性文本。
    private void ensureAttributed() {
        Color textColor = colorOverride != null
                ? colorOverride.get()
                : (muted ? UiTheme.muted() : UiTheme.text());
        Color linkColor = UiTheme.ACCENT;
        if (attributed != null && builtFont == font
                && builtText != null && builtText.equals(textColor)
                && builtMuted != null && builtMuted.equals(linkColor)) {
            return;
        }
        builtFont = font;
        builtText = textColor;
        builtMuted = linkColor;

        StringBuilder builder = new StringBuilder();
        for (InlineSpan span : spans) {
            builder.append(span.text);
        }
        plain = builder.toString();
        if (plain.isEmpty()) {
            attributed = new AttributedString(" ");
            spanEnds = new int[0];
            measuredWidth = -1;
            return;
        }

        AttributedString result = new AttributedString(plain);
        result.addAttribute(TextAttribute.FONT, font);
        result.addAttribute(TextAttribute.FOREGROUND, textColor);
        spanEnds = new int[spans.size()];
        int offset = 0;
        for (int i = 0; i < spans.size(); i++) {
            InlineSpan span = spans.get(i);
            int end = offset + span.text.length();
            spanEnds[i] = end;
            if (end > offset) {
                if (span.code) {
                    result.addAttribute(TextAttribute.FONT, UiTheme.mono(), offset, end);
                } else if (span.bold && span.italic) {
                    result.addAttribute(TextAttribute.FONT,
                            font.deriveFont(Font.BOLD | Font.ITALIC), offset, end);
                } else if (span.bold) {
                    result.addAttribute(TextAttribute.FONT, font.deriveFont(Font.BOLD), offset, end);
                } else if (span.italic) {
                    result.addAttribute(TextAttribute.FONT, font.deriveFont(Font.ITALIC), offset, end);
                }
                if (span.strike) {
                    result.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON,
                            offset, end);
                }
                if (span.isLink()) {
                    result.addAttribute(TextAttribute.FOREGROUND, linkColor, offset, end);
                    result.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON,
                            offset, end);
                }
            }
            offset = end;
        }
        attributed = result;
        measuredWidth = -1;
    }

    public boolean isEmpty() {
        return plainText().isEmpty();
    }
}
