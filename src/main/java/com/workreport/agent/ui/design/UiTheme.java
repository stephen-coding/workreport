package com.workreport.agent.ui.design;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;

// Design tokens for the agent panel.
// Colours are JBColor constants, so a theme switch resolves the next time they are painted;
// fonts are cached and rebuilt automatically when the base font changes.
// Agent 面板的设计令牌：颜色全部走 JBColor（绘制时解析，明暗主题即时生效）；
// 字体按 LAF 缓存，基础字体一变就自动重建，无需调用方手动刷新。
public final class UiTheme {

    private UiTheme() {
    }

    // ---------------------------------------------------------------- colours

    // Surfaces: panel background comes from the IDE, cards and chips are slightly raised.
    // 面板底色沿用 IDE；卡片与 chip 略微抬高。
    public static final Color RAISED = new JBColor(new Color(0xFFFFFF), new Color(0x2B2D30));
    public static final Color SUBTLE = new JBColor(new Color(0xF4F6F9), new Color(0x313438));
    public static final Color BORDER = JBColor.namedColor("Borders.color",
            new JBColor(new Color(0xDBDFE6), new Color(0x3A3D41)));
    public static final Color ACCENT = JBColor.namedColor("Link.activeForeground",
            new JBColor(new Color(0x3574F0), new Color(0x4B8BFF)));
    public static final Color ACCENT_SOFT = new JBColor(new Color(53, 116, 240, 30),
            new Color(75, 139, 255, 48));
    public static final Color HOVER = new JBColor(new Color(0xEAF0FA), new Color(0x34383D));
    public static final Color ERROR = JBColor.namedColor("Notifications.errorForeground",
            new JBColor(new Color(0xC0392B), new Color(0xF08A8A)));
    public static final Color ERROR_SOFT = new JBColor(new Color(0xFDECEC), new Color(0x3A2626));
    public static final Color OK = JBColor.namedColor("Notifications.successForeground",
            new JBColor(new Color(0x2E7D32), new Color(0x73B27A)));
    public static final Color WARN = JBColor.namedColor("Notifications.warningForeground",
            new JBColor(new Color(0xB26B00), new Color(0xD9A343)));

    // Panel background / foreground resolve live, so dark mode and high contrast keep working.
    // 面板底色与正文色实时解析，深色与高对比主题都不会出现死色。
    public static Color surface() {
        Color color = UIUtil.getPanelBackground();
        return color != null ? color : new JLabel().getBackground();
    }

    public static Color text() {
        Color color = UIUtil.getLabelForeground();
        return color != null ? color : Color.BLACK;
    }

    public static Color muted() {
        Color color = UIUtil.getContextHelpForeground();
        return color != null ? color : new Color(0x8C8C8C);
    }

    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    // 12% / 18% accent used for user bubbles, selected pills and focus rings.
    // 用户气泡、选中 pill 与焦点环使用的半透明强调色。
    public static Color accentSoft(int alpha) {
        return withAlpha(ACCENT, alpha);
    }

    // ---------------------------------------------------------------- fonts

    private static volatile Font cachedBase;
    private static volatile Font cachedSmall;
    private static volatile Font cachedBold;
    private static volatile Font cachedMono;
    private static volatile Font cachedH1;
    private static volatile Font cachedH2;
    private static volatile Font cachedH3;
    // The live font the caches were derived from; a Look-and-Feel switch changes it and the
    // caches are rebuilt on the next call.
    // 缓存对应的原始字体；换 LAF 后它会变，下次取用时自动重建。
    private static volatile Font source;
    private static boolean listenerInstalled;

    private static void sync() {
        Font live = UIUtil.getLabelFont();
        if (live == null) {
            live = new JLabel().getFont();
        }
        if (!live.equals(source)) {
            source = live;
            refresh();
        }
    }

    public static Font font() {
        sync();
        Font font = cachedBase;
        if (font == null) {
            font = source;
            cachedBase = font;
        }
        return font;
    }

    public static Font small() {
        sync();
        Font font = cachedSmall;
        if (font == null) {
            font = font().deriveFont(font().getSize2D() - 1f);
            cachedSmall = font;
        }
        return font;
    }

    public static Font bold() {
        sync();
        Font font = cachedBold;
        if (font == null) {
            font = font().deriveFont(Font.BOLD);
            cachedBold = font;
        }
        return font;
    }

    public static Font mono() {
        sync();
        Font font = cachedMono;
        if (font == null) {
            font = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(11, font().getSize() - 1));
            cachedMono = font;
        }
        return font;
    }

    public static Font h1() {
        sync();
        Font font = cachedH1;
        if (font == null) {
            font = font().deriveFont(Font.BOLD, font().getSize2D() + 4f);
            cachedH1 = font;
        }
        return font;
    }

    public static Font h2() {
        sync();
        Font font = cachedH2;
        if (font == null) {
            font = font().deriveFont(Font.BOLD, font().getSize2D() + 2f);
            cachedH2 = font;
        }
        return font;
    }

    public static Font h3() {
        sync();
        Font font = cachedH3;
        if (font == null) {
            font = font().deriveFont(Font.BOLD);
            cachedH3 = font;
        }
        return font;
    }

    // Drop the cached fonts; the next call re-derives them from the live base font.
    // 丢弃字体缓存，下次取用时从当前基础字体重新派生。
    public static void refresh() {
        cachedBase = null;
        cachedSmall = null;
        cachedBold = null;
        cachedMono = null;
        cachedH1 = null;
        cachedH2 = null;
        cachedH3 = null;
    }

    // Repaint every window after a Look-and-Feel change so already-painted rows pick up the new
    // colours. Uses the plain Swing property listener, so no platform-impl class is needed.
    // 换 LAF 后重绘所有窗口，让已绘制过的行拿到新配色；只依赖 Swing 的属性监听。
    public static void install() {
        if (listenerInstalled) {
            return;
        }
        listenerInstalled = true;
        UIManager.addPropertyChangeListener(event -> {
            if (!"lookAndFeel".equals(event.getPropertyName())) {
                return;
            }
            refresh();
            for (Window window : Window.getWindows()) {
                window.repaint();
            }
        });
    }
}
