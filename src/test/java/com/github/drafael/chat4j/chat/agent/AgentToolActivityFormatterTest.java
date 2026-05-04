package com.github.drafael.chat4j.chat.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolActivityFormatterTest {

    @Test
    @DisplayName("Bash tool activity redacts sensitive command arguments")
    void started_whenBashCommandContainsSecrets_redactsSummary() {
        var request = new ToolInvocationRequest(
                "1",
                "bash",
                """
                {"command":"echo OPENAI_API_KEY=secret --token abc123"}
                """
        );

        AgentToolActivity activity = AgentToolActivityFormatter.started(request);

        assertThat(activity.argumentsSummary())
                .contains("OPENAI_API_KEY=<redacted>")
                .contains("--token <redacted>")
                .doesNotContain("secret")
                .doesNotContain("abc123");
    }

    @Test
    @DisplayName("Unknown tool activity omits raw arguments")
    void started_whenToolIsUnknown_omitsRawArguments() {
        var request = new ToolInvocationRequest(
                "1",
                "custom",
                "{\"apiKey\":\"secret\"}"
        );

        AgentToolActivity activity = AgentToolActivityFormatter.started(request);

        assertThat(activity.argumentsSummary())
                .isEqualTo("arguments omitted")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("Default tool activity callback is a no-op")
    void onToolActivity_whenCallbackIsNotProvided_isNoOp() {
        var subject = new AgentRunCallbacks(
                token -> {
                },
                thinking -> {
                },
                () -> {
                },
                error -> {
                }
        );

        subject.onToolActivity().accept(new AgentToolActivity("1", "read", AgentToolActivity.Status.STARTED, "path=note.txt", ""));

        assertThat(subject.onToolActivity()).isNotNull();
    }
}
