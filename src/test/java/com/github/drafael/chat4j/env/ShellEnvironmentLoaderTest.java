package com.github.drafael.chat4j.env;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShellEnvironmentLoaderTest {

    @Test
    @DisplayName("Interactive shell command includes -i to load zshrc-style exports")
    void shellEnvCommand_whenInteractive_includesInteractiveFlag() {
        var command = ShellEnvironmentLoader.shellEnvCommand("/bin/zsh", true);

        assertThat(command).containsExactly("/bin/zsh", "-l", "-i", "-c", "env");
    }

    @Test
    @DisplayName("Login-only shell command omits -i for fallback execution")
    void shellEnvCommand_whenLoginOnly_omitsInteractiveFlag() {
        var command = ShellEnvironmentLoader.shellEnvCommand("/bin/zsh", false);

        assertThat(command).containsExactly("/bin/zsh", "-l", "-c", "env");
    }

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
        var output = "NO_EQUALS_LINE\n=blankKey\nOPENAI_API_KEY=DUMMY_OPENAI_KEY_FOR_TESTS";

        var parsed = ShellEnvironmentLoader.parseEnvOutput(output);

        assertThat(parsed)
                .containsOnlyKeys("OPENAI_API_KEY")
                .containsEntry("OPENAI_API_KEY", "DUMMY_OPENAI_KEY_FOR_TESTS");
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

    @Test
    @DisplayName("Provider credential summary lists present key names without exposing values")
    void summarizeProviderCredentialNames_whenKnownKeysPresent_returnsSortedKeyNames() {
        var env = Map.of(
                "OPENAI_API_KEY", "DUMMY_OPENAI_KEY_FOR_TESTS",
                "ANTHROPIC_API_KEY", "DUMMY_ANTHROPIC_KEY_FOR_TESTS",
                "UNRELATED", "value"
        );

        var summary = ShellEnvironmentLoader.summarizeProviderCredentialNames(env);

        assertThat(summary).isEqualTo("ANTHROPIC_API_KEY, OPENAI_API_KEY");
    }

    @Test
    @DisplayName("Provider credential summary returns none when no known keys are present")
    void summarizeProviderCredentialNames_whenNoKnownKeysPresent_returnsNone() {
        var summary = ShellEnvironmentLoader.summarizeProviderCredentialNames(Map.of("UNRELATED", "value"));

        assertThat(summary).isEqualTo("none");
    }
}
