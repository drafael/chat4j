package com.github.drafael.chat4j.chat.agent;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
final class CodexFallbackAgentAdapter implements AgentProviderAdapter {

    private static final String FALLBACK_NOTICE_TEMPLATE = "\n\n[Chat4J notice: OpenAI Codex tool API is unavailable (quota/auth). Falling back to Codex CLI (project root: %s).]\n\n";

    private final AgentProviderAdapter openAiToolAdapter;
    private final AgentProviderAdapter codexCliAdapter;
    private boolean fallbackNoticeEmitted;

    CodexFallbackAgentAdapter(AgentProviderAdapter openAiToolAdapter, AgentProviderAdapter codexCliAdapter) {
        this.openAiToolAdapter = openAiToolAdapter;
        this.codexCliAdapter = codexCliAdapter;
    }

    @Override
    public AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks) {
        AtomicReference<Exception> primaryError = new AtomicReference<>();
        AtomicBoolean emittedContent = new AtomicBoolean(false);

        AgentRunCallbacks interceptedCallbacks = new AgentRunCallbacks(
                token -> {
                    emittedContent.set(true);
                    callbacks.onToken().accept(token);
                },
                thinkingToken -> {
                    emittedContent.set(true);
                    callbacks.onThinkingToken().accept(thinkingToken);
                },
                callbacks.onComplete(),
                primaryError::set
        );

        AgentTurnResult primaryResult = openAiToolAdapter.executeTurn(request, interceptedCallbacks);
        Exception error = primaryError.get();
        if (shouldFallbackToCodexCli(error, emittedContent.get())) {
            log.warn("Falling back to Codex CLI adapter after OpenAI-compatible tool failure: {}",
                    StringUtils.defaultString(error.getMessage()));
            emitFallbackNotice(callbacks, request);
            return codexCliAdapter.executeTurn(request, callbacks);
        }

        if (error != null) {
            callbacks.onError().accept(error);
        }

        return primaryResult;
    }

    private void emitFallbackNotice(AgentRunCallbacks callbacks, AgentRunRequest request) {
        if (fallbackNoticeEmitted) {
            return;
        }

        fallbackNoticeEmitted = true;
        String projectRoot = request.projectRoot() == null
                ? "(unknown)"
                : request.projectRoot().toAbsolutePath().normalize().toString();
        callbacks.onToken().accept(FALLBACK_NOTICE_TEMPLATE.formatted(projectRoot));
    }

    private boolean shouldFallbackToCodexCli(Exception error, boolean emittedContent) {
        if (error == null || emittedContent) {
            return false;
        }

        String message = StringUtils.defaultString(error.getMessage()).toLowerCase();
        return message.contains("insufficient_quota")
                || message.contains("http 429")
                || message.contains("http 401")
                || message.contains("http 403");
    }
}
