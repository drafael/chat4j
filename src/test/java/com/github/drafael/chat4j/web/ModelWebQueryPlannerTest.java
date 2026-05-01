package com.github.drafael.chat4j.web;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

import static org.assertj.core.api.Assertions.assertThat;

class ModelWebQueryPlannerTest {

    @Test
    @DisplayName("Model query planner parses JSON queries from provider output")
    void planQueries_whenProviderReturnsJsonQueries_returnsParsedQueries() {
        ModelWebQueryPlanner subject = new ModelWebQueryPlanner(
                providerReturning("{\"queries\":[\"java 25 features\",\"jdk 25 release notes\"]}"),
                emptyList()
        );

        assertThat(subject.planQueries("what is new in java 25", () -> false))
                .containsExactly("java 25 features", "jdk 25 release notes");
    }

    @Test
    @DisplayName("Model query planner falls back to the raw prompt when JSON parsing fails")
    void planQueries_whenProviderReturnsInvalidJson_fallsBackToRawPrompt() {
        ModelWebQueryPlanner subject = new ModelWebQueryPlanner(providerReturning("not json"), emptyList());

        assertThat(subject.planQueries("what is new in java 25", () -> false))
                .containsExactly("what is new in java 25");
    }

    private ProviderService providerReturning(String response) {
        return new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onToken.accept(response);
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "Test";
            }

            @Override
            public String envVarName() {
                return null;
            }
        };
    }
}
