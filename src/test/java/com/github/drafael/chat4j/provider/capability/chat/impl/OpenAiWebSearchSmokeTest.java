package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenAiWebSearchSmokeTest {

    private static final String ENABLED_PROPERTY = "chat4j.smoke.openaiWebSearch";
    private static final String MODEL_PROPERTY = "chat4j.smoke.openaiWebSearch.model";
    private static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final String BASE_URL = "https://api.openai.com/v1";

    @Test
    @DisplayName("OpenAI live Web Search emits answer text and claim-linked citation spans")
    void streamCompletion_whenOpenAiWebSearchSmokeIsEnabled_emitsNativeCitations() throws Exception {
        assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), activationHelp());
        String apiKey = System.getenv(API_KEY_ENV);
        assertThat(apiKey)
                .as("%s must be set when OpenAI Web Search smoke testing is enabled", API_KEY_ENV)
                .isNotBlank();
        String model = System.getProperty(MODEL_PROPERTY, "gpt-5-mini");
        ProviderRuntime runtime = runtime(apiKey, model);
        List<String> tokens = new ArrayList<>();
        List<CitationRef> citations = new ArrayList<>();
        var subject = new OpenAiChatCompletionClient(ProviderAttachmentTestSupport.authority());

        subject.streamCompletion(
                runtime,
                List.of(Message.user("Search the web for the current OpenAI API documentation domain and cite the source.")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
                tokens::add,
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

        assertThat(String.join("", tokens)).isNotBlank();
        assertThat(citations)
                .isNotEmpty()
                .anySatisfy(citation -> {
                    assertThat(citation.number()).isPositive();
                    assertThat(citation.url()).startsWith("http");
                    assertThat(citation.responseStartIndex()).isNotNull();
                    assertThat(citation.responseEndIndex()).isGreaterThan(citation.responseStartIndex());
                });
    }

    private ProviderRuntime runtime(String apiKey, String model) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "OpenAI",
                        AuthType.ENV_VAR,
                        API_KEY_ENV,
                        null,
                        BASE_URL,
                        emptyList(),
                        ProviderCapabilities.chatAndModels(),
                        UnaryOperator.identity()
                ),
                null,
                BASE_URL,
                StringUtils.defaultString(apiKey),
                model,
                emptyList()
        );
    }

    private String activationHelp() {
        return "Enable with -D%s=true and set %s; optionally select a model with -D%s=<model>."
                .formatted(ENABLED_PROPERTY, API_KEY_ENV, MODEL_PROPERTY);
    }
}
