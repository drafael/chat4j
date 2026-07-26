package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class SendJob {
    final long jobId;
    volatile UUID conversationId;
    final UUID userMessageId;
    final Instant userMessageTimestamp;
    int userMessageOrdinal;
    int assistantMessageOrdinal;
    final boolean createsConversation;
    final String providerName;
    final String modelId;
    final ProviderRegistry.ProviderDef providerDefinition;
    final String baseUrl;
    volatile String apiKey;
    final ProviderCapabilities capabilities;
    volatile ProviderService provider;
    final List<Message> historySnapshot;
    final ReasoningLevel reasoningLevel;
    final boolean webSearchEnabled;
    final String webSearchOptionId;
    final int webBrowseTopN;
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
            String providerName,
            String modelId,
            ProviderRegistry.ProviderDef providerDefinition,
            String baseUrl,
            ProviderCapabilities capabilities,
            List<Message> historySnapshot,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled,
            String webSearchOptionId,
            int webBrowseTopN,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            String agentSystemPromptAppend
    ) {
        this(
                jobId,
                conversationId,
                providerName,
                modelId,
                providerDefinition,
                baseUrl,
                capabilities,
                historySnapshot,
                reasoningLevel,
                webSearchEnabled,
                webSearchOptionId,
                webBrowseTopN,
                agentModeEnabled,
                agentProjectRoot,
                agentSystemPromptAppend,
                false
        );
    }

    SendJob(
            long jobId,
            UUID conversationId,
            String providerName,
            String modelId,
            ProviderRegistry.ProviderDef providerDefinition,
            String baseUrl,
            ProviderCapabilities capabilities,
            List<Message> historySnapshot,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled,
            String webSearchOptionId,
            int webBrowseTopN,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            String agentSystemPromptAppend,
            boolean reserveConversationId
    ) {
        this.jobId = jobId;
        this.createsConversation = reserveConversationId && conversationId == null;
        this.conversationId = this.createsConversation ? UUID.randomUUID() : conversationId;
        this.userMessageId = UUID.randomUUID();
        this.userMessageTimestamp = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        this.userMessageOrdinal = historySnapshot.size() + 1;
        this.assistantMessageOrdinal = userMessageOrdinal + 1;
        this.providerName = providerName;
        this.modelId = modelId;
        this.providerDefinition = Objects.requireNonNull(providerDefinition, "providerDefinition should not be null");
        this.baseUrl = baseUrl;
        this.apiKey = null;
        this.capabilities = capabilities;
        this.provider = null;
        this.historySnapshot = List.copyOf(historySnapshot);
        this.reasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        this.webSearchEnabled = webSearchEnabled;
        this.webSearchOptionId = webSearchOptionId;
        this.webBrowseTopN = webBrowseTopN;
        this.agentModeEnabled = agentModeEnabled;
        this.agentProjectRoot = agentProjectRoot;
        this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
    }

    SendJob(long jobId, SendJob source) {
        this.jobId = jobId;
        conversationId = source.conversationId;
        userMessageId = source.userMessageId;
        userMessageTimestamp = source.userMessageTimestamp;
        userMessageOrdinal = source.userMessageOrdinal;
        assistantMessageOrdinal = source.assistantMessageOrdinal;
        createsConversation = source.createsConversation;
        providerName = source.providerName;
        modelId = source.modelId;
        providerDefinition = source.providerDefinition;
        baseUrl = source.baseUrl;
        capabilities = source.capabilities;
        historySnapshot = source.historySnapshot;
        reasoningLevel = source.reasoningLevel;
        webSearchEnabled = source.webSearchEnabled;
        webSearchOptionId = source.webSearchOptionId;
        webBrowseTopN = source.webBrowseTopN;
        agentModeEnabled = source.agentModeEnabled;
        agentProjectRoot = source.agentProjectRoot;
        agentSystemPromptAppend = source.agentSystemPromptAppend;
        composerState = source.composerState;
        preparedUserMessage = source.preparedUserMessage;
        persistenceAlreadyCanonical = source.persistenceAlreadyCanonical;
        providerContinuationCancelled = source.providerContinuationCancelled;
    }

    boolean isLive() {
        return !finished && !cancelled.get();
    }

    void clearCredentialReferences() {
        apiKey = null;
        provider = null;
    }
}
