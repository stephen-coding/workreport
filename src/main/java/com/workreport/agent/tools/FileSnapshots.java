package com.workreport.agent.tools;

import java.nio.file.Path;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

// In-memory snapshots of file contents taken before every edit, so the agent
// (or the user) can undo the last change with revert_file.
// 每次编辑前保存文件内容快照，支持 revert_file 撤销最近一次修改。
public final class FileSnapshots {

    private static final int MAX_PER_FILE = 5;

    private final Map<String, Deque<String>> snapshots = new ConcurrentHashMap<>();

    public void capture(Path path, String content) {
        Deque<String> stack = snapshots.computeIfAbsent(key(path), k -> new ConcurrentLinkedDeque<>());
        stack.push(content == null ? "" : content);
        while (stack.size() > MAX_PER_FILE) {
            stack.pollLast();
        }
    }

    public boolean has(Path path) {
        Deque<String> stack = snapshots.get(key(path));
        return stack != null && !stack.isEmpty();
    }

    // Removes and returns the most recent snapshot, or null when there is none.
    // 取出并移除最近一份快照；不存在时返回 null。
    public String pop(Path path) {
        Deque<String> stack = snapshots.get(key(path));
        return stack == null ? null : stack.poll();
    }

    public void clear(Path path) {
        snapshots.remove(key(path));
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
