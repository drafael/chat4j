package com.github.drafael.chat4j.chat.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
final class OpenAiCompatibleFallbackAgentAdapter implements AgentProviderAdapter {

    private static final String FALLBACK_NOTICE_TEMPLATE = "\n\n[Chat4J notice: %s tool API is unavailable. Falling back to provider chat mode (without local tools).]\n\n";

    private final String providerName;
    private final AgentProviderAdapter openAiToolAdapter;
    private final AgentProviderAdapter providerServiceAdapter;
    private boolean fallbackNoticeEmitted;

    OpenAiCompatibleFallbackAgentAdapter(
            String providerName,
            AgentProviderAdapter openAiToolAdapter,
            AgentProviderAdapter providerServiceAdapter
    ) {
        this.providerName = StringUtils.defaultIfBlank(providerName, "OpenAI-compatible provider");
        this.openAiToolAdapter = openAiToolAdapter;
        this.providerServiceAdapter = providerServiceAdapter;
    }

    @Override
    public AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks) {
        AtomicReference<Exception> primaryError = new AtomicReference<>();
        AtomicBoolean emittedContent = new AtomicBoolean(false);
        List<String> bufferedTokens = new ArrayList<>();
        List<String> bufferedThinking = new ArrayList<>();

        AgentRunCallbacks interceptedCallbacks = new AgentRunCallbacks(
                token -> {
                    emittedContent.set(true);
                    bufferedTokens.add(token);
                },
                thinkingToken -> {
                    emittedContent.set(true);
                    bufferedThinking.add(thinkingToken);
                },
                callbacks.onComplete(),
                primaryError::set
        );

        AgentTurnResult primaryResult = openAiToolAdapter.executeTurn(request, interceptedCallbacks);
        Exception error = primaryError.get();
        if (shouldFallback(error, emittedContent.get(), primaryResult, request, bufferedTokens)) {
            log.warn("Falling back to provider chat adapter after OpenAI-compatible tool failure for {}: {}",
                    providerName,
                    StringUtils.defaultString(error == null ? "tool mode unavailable for this turn" : error.getMessage()));
            emitFallbackNotice(callbacks);
            return providerServiceAdapter.executeTurn(request, callbacks);
        }

        flushBufferedChunks(bufferedThinking, callbacks.onThinkingToken());
        flushBufferedChunks(bufferedTokens, callbacks.onToken());

        if (error != null) {
            callbacks.onError().accept(error);
        }

        return primaryResult;
    }

    private void emitFallbackNotice(AgentRunCallbacks callbacks) {
        if (fallbackNoticeEmitted) {
            return;
        }

        fallbackNoticeEmitted = true;
        callbacks.onToken().accept(FALLBACK_NOTICE_TEMPLATE.formatted(providerName));
    }

    private void flushBufferedChunks(List<String> chunks, Consumer<String> emitter) {
        chunks.stream()
                .filter(StringUtils::isNotEmpty)
                .forEach(emitter);
    }

    private boolean shouldFallback(
            Exception error,
            boolean emittedContent,
            AgentTurnResult primaryResult,
            AgentRunRequest request,
            List<String> bufferedTokens
    ) {
        if (error != null) {
            if (emittedContent) {
                return false;
            }

            String message = StringUtils.defaultString(error.getMessage()).toLowerCase(Locale.ROOT);
            return message.contains("http 400")
                    || message.contains("http 401")
                    || message.contains("http 403")
                    || message.contains("http 404")
                    || message.contains("http 405")
                    || message.contains("http 415")
                    || message.contains("http 429")
                    || message.contains("timed out")
                    || message.contains("timeout")
                    || message.contains("invalid argument")
                    || message.contains("unsupported")
                    || message.contains("tool")
                    || message.contains("function");
        }

        if (request.projectRoot() == null
                || !request.toolResults().isEmpty()
                || !primaryResult.completed()
                || !primaryResult.toolInvocations().isEmpty()) {
            return false;
        }

        String text = bufferedTokens.stream()
                .filter(StringUtils::isNotBlank)
                .reduce("", (left, right) -> left.isEmpty() ? right : "%s %s".formatted(left, right))
                .toLowerCase(Locale.ROOT);

        if (StringUtils.isBlank(text)) {
            return true;
        }

        return text.contains("let me explore")
                || text.contains("i'll explore")
                || text.contains("i will explore")
                || text.contains("need to inspect")
                || text.contains("without inspecting")
                || text.contains("can’t accurately")
                || text.contains("can't accurately")
                || text.contains("from the context alone");
    }
}
