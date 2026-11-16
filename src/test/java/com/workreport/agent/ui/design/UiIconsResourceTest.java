package com.workreport.agent.ui.design;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

// Every glyph the tool window asks UiIcons.icon(...) for must ship as an SVG plus its _dark variant.
// A missing file degrades to an empty icon at runtime, which is exactly how the "new session"
// button (tab strip, top right) once ended up invisible.
// 工具窗口通过 UiIcons.icon(...) 取的图标必须随包提供 SVG 与 _dark 变体：
// 文件缺失时运行期会静默退化成空图标，「新建会话」按钮曾因此整块不可见。
class UiIconsResourceTest {

    // Keep in sync with UiIcons.icon("...") / iconButton("...") call sites.
    // 与 UiIcons.icon("...") / iconButton("...") 的调用点保持一致。
    private static final List<String> REFERENCED = List.of(
            "agent", "attach", "history", "memory", "add", "settings");

    @Test
    void everyReferencedIconShipsWithADarkVariant() {
        for (String name : REFERENCED) {
            assertNotNull(stream("/icons/" + name + ".svg"),
                    "missing /icons/" + name + ".svg");
            assertNotNull(stream("/icons/" + name + "_dark.svg"),
                    "missing /icons/" + name + "_dark.svg");
        }
    }

    private static InputStream stream(String path) {
        return UiIconsResourceTest.class.getResourceAsStream(path);
    }
}
