package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.chat.agent.AgentSystemPromptBuilder;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.persistence.SecureFileStore;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import com.github.drafael.chat4j.provider.support.ProcessCommandSupport;
import com.github.drafael.chat4j.provider.support.ProcessHandleSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.WebSearchSourceUrlNormalizer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;

@Slf4j
public class CodexCliChatCompletionClient implements ChatCompletionClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final int INITIALIZE_REQUEST_ID = 1;
    private static final int THREAD_START_REQUEST_ID = 2;
    private static final int TURN_START_REQUEST_ID = 3;

    private final Map<String, String> subprocessEnvironment;
    private final ProviderAttachmentSupport attachmentSupport;
    private final ConcurrentMap<Process, ConcurrentMap<Long, ProcessHandle>> descendantsByProcess = new ConcurrentHashMap<>();

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
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                WebSearchRequestOptions.disabled(),
                onToken,
                onThinkingToken,
                ignored -> {},
                ignored -> {},
                ignored -> {},
                ignored -> {},
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                onCitation,
                query -> {
                },
                onWebSearchSource,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            Consumer<String> onWebSearchQuery,
            Consumer<WebSearchSource> onWebSearchSource,
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
        ReasoningLevel normalizedReasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        boolean webSearchEnabled = webSearchOptions != null && webSearchOptions.enabled();
        AtomicBoolean emittedOutput = new AtomicBoolean(false);
        Path codexHome = createCodexHome(runtime.apiKey());
        try {
            try {
                streamViaAppServer(
                        runtime,
                        prompt,
                        normalizedReasoningLevel,
                        webSearchEnabled,
                        codexHome,
                        onToken,
                        onThinkingToken,
                        onWebSearchQuery,
                        onWebSearchSource,
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
                    streamViaExec(
                            runtime,
                            prompt,
                            normalizedReasoningLevel,
                            webSearchEnabled,
                            codexHome,
                            onToken,
                            onWebSearchQuery,
                            onWebSearchSource,
                            isCancelled,
                            registerActiveStream,
                            clearActiveStream
                    );
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
        } finally {
            deleteCodexHome(codexHome);
        }
    }

    private void streamViaAppServer(ProviderRuntime runtime,
                                    String prompt,
                                    ReasoningLevel reasoningLevel,
                                    boolean webSearchEnabled,
                                    Path codexHome,
                                    Consumer<String> onToken,
                                    Consumer<String> onThinkingToken,
                                    Consumer<String> onWebSearchQuery,
                                    Consumer<WebSearchSource> onWebSearchSource,
                                    BooleanSupplier isCancelled,
                                    Consumer<AutoCloseable> registerActiveStream,
                                    Runnable clearActiveStream,
                                    AtomicBoolean emittedOutput
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(appServerCommand(webSearchEnabled));
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        applyExecutionDirectory(processBuilder);
        applyCodexEnvironment(processBuilder, codexHome);

        Process process = processBuilder.start();
        try {
            registerActiveStream.accept(() -> destroyProcessTree(process));
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

                sendJson(writer, turnStartRequest(threadId, prompt, reasoningLevel));
                awaitTurnCompletion(
                        reader,
                        process,
                        reasoningLevel,
                        onToken,
                        onThinkingToken,
                        onWebSearchQuery,
                        onWebSearchSource,
                        isCancelled,
                        emittedOutput
                );
            }
        } finally {
            try {
                terminateProcess(process);
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void streamViaExec(ProviderRuntime runtime,
                               String prompt,
                               ReasoningLevel reasoningLevel,
                               boolean webSearchEnabled,
                               Path codexHome,
                               Consumer<String> onToken,
                               Consumer<String> onWebSearchQuery,
                               Consumer<WebSearchSource> onWebSearchSource,
                               BooleanSupplier isCancelled,
                               Consumer<AutoCloseable> registerActiveStream,
                               Runnable clearActiveStream
    ) throws Exception {
        Path outputFile = Files.createTempFile(codexHome, "output-", ".txt");
        SecureFileStore.applyOwnerOnlyFilePermissions(outputFile);
        try {
            executeExec(
                    runtime,
                    prompt,
                    outputFile,
                    reasoningLevel,
                    webSearchEnabled,
                    codexHome,
                    onToken,
                    onWebSearchQuery,
                    onWebSearchSource,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
        } finally {
            deleteIfExists(outputFile);
        }
    }

    private void executeExec(
            ProviderRuntime runtime,
            String prompt,
            Path outputFile,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled,
            Path codexHome,
            Consumer<String> onToken,
            Consumer<String> onWebSearchQuery,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(execCommand(
                runtime.selectedModel(),
                outputFile,
                reasoningLevel,
                webSearchEnabled
        ));
        processBuilder.redirectErrorStream(true);
        applyExecutionDirectory(processBuilder);
        applyCodexEnvironment(processBuilder, codexHome);

        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = null;
        try {
            registerActiveStream.accept(() -> destroyProcessTree(process));
            outputFuture = CompletableFuture.supplyAsync(
                    () -> readAll(process),
                    command -> Thread.ofVirtual().name("chat4j-codex-exec-reader").start(command)
            );
            try (var input = process.getOutputStream()) {
                input.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            while (process.isAlive()) {
                trackProcessDescendants(process);
                if (shouldStop(isCancelled)) {
                    destroyProcessTree(process);
                    return;
                }
                Thread.sleep(100);
            }

            if (shouldStop(isCancelled)) {
                return;
            }
            destroyProcessTree(process);
            String commandOutput = awaitExecOutput(process, outputFuture);
            if (shouldStop(isCancelled)) {
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("codex exec failed (exit %d): %s".formatted(exitCode, firstLine(commandOutput)));
            }

            emitExecWebSearchEvents(commandOutput, onWebSearchQuery, onWebSearchSource, isCancelled);
            String responseText = Files.readString(outputFile, StandardCharsets.UTF_8).stripTrailing();
            if (responseText.isBlank()) {
                throw new IllegalStateException("codex exec completed without assistant output");
            }
            if (!shouldStop(isCancelled)) {
                onToken.accept(responseText);
            }
        } finally {
            try {
                terminateProcess(process);
            } finally {
                settleExecOutputReader(process, outputFuture);
                clearActiveStream.run();
            }
        }
    }

    private String awaitExecOutput(Process process, CompletableFuture<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            destroyProcessTree(process);
            try {
                return outputFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                }
                outputFuture.cancel(true);
                throw new IllegalStateException("codex exec output stream did not close after process exit", timeout);
            }
        }
    }

    private void settleExecOutputReader(Process process, CompletableFuture<String> outputFuture) {
        try {
            process.getInputStream().close();
        } catch (IOException | RuntimeException ignored) {
        }
        if (outputFuture == null || outputFuture.isDone()) {
            return;
        }

        boolean interrupted = Thread.interrupted();
        try {
            outputFuture.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            outputFuture.cancel(true);
        } catch (Exception e) {
            outputFuture.cancel(true);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void terminateProcess(Process process) {
        boolean interrupted = Thread.interrupted();
        ConcurrentMap<Long, ProcessHandle> knownDescendants = descendantsByProcess.computeIfAbsent(
                process,
                ignored -> new ConcurrentHashMap<>()
        );
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                destroyProcessTree(process);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (true) {
                    trackProcessDescendants(process);
                    knownDescendants.values().forEach(this::destroyProcessHandle);
                    if (!process.isAlive()
                            && knownDescendants.values().stream().noneMatch(ProcessHandleSupport::isRunning)) {
                        return;
                    }
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            log.warn("Codex subprocess {} did not terminate after forced cleanup", process.pid());
        } finally {
            descendantsByProcess.remove(process, knownDescendants);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void destroyProcessTree(Process process) {
        trackProcessDescendants(process);
        ConcurrentMap<Long, ProcessHandle> knownDescendants = descendantsByProcess.get(process);
        if (knownDescendants == null) {
            try {
                if (!process.isAlive()) {
                    return;
                }
            } catch (RuntimeException e) {
                return;
            }
        }
        ConcurrentMap<Long, ProcessHandle> trackedDescendants = descendantsByProcess.computeIfAbsent(
                process,
                ignored -> new ConcurrentHashMap<>()
        );
        trackedDescendants.values().forEach(this::destroyProcessHandle);
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void trackProcessDescendants(Process process) {
        List<ProcessHandle> discovered = new ArrayList<>();
        try {
            discovered.addAll(process.descendants().toList());
        } catch (RuntimeException ignored) {
        }

        ConcurrentMap<Long, ProcessHandle> trackedDescendants = descendantsByProcess.get(process);
        if (trackedDescendants != null) {
            List.copyOf(trackedDescendants.values()).forEach(handle -> {
                try {
                    discovered.addAll(handle.descendants().toList());
                } catch (RuntimeException ignored) {
                }
            });
        }
        if (discovered.isEmpty()) {
            return;
        }

        ConcurrentMap<Long, ProcessHandle> tracked = descendantsByProcess.computeIfAbsent(
                process,
                ignored -> new ConcurrentHashMap<>()
        );
        discovered.forEach(handle -> tracked.putIfAbsent(handle.pid(), handle));
    }

    private void destroyProcessHandle(ProcessHandle handle) {
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
        }
    }

    List<String> appServerCommand(boolean webSearchEnabled) {
        return List.of(
                "codex",
                "-c",
                webSearchOverride(webSearchEnabled),
                "app-server",
                "--listen",
                "stdio://"
        );
    }

    List<String> execCommand(
            String model,
            Path outputFile,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled
    ) {
        List<String> command = new ArrayList<>(List.of(
                "codex",
                "-c",
                webSearchOverride(webSearchEnabled)
        ));
        reasoningEffort(reasoningLevel).ifPresent(effort -> {
            command.add("-c");
            command.add("model_reasoning_effort=\"%s\"".formatted(effort));
        });
        command.addAll(List.of(
                "exec",
                "--json",
                "--sandbox",
                "read-only",
                "--ephemeral",
                "-m",
                model,
                "-o",
                outputFile.toString(),
                "-"
        ));
        return List.copyOf(command);
    }

    private String webSearchOverride(boolean webSearchEnabled) {
        return "web_search=\"%s\"".formatted(webSearchEnabled ? "live" : "disabled");
    }

    void emitExecWebSearchEvents(
            String commandOutput,
            Consumer<String> onWebSearchQuery,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled
    ) {
        Set<String> emittedQueries = new LinkedHashSet<>();
        Set<String> emittedSourceUrls = new LinkedHashSet<>();
        if (StringUtils.isBlank(commandOutput)) {
            return;
        }
        commandOutput.lines()
                .takeWhile(ignored -> !shouldStop(isCancelled))
                .filter(StringUtils::isNotBlank)
                .forEach(line -> {
                    try {
                        CodexCliApi.Message event = JSON.read(line, CodexCliApi.Message.class);
                        CodexCliApi.Item item = event.eventItem();
                        emitWebSearchQueries(
                                item,
                                emittedQueries,
                                onWebSearchQuery,
                                () -> {
                                },
                                isCancelled
                        );
                        emitWebSearchSources(
                                item,
                                emittedSourceUrls,
                                onWebSearchSource,
                                () -> {
                                },
                                isCancelled
                        );
                    } catch (RuntimeException ignored) {
                        // Codex may include non-JSON diagnostics in the merged output stream.
                    }
                });
    }

    private void emitWebSearchQueries(
            CodexCliApi.Item item,
            Set<String> emittedQueries,
            Consumer<String> onWebSearchQuery,
            Runnable beforeEmit,
            BooleanSupplier isCancelled
    ) {
        if (!isWebSearchItem(item) || shouldStop(isCancelled)) {
            return;
        }
        emitSearchQueryValue(
                item.query(),
                emittedQueries,
                onWebSearchQuery,
                beforeEmit,
                isCancelled
        );
        emitSearchQuery(item.action(), emittedQueries, onWebSearchQuery, beforeEmit, isCancelled);
        if (item.actions() != null) {
            item.actions().forEach(action -> emitSearchQuery(
                    action,
                    emittedQueries,
                    onWebSearchQuery,
                    beforeEmit,
                    isCancelled
            ));
        }
    }

    private void emitSearchQuery(
            CodexCliApi.Action action,
            Set<String> emittedQueries,
            Consumer<String> onWebSearchQuery,
            Runnable beforeEmit,
            BooleanSupplier isCancelled
    ) {
        if (action == null) {
            return;
        }
        String actionType = StringUtils.defaultString(action.type());
        if (!("search".equals(actionType) || "search_query".equals(actionType)) || shouldStop(isCancelled)) {
            return;
        }
        emitSearchQueryValue(
                action.query(),
                emittedQueries,
                onWebSearchQuery,
                beforeEmit,
                isCancelled
        );
        if (action.queries() != null) {
            action.queries().forEach(query -> emitSearchQueryValue(
                    query,
                    emittedQueries,
                    onWebSearchQuery,
                    beforeEmit,
                    isCancelled
            ));
        }
    }

    private void emitSearchQueryValue(
            String value,
            Set<String> emittedQueries,
            Consumer<String> onWebSearchQuery,
            Runnable beforeEmit,
            BooleanSupplier isCancelled
    ) {
        String query = StringUtils.normalizeSpace(value);
        if (StringUtils.isBlank(query) || shouldStop(isCancelled)) {
            return;
        }
        if (emittedQueries.add(query) && !shouldStop(isCancelled)) {
            beforeEmit.run();
            onWebSearchQuery.accept(query);
        }
    }

    private void emitWebSearchSources(
            CodexCliApi.Item item,
            Set<String> emittedSourceUrls,
            Consumer<WebSearchSource> onWebSearchSource,
            Runnable beforeEmit,
            BooleanSupplier isCancelled
    ) {
        if (!isWebSearchItem(item) || shouldStop(isCancelled)) {
            return;
        }
        emitOpenPageSource(item.action(), emittedSourceUrls, onWebSearchSource, beforeEmit, isCancelled);
        if (item.actions() != null) {
            item.actions().forEach(candidate -> emitOpenPageSource(
                    candidate,
                    emittedSourceUrls,
                    onWebSearchSource,
                    beforeEmit,
                    isCancelled
            ));
        }
    }

    private boolean isWebSearchItem(CodexCliApi.Item item) {
        if (item == null) {
            return false;
        }
        String type = StringUtils.defaultString(item.type());
        return "webSearch".equals(type) || "web_search".equals(type);
    }

    private void emitOpenPageSource(
            CodexCliApi.Action action,
            Set<String> emittedSourceUrls,
            Consumer<WebSearchSource> onWebSearchSource,
            Runnable beforeEmit,
            BooleanSupplier isCancelled
    ) {
        if (action == null) {
            return;
        }
        String actionType = StringUtils.defaultString(action.type());
        if (!("openPage".equals(actionType) || "open_page".equals(actionType)) || shouldStop(isCancelled)) {
            return;
        }
        WebSearchSourceUrlNormalizer.normalize(action.url()).ifPresent(normalized -> {
            if (emittedSourceUrls.add(normalized.key()) && !shouldStop(isCancelled)) {
                beforeEmit.run();
                onWebSearchSource.accept(new WebSearchSource(normalized.host(), normalized.displayUrl()));
            }
        });
    }

    private String awaitThreadId(BufferedReader reader,
                                 Process process,
                                 BooleanSupplier isCancelled
    ) throws Exception {
        while (true) {
            CodexCliApi.Message message = nextMessage(reader, process, isCancelled);
            if (message == null) {
                if (shouldStop(isCancelled)) {
                    return null;
                }
                throw new IllegalStateException("codex app-server stopped before thread start completed");
            }

            ensureNoRpcError(message);

            int id = message.id() == null ? -1 : message.id();
            if (id != THREAD_START_REQUEST_ID) {
                continue;
            }

            String threadId = message.result() == null || message.result().thread() == null
                    ? ""
                    : StringUtils.trimToEmpty(message.result().thread().id());
            if (!threadId.isBlank()) {
                return threadId;
            }

            throw new IllegalStateException("codex app-server thread/start response did not include thread id");
        }
    }

    void awaitTurnCompletion(BufferedReader reader,
                             Process process,
                             ReasoningLevel reasoningLevel,
                             Consumer<String> onToken,
                             Consumer<String> onThinkingToken,
                             Consumer<String> onWebSearchQuery,
                             Consumer<WebSearchSource> onWebSearchSource,
                             BooleanSupplier isCancelled,
                             AtomicBoolean emittedOutput
    ) throws Exception {
        Set<String> emittedQueries = new LinkedHashSet<>();
        Set<String> emittedSourceUrls = new LinkedHashSet<>();
        var emittedAssistantText = new AtomicBoolean();
        while (true) {
            CodexCliApi.Message message = nextMessage(reader, process, isCancelled);
            if (message == null) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                throw new IllegalStateException("codex app-server stopped before turn completed");
            }

            ensureNoRpcError(message);

            String method = StringUtils.defaultString(message.method());
            if ("item/agentMessage/delta".equals(method)) {
                String delta = message.params() == null ? "" : StringUtils.defaultString(message.params().delta());
                if (!delta.isEmpty()) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    emittedOutput.set(true);
                    if (StringUtils.isNotBlank(delta)) {
                        emittedAssistantText.set(true);
                    }
                    onToken.accept(delta);
                }
                continue;
            }

            if ("item/reasoning/summaryTextDelta".equals(method)) {
                String delta = message.params() == null ? "" : StringUtils.defaultString(message.params().delta());
                if (!delta.isEmpty()) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    emittedOutput.set(true);
                    if (reasoningLevel.enabled()) {
                        onThinkingToken.accept(delta);
                    }
                }
                continue;
            }

            if ("item/started".equals(method) || "item/completed".equals(method)) {
                CodexCliApi.Item item = message.params() == null ? null : message.params().item();
                emitWebSearchQueries(
                        item,
                        emittedQueries,
                        onWebSearchQuery,
                        () -> emittedOutput.set(true),
                        isCancelled
                );
                emitWebSearchSources(
                        item,
                        emittedSourceUrls,
                        onWebSearchSource,
                        () -> emittedOutput.set(true),
                        isCancelled
                );
                if ("item/completed".equals(method)) {
                    emitCompletedAgentMessage(
                            item,
                            onToken,
                            isCancelled,
                            emittedOutput,
                            emittedAssistantText
                    );
                }
                continue;
            }

            if ("turn/completed".equals(method)) {
                CodexCliApi.Turn turn = message.params() == null ? null : message.params().turn();
                String status = turn == null ? "" : StringUtils.defaultString(turn.status());
                if ("completed".equals(status)) {
                    if (emittedAssistantText.get()) {
                        return;
                    }
                    throw new IllegalStateException("codex app-server completed without assistant output");
                }
                String error = turn == null || turn.error() == null
                        ? ""
                        : StringUtils.defaultString(turn.error().message());
                String detail = StringUtils.isBlank(error) ? StringUtils.defaultIfBlank(status, "unknown status") : error;
                throw new IllegalStateException("codex app-server turn did not complete: %s".formatted(detail));
            }

            if ("error".equals(method)) {
                String error = message.params() == null || message.params().error() == null
                        ? "Unknown error"
                        : StringUtils.defaultIfBlank(message.params().error().message(), "Unknown error");
                throw new IllegalStateException("codex app-server error: %s".formatted(error));
            }

            int id = message.id() == null ? -1 : message.id();
            if (id == TURN_START_REQUEST_ID && message.result() == null) {
                throw new IllegalStateException("codex app-server turn/start failed");
            }
        }
    }

    private void emitCompletedAgentMessage(
            CodexCliApi.Item item,
            Consumer<String> onToken,
            BooleanSupplier isCancelled,
            AtomicBoolean emittedOutput,
            AtomicBoolean emittedAssistantText
    ) {
        String type = item == null ? "" : StringUtils.defaultString(item.type());
        if (!("agentMessage".equals(type) || "agent_message".equals(type)) || emittedAssistantText.get()) {
            return;
        }
        String text = StringUtils.defaultString(item.text());
        if (!text.isBlank() && !shouldStop(isCancelled)) {
            emittedOutput.set(true);
            emittedAssistantText.set(true);
            onToken.accept(text);
        }
    }

    private CodexCliApi.Message nextMessage(BufferedReader reader,
                                 Process process,
                                 BooleanSupplier isCancelled
    ) throws Exception {
        while (true) {
            CompletableFuture<String> lineFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, command -> Thread.ofVirtual().name("chat4j-codex-app-server-reader").start(command));
            long processExitDeadline = Long.MAX_VALUE;
            while (true) {
                trackProcessDescendants(process);
                if (shouldStop(isCancelled)) {
                    destroyProcessTree(process);
                    lineFuture.cancel(true);
                    return null;
                }
                try {
                    String line = lineFuture.get(25, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        return null;
                    }
                    if (StringUtils.isBlank(line)) {
                        break;
                    }
                    try {
                        return JSON.read(line, CodexCliApi.Message.class);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("codex app-server returned malformed JSON");
                    }
                } catch (TimeoutException e) {
                    if (!process.isAlive()) {
                        destroyProcessTree(process);
                        if (processExitDeadline == Long.MAX_VALUE) {
                            processExitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        } else if (System.nanoTime() >= processExitDeadline) {
                            try {
                                process.getInputStream().close();
                            } catch (IOException ignored) {
                            }
                            lineFuture.cancel(true);
                            return null;
                        }
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof CompletionException completionException
                            && completionException.getCause() instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw e;
                }
            }
        }
    }

    private void ensureNoRpcError(CodexCliApi.Message message) {
        if (message.error() == null) {
            return;
        }

        String error = StringUtils.defaultIfBlank(message.error().message(), "Unknown error");
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

    Map<String, Object> threadStartRequest(String model) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ephemeral", true);
        params.put("approvalPolicy", "never");
        params.put("sandbox", "read-only");
        if (StringUtils.isNotBlank(model)) {
            params.put("model", model);
        }

        return Map.of(
                "method", "thread/start",
                "id", THREAD_START_REQUEST_ID,
                "params", params
        );
    }

    Map<String, Object> turnStartRequest(String threadId, String prompt, ReasoningLevel reasoningLevel) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("threadId", threadId);
        params.put("input", List.of(Map.of("type", "text", "text", prompt)));
        reasoningEffort(reasoningLevel).ifPresent(effort -> params.put("effort", effort));
        return Map.of(
                "method", "turn/start",
                "id", TURN_START_REQUEST_ID,
                "params", params
        );
    }

    private Optional<String> reasoningEffort(ReasoningLevel reasoningLevel) {
        if (reasoningLevel == null || !reasoningLevel.enabled()) {
            return Optional.empty();
        }
        return Optional.of(switch (reasoningLevel) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case EXTRA_HIGH -> "xhigh";
            case MAX -> "max";
            case ULTRA -> "ultra";
            case OFF -> throw new IllegalStateException("Disabled reasoning has no Codex effort value");
        });
    }

    private void sendJson(BufferedWriter writer, Map<String, Object> payload) throws IOException {
        writer.write(JSON.writeString(payload));
        writer.newLine();
        writer.flush();
    }

    Path createCodexHome(String credential) throws IOException {
        if (StringUtils.isBlank(credential)) {
            throw new IllegalStateException("OpenAI Codex credentials are unavailable");
        }

        Path codexHome = Files.createTempDirectory("chat4j-codex-home-");
        SecureFileStore.applyOwnerOnlyDirectoryPermissions(codexHome);
        try {
            SecureFileStore.writeStringAtomically(
                    codexHome.resolve("auth.json"),
                    codexAuthDocument(credential.trim()),
                    "auth"
            );
            return codexHome;
        } catch (IOException | RuntimeException e) {
            deleteCodexHome(codexHome);
            throw e;
        }
    }

    private String codexAuthDocument(String credential) throws IOException {
        String accountId = chatGptAccountId(credential).orElse(null);
        if (accountId == null) {
            return JSON.writeString(Map.of(
                    "auth_mode", "apikey",
                    "OPENAI_API_KEY", credential
            ));
        }

        return JSON.writeString(Map.of(
                "auth_mode", "chatgpt",
                "tokens", Map.of(
                        "access_token", credential,
                        "id_token", credential,
                        "refresh_token", "",
                        "account_id", accountId
                ),
                "last_refresh", Instant.now().toString()
        ));
    }

    private Optional<String> chatGptAccountId(String credential) {
        String[] parts = credential.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            CodexCliApi.JwtClaims claims = JSON.read(
                    Base64.getUrlDecoder().decode(parts[1]),
                    CodexCliApi.JwtClaims.class
            );
            String accountId = claims.chatGptAccountId();
            if (StringUtils.isBlank(accountId)) {
                throw new IllegalStateException("OpenAI Codex OAuth credential is missing its ChatGPT account identifier");
            }
            return Optional.of(accountId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("OpenAI Codex OAuth credential is malformed", e);
        }
    }

    private void applyCodexEnvironment(ProcessBuilder processBuilder, Path codexHome) {
        Map<String, String> environment = new LinkedHashMap<>(subprocessEnvironment);
        environment.keySet().removeIf(this::isCodexCredentialEnvironmentVariable);
        environment.put("CODEX_HOME", codexHome.toString());
        ProcessCommandSupport.applyEnvironment(processBuilder, environment);
    }

    private boolean isCodexCredentialEnvironmentVariable(String name) {
        return "CODEX_HOME".equalsIgnoreCase(name)
                || "CODEX_ACCESS_TOKEN".equalsIgnoreCase(name)
                || "CODEX_API_KEY".equalsIgnoreCase(name)
                || "OPENAI_API_KEY".equalsIgnoreCase(name);
    }

    void deleteCodexHome(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                deleteRecursively(directory);
                if (!Files.exists(directory)) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            registerDeleteOnExit(directory);
            log.warn("Unable to delete temporary Codex credential directory immediately: {}", directory);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deleteRecursively(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    if (!(error instanceof NoSuchFileException)) {
                        deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException error) {
                    deleteIfExists(visitedDirectory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void registerDeleteOnExit(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path visitedDirectory, BasicFileAttributes attributes) {
                    visitedDirectory.toFile().deleteOnExit();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    file.toFile().deleteOnExit();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            directory.toFile().deleteOnExit();
        }
    }

    private String buildPrompt(AttachmentProjectionPlan projectionPlan) {
        String transcript = projectionPlan.messages().stream()
                .map(message -> "%s:\n%s".formatted(roleLabel(message.role()), messageText(message)))
                .collect(joining("\n\n"));

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
                .collect(joining("\n"));
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
            return reader.lines().collect(joining("\n"));
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
