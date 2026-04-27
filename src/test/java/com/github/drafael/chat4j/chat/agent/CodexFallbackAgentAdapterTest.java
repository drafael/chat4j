package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class CodexFallbackAgentAdapterTest {

    @Test
    @DisplayName("Falls back to Codex CLI adapter on insufficient quota errors")
    void executeTurn_whenPrimaryReturnsInsufficientQuotaError_fallsBackToCliAdapter() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onError().accept(new IllegalStateException(
                    "OpenAI Codex tool-calling is unavailable due to insufficient_quota (HTTP 429)."
            ));
            return new AgentTurnResult(false, emptyList());
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            callbacks.onToken().accept("cli-response");
            return AgentTurnResult.complete();
        };

        CodexFallbackAgentAdapter subject = new CodexFallbackAgentAdapter(primary, fallback);

        List<String> tokens = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(
                        tokens::add,
                        thinking -> {
                        },
                        () -> completed.set(true),
                        error -> {
                        }
                )
        );

        assertThat(fallbackInvoked.get()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(completed.get()).isFalse();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst()).contains("Falling back to Codex CLI");
        assertThat(tokens.getFirst()).contains(Path.of(".").toAbsolutePath().normalize().toString());
        assertThat(tokens.get(1)).isEqualTo("cli-response");
    }

    @Test
    @DisplayName("Does not fall back for non-quota errors")
    void executeTurn_whenPrimaryReturnsOtherError_doesNotFallback() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
        AtomicBoolean errorForwarded = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onError().accept(new IllegalStateException("OpenAI tool turn failed with HTTP 500"));
            return new AgentTurnResult(false, emptyList());
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            return AgentTurnResult.complete();
        };

        CodexFallbackAgentAdapter subject = new CodexFallbackAgentAdapter(primary, fallback);

        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(
                        token -> {
                        },
                        thinking -> {
                        },
                        () -> {
                        },
                        error -> errorForwarded.set(true)
                )
        );

        assertThat(fallbackInvoked.get()).isFalse();
        assertThat(errorForwarded.get()).isTrue();
        assertThat(result.completed()).isFalse();
    }
}
