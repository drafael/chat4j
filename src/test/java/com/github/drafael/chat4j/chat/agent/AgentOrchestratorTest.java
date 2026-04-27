package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOrchestratorTest {

    @Test
    @DisplayName("Agent orchestrator rejects runs when project root is missing")
    void streamCompletion_whenProjectRootIsMissing_throwsIllegalStateException() {
        var subject = AgentOrchestrator.createDefault();
        var provider = immediateProvider();
        var request = new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, null, emptyList(), () -> false);

        assertThatThrownBy(() -> subject.streamCompletion(
                "OpenAI",
                "gpt-5-mini",
                "https://api.openai.com/v1",
                "test-key",
                "",
                provider,
                request,
                new AgentRunCallbacks(token -> {
                }, thinking -> {
                }, () -> {
                }, error -> {
                })
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid project folder");
    }

    @Test
    @DisplayName("Agent orchestrator delegates to provider adapter when request is valid")
    void streamCompletion_whenRequestIsValid_delegatesToProviderStream() throws Exception {
        var invoked = new AtomicBoolean(false);
        var token = new AtomicBoolean(false);
        var completed = new AtomicBoolean(false);

        ProviderService provider = new ProviderService() {
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
                invoked.set(true);
                onToken.accept("pong");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        };

        Path projectRoot = Files.createTempDirectory("chat4j-agent-test");
        var request = new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.MEDIUM, projectRoot, emptyList(), () -> false);
        var callbacks = new AgentRunCallbacks(
                ignored -> token.set(true),
                ignored -> {
                },
                () -> completed.set(true),
                error -> {
                }
        );

        var subject = AgentOrchestrator.createDefault();
        subject.streamCompletion(
                "Custom Provider",
                "",
                "",
                "",
                "",
                provider,
                request,
                callbacks
        );

        assertThat(invoked.get()).isTrue();
        assertThat(token.get()).isTrue();
        assertThat(completed.get()).isTrue();
    }

    @Test
    @DisplayName("Agent orchestrator executes tool requests and continues loop with tool results")
    void streamCompletion_whenAdapterRequestsTools_executesToolsAndContinuesLoop() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-loop");
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", java.nio.charset.StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicReference<List<ToolInvocationResult>> secondTurnResults = new AtomicReference<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory() {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    int turn = turns.incrementAndGet();
                    if (turn == 1) {
                        callbacks.onToken().accept("Running tool...\n");
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest("1", "read", "{\"path\":\"note.txt\"}")
                        ));
                    }

                    secondTurnResults.set(request.toolResults());
                    callbacks.onToken().accept("Done");
                    return AgentTurnResult.complete();
                };
            }
        };

        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, projectRoot, emptyList(), () -> false);

        AtomicBoolean completed = new AtomicBoolean(false);
        subject.streamCompletion(
                "OpenAI",
                "gpt-5-mini",
                "https://api.openai.com/v1",
                "test-key",
                "",
                immediateProvider(),
                request,
                new AgentRunCallbacks(
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> completed.set(true),
                        error -> {
                        }
                )
        );

        assertThat(turns.get()).isEqualTo(2);
        assertThat(secondTurnResults.get()).hasSize(1);
        assertThat(secondTurnResults.get().getFirst().success()).isTrue();
        assertThat(secondTurnResults.get().getFirst().output()).contains("hello tool");
        assertThat(completed.get()).isTrue();
    }

    @Test
    @DisplayName("Agent orchestrator allows one final completion turn after max tool rounds")
    void streamCompletion_whenToolRoundsHitLimit_allowsFinalCompletionTurn() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-max-rounds-final");
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", java.nio.charset.StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<ToolInvocationResult>> finalTurnResults = new AtomicReference<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory() {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    int turn = turns.incrementAndGet();
                    if (turn <= 8) {
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest("1", "read", "{\"path\":\"note.txt\"}")
                        ));
                    }

                    finalTurnResults.set(request.toolResults());
                    return AgentTurnResult.complete();
                };
            }
        };

        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, projectRoot, emptyList(), () -> false);

        subject.streamCompletion(
                "Mistral",
                "devstral-latest",
                "https://api.mistral.ai/v1",
                "test-key",
                "",
                immediateProvider(),
                request,
                new AgentRunCallbacks(
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> completed.set(true),
                        error::set
                )
        );

        assertThat(turns.get()).isEqualTo(9);
        assertThat(error.get()).isNull();
        assertThat(completed.get()).isTrue();
        assertThat(finalTurnResults.get()).hasSize(1);
        assertThat(finalTurnResults.get().getFirst().success()).isTrue();
    }

    @Test
    @DisplayName("Agent orchestrator reports error when tool loop still exceeds max rounds after final turn")
    void streamCompletion_whenToolLoopNeverSettles_reportsMaxRoundsError() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-max-rounds-error");
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", java.nio.charset.StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory() {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    turns.incrementAndGet();
                    return AgentTurnResult.continueWithTools(List.of(
                            new ToolInvocationRequest("1", "bash", "{\"command\":\"pwd\"}")
                    ));
                };
            }
        };

        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(List.of(Message.user("ping")), ReasoningLevel.OFF, projectRoot, emptyList(), () -> false);

        subject.streamCompletion(
                "Mistral",
                "devstral-latest",
                "https://api.mistral.ai/v1",
                "test-key",
                "",
                immediateProvider(),
                request,
                new AgentRunCallbacks(
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> completed.set(true),
                        error::set
                )
        );

        assertThat(turns.get()).isEqualTo(9);
        assertThat(completed.get()).isFalse();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum rounds")
                .hasMessageContaining("provider=Mistral")
                .hasMessageContaining("model=devstral-latest")
                .hasMessageContaining("requestedTools=bash");
    }

    @Test
    @DisplayName("Agent loop guard injects guidance for repeated read-only tool batches")
    void streamCompletion_whenReadOnlyToolBatchRepeats_appliesLoopGuardAndCompletes() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-loop-guard");

        AtomicInteger turns = new AtomicInteger(0);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Exception> error = new AtomicReference<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory() {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    int turn = turns.incrementAndGet();
                    if (turn <= 3) {
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest("1", "ls", "{\"path\":\".\"}")
                        ));
                    }

                    List<ToolInvocationResult> toolResults = request.toolResults();
                    assertThat(toolResults).hasSize(1);
                    assertThat(toolResults.getFirst().success()).isTrue();
                    assertThat(toolResults.getFirst().output()).contains("LOOP_GUARD");

                    return AgentTurnResult.complete();
                };
            }
        };

        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(List.of(Message.user("inspect workspace")), ReasoningLevel.OFF, projectRoot, emptyList(), () -> false);

        subject.streamCompletion(
                "Mistral",
                "devstral-latest",
                "https://api.mistral.ai/v1",
                "test-key",
                "",
                immediateProvider(),
                request,
                new AgentRunCallbacks(
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> completed.set(true),
                        error::set
                )
        );

        assertThat(turns.get()).isEqualTo(4);
        assertThat(error.get()).isNull();
        assertThat(completed.get()).isTrue();
    }

    private ProviderService immediateProvider() {
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
                onToken.accept("pong");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        };
    }
}
