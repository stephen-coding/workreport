package com.workreport.agent.ui.design;

import com.intellij.icons.AllIcons;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.EmptyIcon;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Icon registry plus the handful of glyphs that are drawn instead of shipped as SVG.
// Loading goes through IconLoader so IntelliJ picks the `_dark` variant in dark themes by itself.
// 图标注册表 + 少量直接绘制的字形。
// 走 IconLoader 加载，深色主题由平台自动选中同名 `_dark` 变体。
public final class UiIcons {

    private UiIcons() {
    }

    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    // Load /icons/<name>.svg from the plugin resources; a missing file degrades to an empty icon
    // instead of throwing on the EDT.
    // 从插件资源加载 /icons/<name>.svg；文件缺失时退化为空图标，绝不在 EDT 上抛异常。
    public static @NotNull Icon icon(@NotNull String name) {
        return CACHE.computeIfAbsent(name, key -> {
            try {
                return com.intellij.openapi.util.IconLoader.getIcon("/icons/" + key + ".svg", UiIcons.class);
            } catch (Exception e) {
                return EmptyIcon.create(16);
            }
        });
    }

    public static @NotNull Icon scaled(@NotNull String name, int size) {
        Icon icon = icon(name);
        return icon.getIconWidth() == size ? icon : IconUtil.scale(icon, null, size / (float) icon.getIconWidth());
    }

    public static @NotNull Icon settings() {
        return AllIcons.General.Settings;
    }

    public static @NotNull Icon add() {
        return AllIcons.General.Add;
    }

    public static @NotNull Icon copy() {
        return AllIcons.Actions.Copy;
    }

    public static @NotNull Icon retry() {
        return AllIcons.Actions.Restart;
    }

    public static @NotNull Icon trash() {
        return AllIcons.Actions.GC;
    }

    public static @NotNull Icon search() {
        return AllIcons.Actions.Find;
    }

    // ---------------------------------------------------------------- glyphs

    // Upward arrow inside the send button.
    // 发送按钮里的向上箭头。
    public static void paintUpArrow(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float cx = box.x + box.width / 2f;
        float top = box.y + box.height * 0.24f;
        float bottom = box.y + box.height * 0.76f;
        float half = box.width * 0.20f;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(cx, top);
        path.lineTo(cx + half, top + half);
        path.moveTo(cx, top);
        path.lineTo(cx - half, top + half);
        path.moveTo(cx, top);
        path.lineTo(cx, bottom);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(Math.max(1.4f, box.width * 0.09f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g2.draw(path);
        g2.dispose();
    }

    // Rounded square for the stop button.
    // 停止按钮的圆角方块。
    public static void paintStop(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int size = Math.round(Math.min(box.width, box.height) * 0.42f);
        int x = box.x + (box.width - size) / 2;
        int y = box.y + (box.height - size) / 2;
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Float(x, y, size, size, size * 0.3f, size * 0.3f));
        g2.dispose();
    }

    // Diagonal cross used for close buttons and removable chips.
    // 关闭按钮与可移除 chip 使用的叉。
    public static void paintClose(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float inset = Math.max(2f, box.width * 0.30f);
        float x1 = box.x + inset;
        float y1 = box.y + inset;
        float x2 = box.x + box.width - inset;
        float y2 = box.y + box.height - inset;
        g2.setColor(color);
        g2.setStroke(new BasicStroke(Math.max(1.2f, box.width * 0.10f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g2.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
        g2.drawLine(Math.round(x2), Math.round(y1), Math.round(x1), Math.round(y2));
        g2.dispose();
    }

    // Chevron pointing down (open) or right (collapsed).
    // 向下的折角图标：展开朝下、折叠朝右。
    public static void paintChevron(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color,
                                    boolean down) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float cx = box.x + box.width / 2f;
        float cy = box.y + box.height / 2f;
        float half = box.width * 0.22f;
        Path2D.Float path = new Path2D.Float();
        if (down) {
            path.moveTo(cx - half, cy - half * 0.5f);
            path.lineTo(cx, cy + half * 0.5f);
            path.lineTo(cx + half, cy - half * 0.5f);
        } else {
            path.moveTo(cx - half * 0.5f, cy - half);
            path.lineTo(cx + half * 0.5f, cy);
            path.lineTo(cx - half * 0.5f, cy + half);
        }
        g2.setColor(color);
        g2.setStroke(new BasicStroke(Math.max(1.3f, box.width * 0.09f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g2.draw(path);
        g2.dispose();
    }

    // Tick mark for finished checklist rows.
    // 清单完成项的勾。
    public static void paintCheck(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float x = box.x + box.width * 0.22f;
        float y = box.y + box.height * 0.52f;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x, y);
        path.lineTo(x + box.width * 0.20f, y + box.height * 0.22f);
        path.lineTo(x + box.width * 0.56f, y - box.height * 0.22f);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(Math.max(1.4f, box.width * 0.12f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g2.draw(path);
        g2.dispose();
    }

    // Paperclip-ish clip glyph for attachment chips (drawn, no resource needed).
    // 附件 chip 用的回形针字形（直接绘制，无需资源文件）。
    public static void paintPaperclip(@NotNull Graphics2D g, @NotNull Rectangle box, @NotNull Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(Math.max(1.1f, box.width * 0.10f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        int w = Math.round(box.width * 0.44f);
        int h = Math.round(box.height * 0.62f);
        int x = box.x + (box.width - w) / 2;
        int y = box.y + (box.height - h) / 2;
        g2.drawArc(x, y, w, h, 40, 280);
        g2.drawLine(x + w / 2, y + h / 5, x + w / 2, y + h * 4 / 5);
        g2.dispose();
    }
}
