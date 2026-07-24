package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class SendJob {
    final long jobId;
    volatile UUID conversationId;
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
        this.jobId = jobId;
        this.conversationId = conversationId;
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

    boolean isLive() {
        return !finished && !cancelled.get();
    }

    void clearCredentialReferences() {
        apiKey = null;
        provider = null;
    }
}
