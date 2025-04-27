package com.github.drafael.chat4j.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShellEnvironmentLoaderTest {

    @Test
    @DisplayName("Environment parser keeps key-value pairs and preserves values containing equals signs")
    void parseEnvOutput_whenValueContainsEquals_preservesFullValue() {
        var output = "PATH=/usr/bin\nTOKEN=abc=def==\nLANG=en_US.UTF-8";

        var parsed = ShellEnvironmentLoader.parseEnvOutput(output);

        assertThat(parsed)
                .containsEntry("PATH", "/usr/bin")
                .containsEntry("TOKEN", "abc=def==")
                .containsEntry("LANG", "en_US.UTF-8");
    }

    @Test
    @DisplayName("Environment parser ignores malformed lines and blank keys")
    void parseEnvOutput_whenLinesAreMalformed_ignoresInvalidEntries() {
        var output = "NO_EQUALS_LINE\n=blankKey\nOPENAI_API_KEY=sk-test";

        var parsed = ShellEnvironmentLoader.parseEnvOutput(output);

        assertThat(parsed)
                .containsOnlyKeys("OPENAI_API_KEY")
                .containsEntry("OPENAI_API_KEY", "sk-test");
    }

    @Test
    @DisplayName("Environment parser keeps the last value when duplicate keys are present")
    void parseEnvOutput_whenDuplicateKeysExist_keepsLastValue() {
        var output = "OPENAI_API_KEY=first\nOPENAI_API_KEY=second";

        var parsed = ShellEnvironmentLoader.parseEnvOutput(output);

        assertThat(parsed)
                .containsOnlyKeys("OPENAI_API_KEY")
                .containsEntry("OPENAI_API_KEY", "second");
    }
}
