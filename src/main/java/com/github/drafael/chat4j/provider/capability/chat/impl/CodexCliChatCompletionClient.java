package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.agent.AgentSystemPromptBuilder;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import com.github.drafael.chat4j.provider.support.ProcessCommandSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;

public class CodexCliChatCompletionClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INITIALIZE_REQUEST_ID = 1;
    private static final int THREAD_START_REQUEST_ID = 2;
    private static final int TURN_START_REQUEST_ID = 3;

    private final Map<String, String> subprocessEnvironment;
    private final ProviderAttachmentSupport attachmentSupport;

    public CodexCliChatCompletionClient(
            @NonNull Map<String, String> subprocessEnvironment,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this.subprocessEnvironment = Map.copyOf(subprocessEnvironment);
        this.attachmentSupport = attachmentSupport;
    }

    @Override
    public void streamCompletion(ProviderRuntime runtime,
                                 List<Message> history,
                                 ReasoningLevel reasoningLevel,
                                 Consumer<String> onToken,
                                 Consumer<String> onThinkingToken,
                                 BooleanSupplier isCancelled,
                                 Consumer<AutoCloseable> registerActiveStream,
                                 Runnable clearActiveStream
    ) throws Exception {
        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                isCancelled
        );
        if (shouldStop(isCancelled)) {
            return;
        }
        String prompt = buildPrompt(projectionPlan);
        AtomicBoolean emittedOutput = new AtomicBoolean(false);

        try {
            streamViaAppServer(
                    runtime,
                    prompt,
                    onToken,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream,
                    emittedOutput
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            if (shouldStop(isCancelled)) {
                return;
            }

            String appServerFailure = firstLine(e.getMessage());
            if (emittedOutput.get()) {
                throw e;
            }

            try {
                streamViaExec(runtime, prompt, onToken, isCancelled, registerActiveStream, clearActiveStream);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (Exception ex) {
                String fallbackFailure = firstLine(ex.getMessage());
                throw new IllegalStateException(
                        "codex app-server failed: %s | codex exec fallback failed: %s"
                                .formatted(appServerFailure, fallbackFailure),
                        ex
                );
            }
        }
    }

    private void streamViaAppServer(ProviderRuntime runtime,
                                    String prompt,
                                    Consumer<String> onToken,
                                    BooleanSupplier isCancelled,
                                    Consumer<AutoCloseable> registerActiveStream,
                                    Runnable clearActiveStream,
                                    AtomicBoolean emittedOutput
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder("codex", "app-server", "--listen", "stdio://");
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        applyExecutionDirectory(processBuilder);
        ProcessCommandSupport.applyEnvironment(processBuilder, subprocessEnvironment);

        Process process = processBuilder.start();
        try {
            registerActiveStream.accept(process::destroyForcibly);
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

                sendJson(writer, turnStartRequest(threadId, prompt));
                awaitTurnCompletion(reader, process, onToken, isCancelled, emittedOutput);
            }
        } finally {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void streamViaExec(ProviderRuntime runtime,
                               String prompt,
                               Consumer<String> onToken,
                               BooleanSupplier isCancelled,
                               Consumer<AutoCloseable> registerActiveStream,
                               Runnable clearActiveStream
    ) throws Exception {
        Path outputFile = Files.createTempFile("chat4j-codex-output", ".txt");
        try {
            executeExec(runtime, prompt, outputFile, onToken, isCancelled, registerActiveStream, clearActiveStream);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private void executeExec(
            ProviderRuntime runtime,
            String prompt,
            Path outputFile,
            Consumer<String> onToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "codex",
                "exec",
                "--sandbox",
                "read-only",
                "-m",
                runtime.selectedModel(),
                "-o",
                outputFile.toString(),
                "-"
        );
        processBuilder.redirectErrorStream(true);
        applyExecutionDirectory(processBuilder);
        ProcessCommandSupport.applyEnvironment(processBuilder, subprocessEnvironment);

        Process process = processBuilder.start();
        try {
            registerActiveStream.accept(process::destroyForcibly);
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readAll(process));
            try (var input = process.getOutputStream()) {
                input.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            while (process.isAlive()) {
                if (shouldStop(isCancelled)) {
                    process.destroyForcibly();
                    return;
                }
                Thread.sleep(100);
            }

            if (shouldStop(isCancelled)) {
                return;
            }
            String commandOutput = outputFuture.join();
            if (shouldStop(isCancelled)) {
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("codex exec failed (exit %d): %s".formatted(exitCode, firstLine(commandOutput)));
            }

            String responseText = Files.readString(outputFile, StandardCharsets.UTF_8).trim();
            if (!responseText.isBlank() && !shouldStop(isCancelled)) {
                onToken.accept(responseText);
            }
        } finally {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } finally {
                clearActiveStream.run();
            }
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
                                     AtomicBoolean emittedOutput
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
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    emittedOutput.set(true);
                    onToken.accept(delta);
                }
                continue;
            }

            if ("item/completed".equals(method)) {
                JsonNode item = message.path("params").path("item");
                String type = item.path("type").asText("");
                if (("agentMessage".equals(type) || "agent_message".equals(type)) && !emittedOutput.get()) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        if (shouldStop(isCancelled)) {
                            return;
                        }
                        emittedOutput.set(true);
                        onToken.accept(text);
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
                } catch (IOException e) {
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
                "params", emptyMap());
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

    private String buildPrompt(AttachmentProjectionPlan projectionPlan) {
        String transcript = projectionPlan.messages().stream()
                .map(message -> "%s:\n%s".formatted(roleLabel(message.role()), messageText(message)))
                .reduce("%s\n\n%s"::formatted)
                .orElse("");

        Path executionDirectory = ExecutionDirectoryContext.currentDirectory().orElse(null);
        if (executionDirectory != null) {
            String promptAppend = AgentSystemPromptContext.currentPromptAppend().orElse("");
            String systemPrompt = AgentSystemPromptBuilder.buildCodexFallbackPrompt(executionDirectory, promptAppend);
            return "%s\n\nConversation:\n\n%s".formatted(systemPrompt, transcript);
        }

        return "You are a coding assistant. Answer directly in plain text. Do not execute commands or modify files.\n\nConversation:\n\n%s"
                .formatted(transcript);
    }

    private String messageText(ProjectedMessage message) {
        return message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .reduce("%s\n%s"::formatted)
                .orElse("");
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

    private void applyExecutionDirectory(ProcessBuilder processBuilder) {
        ExecutionDirectoryContext.currentDirectory()
                .ifPresent(directory -> processBuilder.directory(directory.toFile()));
    }

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "No output";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }
}
