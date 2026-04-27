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

class OpenAiCompatibleFallbackAgentAdapterTest {

    @Test
    @DisplayName("Falls back to provider service adapter on HTTP 400 tool failure")
    void executeTurn_whenToolTurnFailsWithHttp400_fallsBackToProviderService() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onError().accept(new IllegalStateException("Google AI tool turn failed with HTTP 400"));
            return new AgentTurnResult(false, emptyList());
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            callbacks.onToken().accept("provider-response");
            return AgentTurnResult.complete();
        };

        OpenAiCompatibleFallbackAgentAdapter subject = new OpenAiCompatibleFallbackAgentAdapter(
                "Google AI",
                primary,
                fallback
        );

        List<String> tokens = new ArrayList<>();
        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(tokens::add, thinking -> {
                }, () -> {
                }, error -> {
                })
        );

        assertThat(fallbackInvoked.get()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst()).contains("Google AI tool API is unavailable");
        assertThat(tokens.get(1)).isEqualTo("provider-response");
    }

    @Test
    @DisplayName("Falls back on timeout tool failure")
    void executeTurn_whenToolTurnTimesOut_fallsBackToProviderService() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onError().accept(new IllegalStateException("LM Studio tool turn timed out after 240 seconds"));
            return new AgentTurnResult(false, emptyList());
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            callbacks.onToken().accept("provider-response");
            return AgentTurnResult.complete();
        };

        OpenAiCompatibleFallbackAgentAdapter subject = new OpenAiCompatibleFallbackAgentAdapter(
                "LM Studio",
                primary,
                fallback
        );

        List<String> tokens = new ArrayList<>();
        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(tokens::add, thinking -> {
                }, () -> {
                }, error -> {
                })
        );

        assertThat(fallbackInvoked.get()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst()).contains("LM Studio tool API is unavailable");
        assertThat(tokens.get(1)).isEqualTo("provider-response");
    }

    @Test
    @DisplayName("Falls back when tool adapter completes without tool invocations on first project turn")
    void executeTurn_whenPrimaryCompletesWithoutToolsOnFirstTurn_fallsBack() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onToken().accept("Sure! Let me explore the project structure.");
            return AgentTurnResult.complete();
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            callbacks.onToken().accept("project-summary");
            return AgentTurnResult.complete();
        };

        OpenAiCompatibleFallbackAgentAdapter subject = new OpenAiCompatibleFallbackAgentAdapter(
                "GitHub Copilot",
                primary,
                fallback
        );

        List<String> tokens = new ArrayList<>();
        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("describe current project")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(tokens::add, thinking -> {
                }, () -> {
                }, error -> {
                })
        );

        assertThat(fallbackInvoked.get()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst()).contains("Falling back to provider chat mode");
        assertThat(tokens.get(1)).isEqualTo("project-summary");
    }

    @Test
    @DisplayName("Does not fall back when primary adapter already emitted content")
    void executeTurn_whenPrimaryEmitsContent_doesNotFallback() {
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);

        AgentProviderAdapter primary = (request, callbacks) -> {
            callbacks.onToken().accept("partial");
            callbacks.onError().accept(new IllegalStateException("HTTP 400"));
            return new AgentTurnResult(false, emptyList());
        };
        AgentProviderAdapter fallback = (request, callbacks) -> {
            fallbackInvoked.set(true);
            return AgentTurnResult.complete();
        };

        OpenAiCompatibleFallbackAgentAdapter subject = new OpenAiCompatibleFallbackAgentAdapter(
                "Google AI",
                primary,
                fallback
        );

        AtomicBoolean forwardedError = new AtomicBoolean(false);
        List<String> tokens = new ArrayList<>();
        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, Path.of("."), emptyList(), () -> false),
                new AgentRunCallbacks(tokens::add, thinking -> {
                }, () -> {
                }, error -> forwardedError.set(true))
        );

        assertThat(fallbackInvoked.get()).isFalse();
        assertThat(forwardedError.get()).isTrue();
        assertThat(tokens).containsExactly("partial");
        assertThat(result.completed()).isFalse();
    }
}
