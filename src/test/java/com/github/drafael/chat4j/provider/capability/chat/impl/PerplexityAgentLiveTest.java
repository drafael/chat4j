package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CHAT4J_PERPLEXITY_LIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "PERPLEXITY_API_KEY", matches = ".+")
class PerplexityAgentLiveTest {

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"fast", "sonar-reasoning-pro"})
    @DisplayName("The real Agent API produces an answer and safe source metadata through the production client")
    void streamCompletion_liveSyncAndBackgroundResearch_returnsCitedAnswer(String selection) throws Exception {
        var subject = new PerplexityChatCompletionClient(new ProviderAttachmentSupport(tempDir));
        var runtime = new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity", AuthType.ENV_VAR, "PERPLEXITY_API_KEY", null, "https://api.perplexity.ai",
                        PerplexityModelIds.AGENT_EFFORTS, ProviderCapabilities.chatAndModels(), value -> value
                ),
                "PERPLEXITY_API_KEY", "https://api.perplexity.ai", System.getenv("PERPLEXITY_API_KEY"), selection
        );
        var output = new StringBuilder();
        List<CitationRef> citations = new ArrayList<>();

        subject.streamCompletion(
                runtime,
                List.of(Message.user("Use web search to find the official Java 21 documentation URL. Answer in one sentence and cite its source.")),
                ReasoningLevel.MEDIUM,
                WebSearchRequestOptions.disabled(),
                output::append,
                ignored -> {
                },
                ignored -> {
                },
                citations::add,
                () -> false,
                ignored -> {
                },
                () -> {
                }
        );

        assertThat(output).isNotEmpty();
        assertThat(citations).isNotEmpty().allSatisfy(citation -> {
            assertThat(citation.url()).startsWith("https://");
            assertThat(citation.number()).isPositive();
        });
        assertThat(output.toString()).contains("Sources:");
    }
}
