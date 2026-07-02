package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static java.util.Collections.emptyList;

@Slf4j
public class CapabilityProviderService implements ProviderService {

    private static final String CHAT4J_RENDERING_HINT = """
            Chat4J can render explicit fenced Markdown blocks in the chat transcript.
            - Use `mermaid` only for flowcharts, sequence diagrams, state diagrams, class diagrams, ER diagrams, or process diagrams.
            - For generated chemical structures and molecules, output only a valid `smiles` fenced block, for example: ```smiles\nCCO\n```.
            - Do not output chemical coordinate-file fences from molecule names, formulas, or descriptions. Coordinate-file rendering requires exact user-supplied file content and should not be invented.
            - Do not use Mermaid for chemistry.
            Keep explanatory text brief and put renderable source inside the fence.
            """.strip();

    private final ProviderRuntime runtime;
    private final ChatCompletionClient chatCompletionClient;
    private final ModelCatalogClient modelCatalogClient;
    private final AtomicReference<AutoCloseable> activeStream = new AtomicReference<>();

    public CapabilityProviderService(
        ProviderRuntime runtime,
        ChatCompletionClient chatCompletionClient,
        ModelCatalogClient modelCatalogClient
    ) {
        this.runtime = runtime;
        this.chatCompletionClient = chatCompletionClient;
        this.modelCatalogClient = modelCatalogClient;
    }

    @Override
    public void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled
    ) {
        try {
            chatCompletionClient.streamCompletion(
                runtime,
                withChat4jRenderingHint(history),
                reasoningLevel,
                onToken,
                onThinkingToken,
                isCancelled,
                this::registerActiveStream,
                this::clearActiveStream
            );
            if (!isCancelled.getAsBoolean()) {
                onComplete.run();
            }
        } catch (Exception e) {
            if (!shouldStop(isCancelled)) {
                log.warn("Provider stream failed for {}: {}", runtime.descriptor().name(), ExceptionUtils.getMessage(e));
                onError.accept(ProviderExceptionMapper.map(e));
            }
        }
    }

    @Override
    public void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onComplete,
                onError,
                isCancelled,
                this::registerActiveStream,
                this::clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                part -> {
                },
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                citation -> {
                },
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Consumer<CitationRef> onCitation,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        try {
            chatCompletionClient.streamCompletion(
                runtime,
                withChat4jRenderingHint(history),
                reasoningLevel,
                webSearchOptions == null ? WebSearchRequestOptions.disabled() : webSearchOptions,
                onToken,
                onThinkingToken,
                onPart == null ? part -> {
                } : onPart,
                onCitation == null ? citation -> {
                } : onCitation,
                isCancelled,
                registerActiveStream,
                clearActiveStream
            );
            if (!isCancelled.getAsBoolean()) {
                onComplete.run();
            }
        } catch (Exception e) {
            if (!shouldStop(isCancelled)) {
                log.warn("Provider stream failed for {}: {}", runtime.descriptor().name(), ExceptionUtils.getMessage(e));
                onError.accept(ProviderExceptionMapper.map(e));
            }
        }
    }

    @Override
    public List<String> availableModels() {
        if (runtime.selectedModel() != null) {
            return List.of(runtime.selectedModel());
        }

        try {
            return modelCatalogClient.fetchModels(runtime);
        } catch (Exception e) {
            log.debug("Model listing failed for {}: {}", runtime.descriptor().name(), ExceptionUtils.getMessage(e));
            return emptyList();
        }
    }

    @Override
    public void cancelActiveRequest() {
        AutoCloseable stream = activeStream.getAndSet(null);
        if (stream == null) {
            return;
        }

        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String name() {
        return runtime.descriptor().name();
    }

    @Override
    public String envVarName() {
        return runtime.descriptor().credentialEnvVar();
    }

    static List<Message> withChat4jRenderingHint(List<Message> history) {
        List<Message> safeHistory = history == null ? emptyList() : history;
        if (safeHistory.stream().anyMatch(message -> message.role() == Role.SYSTEM)) {
            return safeHistory;
        }
        List<Message> hintedHistory = new ArrayList<>(safeHistory.size() + 1);
        hintedHistory.add(Message.system(CHAT4J_RENDERING_HINT));
        hintedHistory.addAll(safeHistory);
        return List.copyOf(hintedHistory);
    }

    private void registerActiveStream(AutoCloseable stream) {
        activeStream.set(stream);
    }

    private void clearActiveStream() {
        activeStream.set(null);
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
