package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static java.util.Collections.emptyList;

public class CapabilityProviderService implements ProviderService {

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
                history,
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
