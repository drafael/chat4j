package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class SendJob {
    final long jobId;
    volatile UUID conversationId;
    final String providerName;
    final String modelId;
    final String baseUrl;
    final String apiKey;
    final ProviderCapabilities capabilities;
    final ProviderService provider;
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
            String baseUrl,
            String apiKey,
            ProviderCapabilities capabilities,
            ProviderService provider,
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
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.capabilities = capabilities;
        this.provider = provider;
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
}
