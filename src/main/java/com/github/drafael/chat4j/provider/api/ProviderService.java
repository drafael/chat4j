package com.github.drafael.chat4j.provider.api;

import com.github.drafael.chat4j.provider.support.CredentialResolver;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface ProviderService {

    void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled
    );

    List<String> availableModels();

    default void cancelActiveRequest() {
        // no-op by default
    }

    String name();

    String envVarName();

    default boolean isAvailable() {
        return CredentialResolver.hasRequiredCredentials(envVarName());
    }
}
