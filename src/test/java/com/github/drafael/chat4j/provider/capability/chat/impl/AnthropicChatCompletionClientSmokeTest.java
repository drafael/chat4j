package com.github.drafael.chat4j.provider.capability.chat.impl;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnthropicChatCompletionClientSmokeTest {

    @Test
    @DisplayName("Anthropic live SDK smoke covers listing, streaming, reasoning, web search, images, and cancellation")
    void runAll_whenAnthropicSmokeIsEnabled_exercisesLiveAnthropicSdk() throws Exception {
        assumeTrue(AnthropicSmokeRunner.enabled(), AnthropicSmokeRunner.activationHelp());

        AnthropicSmokeRunner.SmokeConfig config = AnthropicSmokeRunner.configFromEnvironment();
        assertThat(config.apiKey())
                .as("%s must be set when Anthropic smoke testing is enabled", "ANTHROPIC_API_KEY")
                .isNotBlank();

        AnthropicSmokeRunner.SmokeResult result = new AnthropicSmokeRunner(config).runAll();

        assertThat(StringUtils.trimToEmpty(result.summary())).isNotBlank();
    }
}
