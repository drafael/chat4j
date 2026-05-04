package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderServiceAgentAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Provider service adapter applies agent project root as execution directory context")
    void executeTurn_whenProjectRootProvided_setsExecutionDirectoryContext() {
        AtomicReference<Path> observedDirectory = new AtomicReference<>();
        AtomicReference<String> observedPromptAppend = new AtomicReference<>();
        AtomicReference<List<Message>> observedHistory = new AtomicReference<>();
        ProviderService providerService = new ProviderService() {
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
                observedDirectory.set(ExecutionDirectoryContext.currentDirectory().orElse(null));
                observedPromptAppend.set(AgentSystemPromptContext.currentPromptAppend().orElse(""));
                observedHistory.set(history);
                onToken.accept("ok");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("model");
            }

            @Override
            public String name() {
                return "Test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        };

        ProviderServiceAgentAdapter subject = new ProviderServiceAgentAdapter(providerService, "Use concise summaries");
        List<AgentToolActivity> toolActivities = new ArrayList<>();

        AgentTurnResult result = subject.executeTurn(
                new AgentRunRequest(
                        List.of(Message.user("Describe project")),
                        ReasoningLevel.OFF,
                        tempDir,
                        emptyList(),
                        () -> false
                ),
                new AgentRunCallbacks(
                        token -> {
                        },
                        thinking -> {
                        },
                        toolActivities::add,
                        () -> {
                        },
                        error -> {
                        }
                )
        );

        assertThat(result.completed()).isTrue();
        assertThat(observedDirectory.get()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(observedPromptAppend.get()).isEqualTo("Use concise summaries");
        assertThat(observedHistory.get()).isNotNull();
        assertThat(observedHistory.get().getFirst().role()).isEqualTo(com.github.drafael.chat4j.provider.api.Role.SYSTEM);
        assertThat(observedHistory.get().getFirst().content()).contains("Selected agent root: " + tempDir.toAbsolutePath().normalize());
        assertThat(observedHistory.get().getFirst().content()).contains("Top-level entries");
        assertThat(toolActivities).hasSize(1);
        assertThat(toolActivities.getFirst().toolName()).isEqualTo("workspace-context");
        assertThat(toolActivities.getFirst().status()).isEqualTo(AgentToolActivity.Status.SUCCEEDED);
        assertThat(toolActivities.getFirst().argumentsSummary()).isEqualTo("path=%s".formatted(tempDir.getFileName()));
        assertThat(toolActivities.getFirst().message()).isEqualTo("using workspace snapshot");
        assertThat(ExecutionDirectoryContext.currentDirectory()).isEmpty();
        assertThat(AgentSystemPromptContext.currentPromptAppend()).isEmpty();
    }
}
