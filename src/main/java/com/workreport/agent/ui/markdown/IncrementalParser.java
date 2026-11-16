package com.workreport.agent.ui.markdown;

import java.util.ArrayList;
import java.util.List;

// Streaming-friendly Markdown parser.
// Only the tail of the document can still change while tokens arrive, so feed() re-parses from the
// last block's source offset and reuses everything before it. When the freshly parsed tail would
// merge into the block above (a trailing "- " turning a paragraph into a list item, for example),
// the window backs off one block at a time instead of silently producing two half blocks.
// 流式 Markdown 解析器：追加 token 时只有文档尾部还可能变化，因此 feed() 从末块的源偏移重新解析，
// 之前的块原样复用。若新解析出的尾块会与上方块合并（例如末尾的 “- ” 把段落变成列表项），
// 窗口就逐块向前回退，避免产出两个半截块。
// 每个 token 的代价仍远低于全文，其余块保持对象身份，视图无需重建。
public final class IncrementalParser {

    // A single merge can only reach the immediately preceding block, but a couple of steps of
    // slack keeps the invariant cheap to reason about.
    // 合并最多影响紧邻的上一块，留几步余量让不变式更容易推理。
    private static final int MAX_BACKOFF = 3;

    private String text = "";
    private List<MarkdownBlock> blocks = List.of();
    private List<Integer> offsets = List.of();

    public List<MarkdownBlock> blocks() {
        return blocks;
    }

    public String text() {
        return text;
    }

    // Feed the full text accumulated so far; returns the (possibly identical) block list.
    // 输入目前累计的完整文本，返回块列表（未变化时是同一个实例）。
    public List<MarkdownBlock> feed(String newText) {
        String next = newText == null ? "" : newText;
        if (next.equals(text)) {
            return blocks;
        }
        // A non-append change (history replay, clear, edit) has to start over.
        // 非追加式变化（历史回放、清空、编辑）只能整体重解析。
        if (!next.startsWith(text) || text.isEmpty()) {
            adopt(next, MarkdownParser.parseWithOffsets(next));
            return blocks;
        }

        int keep = blocks.size() - 1;
        MarkdownParser.Parsed tail;
        int from;
        for (int attempt = 0; ; attempt++) {
            from = keep <= 0 ? 0 : offsets.get(keep);
            tail = MarkdownParser.parseWithOffsets(next.substring(from));
            boolean backOff = attempt < MAX_BACKOFF && keep > 0 && !tail.blocks().isEmpty()
                    && mergeable(blocks.get(keep - 1), tail.blocks().get(0));
            if (!backOff) {
                break;
            }
            keep--;
        }

        List<MarkdownBlock> combined = new ArrayList<>(keep + tail.blocks().size());
        List<Integer> combinedOffsets = new ArrayList<>(keep + tail.offsets().size());
        combined.addAll(blocks.subList(0, keep));
        combinedOffsets.addAll(offsets.subList(0, keep));
        combined.addAll(tail.blocks());
        for (int offset : tail.offsets()) {
            combinedOffsets.add(from + offset);
        }
        text = next;
        blocks = List.copyOf(combined);
        offsets = List.copyOf(combinedOffsets);
        return blocks;
    }

    private void adopt(String next, MarkdownParser.Parsed parsed) {
        text = next;
        blocks = parsed.blocks();
        offsets = parsed.offsets();
    }

    // Two adjacent blocks of these kinds would have been parsed as one, so the boundary between
    // them is not a safe place to stop reusing the old prefix.
    // 这些相邻块本应被解析成一块，因此它们的边界不能作为复用前缀的安全断点。
    private static boolean mergeable(MarkdownBlock previous, MarkdownBlock next) {
        if (previous instanceof MarkdownBlock.ListBlock && next instanceof MarkdownBlock.ListBlock) {
            return true;
        }
        // A half-typed table row is a paragraph until its closing pipe arrives; then the table
        // above swallows it.
        // 尚未打完的表格行先是段落，闭合的竖线到达后才被上方的表格吃掉。
        return previous instanceof MarkdownBlock.Table
                && (next instanceof MarkdownBlock.Paragraph || next instanceof MarkdownBlock.Table);
    }

    public void reset() {
        text = "";
        blocks = List.of();
        offsets = List.of();
    }
}
