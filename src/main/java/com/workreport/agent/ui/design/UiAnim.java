package com.workreport.agent.ui.design;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

// One shared timer for every micro-animation in the panel.
// Running all tweens through a single 15 ms timer keeps the EDT cost flat instead of adding
// one timer per component, and the timer stops by itself when nothing is animating.
// 面板内所有微动效共用一个 15 ms 定时器：不按组件开 timer，空闲时自动停止，EDT 开销恒定。
public final class UiAnim {

    private UiAnim() {
    }

    private static final int FRAME_MS = 15;

    private static final class Anim {
        private final long startNanos;
        private final int durationMs;
        private final Consumer<Float> step;

        private Anim(int durationMs, Consumer<Float> step) {
            this.startNanos = System.nanoTime();
            this.durationMs = Math.max(1, durationMs);
            this.step = step;
        }
    }

    private static final List<Anim> ACTIVE = new ArrayList<>();
    private static Timer timer;

    // Animate a 0..1 progress value with ease-out easing. Steps run on the EDT.
    // 以 ease-out 曲线驱动 0~1 的进度回调，回调在 EDT 上执行。
    public static void animate(int durationMs, Consumer<Float> step) {
        if (step == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> animate(durationMs, step));
            return;
        }
        ACTIVE.add(new Anim(durationMs, step));
        if (timer == null) {
            timer = new Timer(FRAME_MS, e -> tick());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    // Convenience for hover fades: `target` 0 or 1, and the setter receives the blended value.
    // 悬停渐变的便捷入口：target 为 0 或 1，setter 收到中间值。
    public static void fade(float from, float target, int durationMs, Consumer<Float> setter) {
        if (setter == null) {
            return;
        }
        animate(durationMs, progress -> setter.accept(from + (target - from) * progress));
    }

    public static void cancelAll() {
        ACTIVE.clear();
        if (timer != null) {
            timer.stop();
        }
    }

    private static void tick() {
        long now = System.nanoTime();
        Iterator<Anim> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Anim anim = it.next();
            float progress = (now - anim.startNanos) / (anim.durationMs * 1_000_000f);
            boolean done = progress >= 1f;
            float eased = easeOut(Math.min(1f, progress));
            try {
                anim.step.accept(eased);
            } catch (RuntimeException ignored) {
                // A disposed component must never take the whole animation loop down.
                // 组件已销毁时不能把整个动画循环带崩。
                done = true;
            }
            if (done) {
                it.remove();
            }
        }
        if (ACTIVE.isEmpty() && timer != null) {
            timer.stop();
        }
    }

    private static float easeOut(float t) {
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }
}
