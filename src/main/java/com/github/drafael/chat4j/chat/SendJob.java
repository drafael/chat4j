package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class SendJob {
    final long jobId;
    volatile UUID conversationId;
    UUID userMessageId;
    Instant userMessageTimestamp;
    int userMessageOrdinal;
    int assistantMessageOrdinal;
    boolean createsConversation;
    final SendRuntimeSnapshot runtime;
    volatile String apiKey;
    volatile ProviderService provider;
    final List<Message> historySnapshot;
    final ReasoningLevel reasoningLevel;
    final boolean requestedWebSearch;
    final boolean webSearchEnabled;
    final boolean agentModeEnabled;
    final Path agentProjectRoot;
    final String agentSystemPromptAppend;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    volatile SendPhase phase = SendPhase.PREPARING;
    volatile Thread worker;
    volatile boolean finished;
    volatile Long streamSessionId;
    volatile ComposerState composerState;
    volatile Message preparedUserMessage;
    volatile boolean durableUserMessageSubmissionStarted;
    volatile boolean durableHistoryMutationSubmissionStarted;
    volatile boolean persistenceAlreadyCanonical;
    volatile boolean providerContinuationCancelled;

    SendJob(
            long jobId,
            UUID conversationId,
            SendRuntimeSnapshot runtime,
            List<Message> historySnapshot,
            ReasoningLevel reasoningLevel,
            boolean requestedWebSearch,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            String agentSystemPromptAppend
    ) {
        this(jobId, conversationId, runtime, historySnapshot, reasoningLevel, requestedWebSearch, agentModeEnabled,
                agentProjectRoot, agentSystemPromptAppend, false);
    }

    SendJob(
            long jobId,
            UUID conversationId,
            SendRuntimeSnapshot runtime,
            List<Message> historySnapshot,
            ReasoningLevel reasoningLevel,
            boolean requestedWebSearch,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            String agentSystemPromptAppend,
            boolean createsConversation
    ) {
        this.jobId = jobId;
        this.createsConversation = createsConversation;
        this.conversationId = createsConversation && conversationId == null ? UUID.randomUUID() : conversationId;
        this.userMessageId = UUID.randomUUID();
        this.userMessageTimestamp = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        this.userMessageOrdinal = historySnapshot.size() + 1;
        this.assistantMessageOrdinal = userMessageOrdinal + 1;
        this.runtime = runtime;
        this.apiKey = null;
        this.provider = null;
        this.historySnapshot = List.copyOf(historySnapshot);
        this.reasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        this.requestedWebSearch = requestedWebSearch;
        this.webSearchEnabled = runtime.webSearchOutcome().required()
                || runtime.webSearchOutcome().optional() && requestedWebSearch;
        this.agentModeEnabled = agentModeEnabled;
        this.agentProjectRoot = agentProjectRoot;
        this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
    }

    private SendJob(long jobId, SendJob source) {
        this.jobId = jobId;
        conversationId = source.conversationId;
        userMessageId = source.userMessageId;
        userMessageTimestamp = source.userMessageTimestamp;
        userMessageOrdinal = source.userMessageOrdinal;
        assistantMessageOrdinal = source.assistantMessageOrdinal;
        createsConversation = source.createsConversation;
        runtime = source.runtime;
        historySnapshot = source.historySnapshot;
        reasoningLevel = source.reasoningLevel;
        requestedWebSearch = source.requestedWebSearch;
        webSearchEnabled = source.webSearchEnabled;
        agentModeEnabled = source.agentModeEnabled;
        agentProjectRoot = source.agentProjectRoot;
        agentSystemPromptAppend = source.agentSystemPromptAppend;
        composerState = source.composerState;
        preparedUserMessage = source.preparedUserMessage;
        persistenceAlreadyCanonical = source.persistenceAlreadyCanonical;
        providerContinuationCancelled = source.providerContinuationCancelled;
    }

    static SendJob admittedContinuation(long jobId, SendJob source) {
        return new SendJob(jobId, source);
    }

    boolean isLive() {
        return !finished && !cancelled.get();
    }

    void clearCredentialReferences() {
        apiKey = null;
        provider = null;
    }
}
