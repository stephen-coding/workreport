package com.workreport.agent.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// JSON Lines RPC connection to the Agent Core (request/response + notifications).
// 与 Agent Core 的 JSON Lines RPC 连接（请求响应 + 通知）。
public final class JsonRpcConnection implements Disposable {

    private static final Logger LOG = Logger.getInstance(JsonRpcConnection.class);
    private static final Gson GSON = new Gson();

    private final CoreProcess process;
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonObject>> notificationHandlers = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;
    private Thread readerThread;

    public JsonRpcConnection(@NotNull CoreProcess process) {
        this.process = process;
    }

    public void addNotificationHandler(@NotNull Consumer<JsonObject> handler) {
        notificationHandlers.add(handler);
    }

    public void start() {
        readerThread = new Thread(this::readLoop, "agent-core-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getStdout(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonObject message;
                try {
                    message = JsonParser.parseString(trimmed).getAsJsonObject();
                } catch (Exception e) {
                    LOG.warn("drop invalid core frame: " + trimmed);
                    continue;
                }
                dispatch(message);
            }
        } catch (IOException e) {
            if (running) {
                LOG.warn("agent core stream closed", e);
            }
        } finally {
            for (CompletableFuture<JsonObject> future : pending.values()) {
                future.completeExceptionally(new IOException("agent core connection closed"));
            }
            pending.clear();
        }
    }

    private void dispatch(@NotNull JsonObject message) {
        if (message.has("method")) {
            for (Consumer<JsonObject> handler : notificationHandlers) {
                try {
                    handler.accept(message);
                } catch (Exception e) {
                    LOG.warn("notification handler failed", e);
                }
            }
            return;
        }
        if (message.has("id")) {
            String id = message.get("id").getAsString();
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) {
                future.complete(message);
            }
        }
    }

    public CompletableFuture<JsonObject> request(@NotNull String method, @NotNull Map<String, Object> params) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        process.writeLine(GSON.toJson(payload));
        return future;
    }

    public void notify(@NotNull String method, @NotNull Map<String, Object> params) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("method", method);
        payload.put("params", params);
        process.writeLine(GSON.toJson(payload));
    }

    public boolean isAlive() {
        return running && process.isAlive();
    }

    @Override
    public void dispose() {
        running = false;
        if (readerThread != null) {
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process.dispose();
    }
}
