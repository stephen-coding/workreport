package com.workreport.agent.tools;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.locks.ReentrantLock;

// Project-wide gate that serializes file-mutating tools across parallel chat sessions.
// Two agents writing the same project at once would overwrite each other, and several diff
// dialogs at the same time are impossible to attribute — so writes take turns while reads
// and searches stay fully parallel.
// 项目级写闸门：并行会话的「写文件」工具排队执行。
// 两个 Agent 同时改同一项目会互相覆盖，多个 diff 弹窗也无法分辨来源；
// 因此写操作轮流来，读文件/搜索仍然完全并行。
@Service(Service.Level.PROJECT)
public final class ProjectWriteGate {

    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile String owner;
    private volatile int waiting;

    public static ProjectWriteGate getInstance(@NotNull Project project) {
        return project.getService(ProjectWriteGate.class);
    }

    // Blocks until the caller holds the gate. Returns the previous owner when we had to wait
    // (null when the gate was free).
    // 阻塞直到拿到写锁；若需要等待，返回上一个持有者的名字（空闲时返回 null）。
    public @Nullable String acquire(@NotNull String who) {
        if (lock.tryLock()) {
            owner = who;
            return null;
        }
        String previous = owner;
        waiting++;
        try {
            lock.lock();
        } finally {
            waiting--;
        }
        owner = who;
        return previous == null ? "另一个会话" : previous;
    }

    public void release() {
        owner = null;
        lock.unlock();
    }

    public @Nullable String owner() {
        return owner;
    }

    public int waiting() {
        return waiting;
    }
}
