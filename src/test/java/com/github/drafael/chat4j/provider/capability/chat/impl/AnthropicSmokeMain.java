package com.github.drafael.chat4j.provider.capability.chat.impl;

import org.apache.commons.lang3.StringUtils;

public final class AnthropicSmokeMain {

    private AnthropicSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (!AnthropicSmokeRunner.enabled()) {
            throw new IllegalStateException(AnthropicSmokeRunner.activationHelp());
        }

        AnthropicSmokeRunner.SmokeConfig config = AnthropicSmokeRunner.configFromEnvironment();
        if (StringUtils.isBlank(config.apiKey())) {
            throw new IllegalStateException("ANTHROPIC_API_KEY must be set. %s".formatted(AnthropicSmokeRunner.activationHelp()));
        }

        System.out.println("Running Anthropic smoke with %s".formatted(config));
        AnthropicSmokeRunner.SmokeResult result = new AnthropicSmokeRunner(config).runAll();
        System.out.println(result.summary());
    }
}
