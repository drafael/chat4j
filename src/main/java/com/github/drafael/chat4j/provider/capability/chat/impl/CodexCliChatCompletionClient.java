package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ProcessCommandSupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

public class CodexCliChatCompletionClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INITIALIZE_REQUEST_ID = 1;
    private static final int THREAD_START_REQUEST_ID = 2;
    private static final int TURN_START_REQUEST_ID = 3;
    private static final AtomicReference<DiagnosticsSnapshot> LAST_DIAGNOSTICS =
            new AtomicReference<>(DiagnosticsSnapshot.empty());

    public static DiagnosticsSnapshot diagnosticsSnapshot() {
        return LAST_DIAGNOSTICS.get();
    }

    public record DiagnosticsSnapshot(String transport,
                                      boolean sawStreamingDelta,
                                      boolean fallbackUsed,
                                      String lastFailureReason,
                                      String lastAppServerError,
                                      long updatedAtEpochMs
    ) {

        private static DiagnosticsSnapshot empty() {
            return new DiagnosticsSnapshot("none", false, false, null, null, 0L);
        }
    }

    @Override
    public void streamCompletion(ProviderRuntime runtime,
                                 List<Message> history,
                                 Consumer<String> onToken,
                                 BooleanSupplier isCancelled,
                                 Consumer<AutoCloseable> registerActiveStream,
                                 Runnable clearActiveStream
    ) throws Exception {
        updateDiagnostics("pending", false, false, null, null);

        AtomicBoolean emittedOutput = new AtomicBoolean(false);
        AtomicBoolean sawStreamingDelta = new AtomicBoolean(false);

        try {
            streamViaAppServer(
                    runtime,
                    history,
                    onToken,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream,
                    emittedOutput,
                    sawStreamingDelta
            );
            updateDiagnostics("app-server", sawStreamingDelta.get(), false, null, null);
        } catch (Exception appServerError) {
            if (shouldStop(isCancelled)) {
                updateDiagnostics("cancelled", sawStreamingDelta.get(), false, null, null);
                return;
            }

            String appServerFailure = firstLine(appServerError.getMessage());
            if (emittedOutput.get()) {
                updateDiagnostics("app-server", sawStreamingDelta.get(), false, appServerFailure, appServerFailure);
                throw appServerError;
            }

            try {
                streamViaExec(runtime, history, onToken, isCancelled, registerActiveStream, clearActiveStream);
                updateDiagnostics("exec-fallback", false, true, null, appServerFailure);
            } catch (Exception execError) {
                String fallbackFailure = firstLine(execError.getMessage());
                updateDiagnostics("exec-fallback-failed", false, true, fallbackFailure, appServerFailure);
                throw new IllegalStateException(
                        "codex app-server failed: %s | codex exec fallback failed: %s"
                                .formatted(appServerFailure, fallbackFailure),
                        execError
                );
            }
        }
    }

    private void streamViaAppServer(ProviderRuntime runtime,
                                    List<Message> history,
                                    Consumer<String> onToken,
                                    BooleanSupplier isCancelled,
                                    Consumer<AutoCloseable> registerActiveStream,
                                    Runnable clearActiveStream,
                                    AtomicBoolean emittedOutput,
                                    AtomicBoolean sawStreamingDelta
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("codex", "app-server", "--listen", "stdio://");
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        ProcessCommandSupport.applyShellEnvironment(processBuilder);

        Process process = processBuilder.start();
        registerActiveStream.accept(() -> process.destroyForcibly());

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
        ) {

            sendJson(writer, initializeRequest());
            sendJson(writer, initializedNotification());
            sendJson(writer, threadStartRequest(runtime.selectedModel()));

            String threadId = awaitThreadId(reader, process, isCancelled);
            if (StringUtils.isBlank(threadId)) {
                return;
            }

            sendJson(writer, turnStartRequest(threadId, buildPrompt(history)));
            awaitTurnCompletion(reader, process, onToken, isCancelled, emittedOutput, sawStreamingDelta);
        } finally {
            clearActiveStream.run();
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    private void streamViaExec(ProviderRuntime runtime,
                               List<Message> history,
                               Consumer<String> onToken,
                               BooleanSupplier isCancelled,
                               Consumer<AutoCloseable> registerActiveStream,
                               Runnable clearActiveStream
    ) throws Exception {
        Path outputFile = Files.createTempFile("chat4j-codex-output", ".txt");
        String prompt = buildPrompt(history);

        ProcessBuilder processBuilder = new ProcessBuilder(
                "codex",
                "exec",
                "--sandbox",
                "read-only",
                "-m",
                runtime.selectedModel(),
                "-o",
                outputFile.toString(),
                prompt
        );
        processBuilder.redirectErrorStream(true);
        ProcessCommandSupport.applyShellEnvironment(processBuilder);

        Process process = processBuilder.start();
        process.getOutputStream().close();
        registerActiveStream.accept(() -> process.destroyForcibly());

        try {
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAll(process));

            while (process.isAlive()) {
                if (shouldStop(isCancelled)) {
                    process.destroyForcibly();
                    return;
                }
                Thread.sleep(100);
            }

            int exitCode = process.exitValue();
            String commandOutput = outputFuture.join();
            if (exitCode != 0) {
                throw new IllegalStateException("codex exec failed (exit %d): %s".formatted(exitCode, firstLine(commandOutput)));
            }

            String responseText = Files.exists(outputFile)
                    ? Files.readString(outputFile, StandardCharsets.UTF_8).trim()
                    : "";

            if (!responseText.isBlank()) {
                onToken.accept(responseText);
            }
        } finally {
            clearActiveStream.run();
            Files.deleteIfExists(outputFile);
        }
    }

    private String awaitThreadId(BufferedReader reader,
                                 Process process,
                                 BooleanSupplier isCancelled
    ) throws Exception {
        while (true) {
            JsonNode message = nextMessage(reader, process, isCancelled);
            if (message == null) {
                if (shouldStop(isCancelled)) {
                    return null;
                }
                throw new IllegalStateException("codex app-server stopped before thread start completed");
            }

            ensureNoRpcError(message);

            int id = message.path("id").asInt(-1);
            if (id != THREAD_START_REQUEST_ID) {
                continue;
            }

            String threadId = message.path("result").path("thread").path("id").asText("").trim();
            if (!threadId.isBlank()) {
                return threadId;
            }

            throw new IllegalStateException("codex app-server thread/start response did not include thread id");
        }
    }

    private void awaitTurnCompletion(BufferedReader reader,
                                     Process process,
                                     Consumer<String> onToken,
                                     BooleanSupplier isCancelled,
                                     AtomicBoolean emittedOutput,
                                     AtomicBoolean sawStreamingDelta
    ) throws Exception {
        while (true) {
            JsonNode message = nextMessage(reader, process, isCancelled);
            if (message == null) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                throw new IllegalStateException("codex app-server stopped before turn completed");
            }

            ensureNoRpcError(message);

            String method = message.path("method").asText("");
            if ("item/agentMessage/delta".equals(method)) {
                String delta = message.path("params").path("delta").asText("");
                if (!delta.isEmpty()) {
                    onToken.accept(delta);
                    emittedOutput.set(true);
                    sawStreamingDelta.set(true);
                }
                continue;
            }

            if ("item/completed".equals(method)) {
                JsonNode item = message.path("params").path("item");
                String type = item.path("type").asText("");
                if (("agentMessage".equals(type) || "agent_message".equals(type)) && !emittedOutput.get()) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        onToken.accept(text);
                        emittedOutput.set(true);
                    }
                }
                continue;
            }

            if ("turn/completed".equals(method)) {
                String status = message.path("params").path("turn").path("status").asText("completed");
                if ("failed".equalsIgnoreCase(status)) {
                    String error = message.path("params").path("turn").path("error").path("message").asText("Unknown error");
                    throw new IllegalStateException("codex app-server turn failed: %s".formatted(error));
                }
                return;
            }

            if ("error".equals(method)) {
                String error = message.path("params").path("error").path("message").asText("Unknown error");
                throw new IllegalStateException("codex app-server error: %s".formatted(error));
            }

            int id = message.path("id").asInt(-1);
            if (id == TURN_START_REQUEST_ID && message.path("result").isMissingNode()) {
                throw new IllegalStateException("codex app-server turn/start failed");
            }
        }
    }

    private JsonNode nextMessage(BufferedReader reader,
                                 Process process,
                                 BooleanSupplier isCancelled
    ) throws Exception {
        while (true) {
            if (shouldStop(isCancelled)) {
                process.destroyForcibly();
                return null;
            }

            if (reader.ready()) {
                String line = reader.readLine();
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                try {
                    return JSON.readTree(line);
                } catch (IOException ignored) {
                    continue;
                }
            }

            if (!process.isAlive()) {
                return null;
            }

            Thread.sleep(25);
        }
    }

    private void ensureNoRpcError(JsonNode message) {
        if (!message.has("error") || message.path("error").isNull()) {
            return;
        }

        String error = message.path("error").path("message").asText("Unknown error");
        throw new IllegalStateException("codex app-server RPC error: %s".formatted(error));
    }

    private Map<String, Object> initializeRequest() {
        return Map.of(
                "method", "initialize",
                "id", INITIALIZE_REQUEST_ID,
                "params", Map.of(
                        "clientInfo", Map.of(
                                "name", "chat4j",
                                "title", "Chat4J",
                                "version", "1.0"
                        )
                ));
    }

    private Map<String, Object> initializedNotification() {
        return Map.of(
                "method", "initialized",
                "params", Map.of());
    }

    private Map<String, Object> threadStartRequest(String model) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(model)) {
            params.put("model", model);
        }

        return Map.of(
                "method", "thread/start",
                "id", THREAD_START_REQUEST_ID,
                "params", params
        );
    }

    private Map<String, Object> turnStartRequest(String threadId, String prompt) {
        return Map.of(
                "method", "turn/start",
                "id", TURN_START_REQUEST_ID,
                "params", Map.of(
                        "threadId", threadId,
                        "input", List.of(Map.of("type", "text", "text", prompt))
                ));
    }

    private void sendJson(BufferedWriter writer, Map<String, Object> payload) throws IOException {
        writer.write(JSON.writeValueAsString(payload));
        writer.newLine();
        writer.flush();
    }

    private void updateDiagnostics(String transport,
                                   boolean sawStreamingDelta,
                                   boolean fallbackUsed,
                                   String lastFailureReason,
                                   String lastAppServerError
    ) {
        LAST_DIAGNOSTICS.set(new DiagnosticsSnapshot(
                transport,
                sawStreamingDelta,
                fallbackUsed,
                lastFailureReason,
                lastAppServerError,
                System.currentTimeMillis()
        ));
    }

    private String buildPrompt(List<Message> history) {
        String transcript = history.stream()
                .map(message -> "%s:\n%s".formatted(roleLabel(message.role()), message.content()))
                .reduce((left, right) -> "%s\n\n%s".formatted(left, right))
                .orElse("");

        return "You are a coding assistant. Answer directly in plain text. Do not execute commands or modify files.\n\nConversation:\n\n%s"
                .formatted(transcript);
    }

    private String roleLabel(Role role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
        };
    }

    private String readAll(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : "%s\n%s".formatted(left, right));
        } catch (IOException e) {
            return "";
        }
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "No output";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }
}
