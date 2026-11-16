package com.workreport.agent.ui.markdown;

// One run of inline text with a single style combination.
// Immutable and shared: the incremental parser reuses spans for blocks it did not re-parse.
// 一段同格式的行内文本。不可变对象，增量解析时未重新解析的块会原样复用这些 span。
public final class InlineSpan {

    public final String text;
    public final boolean bold;
    public final boolean italic;
    public final boolean strike;
    public final boolean code;
    // Non-null for [text](url) links.
    // 链接（[text](url)）的地址，非链接为 null。
    public final String href;

    private InlineSpan(String text, boolean bold, boolean italic, boolean strike, boolean code,
                       String href) {
        this.text = text == null ? "" : text;
        this.bold = bold;
        this.italic = italic;
        this.strike = strike;
        this.code = code;
        this.href = href;
    }

    public static InlineSpan plain(String text) {
        return new InlineSpan(text, false, false, false, false, null);
    }

    public static InlineSpan of(String text, boolean bold, boolean italic, boolean strike,
                                boolean code, String href) {
        return new InlineSpan(text, bold, italic, strike, code, href);
    }

    public static InlineSpan code(String text) {
        return new InlineSpan(text, false, false, false, true, null);
    }

    public static InlineSpan link(String text, String href) {
        return new InlineSpan(text, false, false, false, false, href);
    }

    public InlineSpan withBold() {
        return new InlineSpan(text, true, italic, strike, code, href);
    }

    public InlineSpan withItalic() {
        return new InlineSpan(text, bold, true, strike, code, href);
    }

    public InlineSpan withStrike() {
        return new InlineSpan(text, bold, italic, true, code, href);
    }

    public boolean isLink() {
        return href != null && !href.isEmpty();
    }

    public boolean hasSameStyle(InlineSpan other) {
        return other != null
                && bold == other.bold
                && italic == other.italic
                && strike == other.strike
                && code == other.code
                && java.util.Objects.equals(href, other.href);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InlineSpan span)) {
            return false;
        }
        return bold == span.bold && italic == span.italic && strike == span.strike
                && code == span.code && text.equals(span.text)
                && java.util.Objects.equals(href, span.href);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(text, bold, italic, strike, code, href);
    }

    @Override
    public String toString() {
        return "InlineSpan[" + text + (bold ? ",b" : "") + (italic ? ",i" : "")
                + (strike ? ",s" : "") + (code ? ",code" : "")
                + (isLink() ? "," + href : "") + "]";
    }
}
