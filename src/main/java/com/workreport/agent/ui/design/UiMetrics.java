package com.workreport.agent.ui.design;

import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

// Spacing, radius and line-height scale for the panel.
// Everything is scaled through JBUI so 200% displays keep the same visual rhythm.
// 间距、圆角与行高的尺寸表；全部经 JBUI 缩放，200% 缩放下比例不变。
public final class UiMetrics {

    private UiMetrics() {
    }

    // Raw scale helper for one-off pixel values.
    // 一次性像素值的直接缩放入口。
    public static int scale(int value) {
        return JBUI.scale(value);
    }

    // 4 / 6 / 8 / 12 / 16 / 20 spacing ladder.
    // 4 / 6 / 8 / 12 / 16 / 20 间距阶。
    public static int xs() {
        return JBUI.scale(4);
    }

    public static int s() {
        return JBUI.scale(6);
    }

    public static int m() {
        return JBUI.scale(8);
    }

    public static int l() {
        return JBUI.scale(12);
    }

    public static int xl() {
        return JBUI.scale(16);
    }

    public static int xxl() {
        return JBUI.scale(20);
    }

    public static int radiusBubble() {
        return JBUI.scale(12);
    }

    public static int radiusCard() {
        return JBUI.scale(10);
    }

    public static int radiusChip() {
        return JBUI.scale(8);
    }

    public static int radiusInput() {
        return JBUI.scale(12);
    }

    public static int radiusButton() {
        return JBUI.scale(6);
    }

    // Line height of body text: a bit more air than the raw font metrics report, which keeps
    // long prose readable inside a dense tool window.
    // 正文行高：比原始字体度量多一点留白，密集工具窗口里长文本更易读。
    private static final JLabel SCRATCH = new JLabel();

    public static int lineHeight(Font font) {
        FontMetrics metrics = SCRATCH.getFontMetrics(font);
        if (metrics == null) {
            return JBUI.scale(20);
        }
        return Math.round(metrics.getHeight() * 1.15f);
    }

    public static FontMetrics metrics(Font font) {
        return SCRATCH.getFontMetrics(font);
    }

    public static int textWidth(String text, Font font) {
        FontMetrics metrics = SCRATCH.getFontMetrics(font);
        return metrics == null ? 0 : metrics.stringWidth(text);
    }
}
