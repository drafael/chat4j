package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        var subject = new AgentOrchestrator(new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()), new LocalToolRuntime());
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

        var subject = new AgentOrchestrator(new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()), new LocalToolRuntime());
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
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicReference<List<ToolInvocationResult>> secondTurnResults = new AtomicReference<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
        List<AgentToolActivity> toolActivities = new ArrayList<>();
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
                        toolActivities::add,
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
        assertThat(toolActivities)
                .extracting(AgentToolActivity::status)
                .containsExactly(AgentToolActivity.Status.STARTED, AgentToolActivity.Status.SUCCEEDED);
        assertThat(toolActivities)
                .extracting(AgentToolActivity::toolName)
                .containsExactly("read", "read");
    }

    @Test
    @DisplayName("A worker interruption after an adapter callback suppresses completion")
    void streamCompletion_whenWorkerIsInterrupted_stopsCallbacks() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-interrupt");
        var completed = new AtomicBoolean();
        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
                    callbacks.onToken().accept("partial");
                    Thread.currentThread().interrupt();
                    return AgentTurnResult.complete();
                };
            }
        };
        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(
                List.of(Message.user("inspect workspace")),
                ReasoningLevel.OFF,
                projectRoot,
                emptyList(),
                () -> false
        );

        boolean interrupted;
        try {
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
                            ignored -> {
                            },
                            () -> completed.set(true),
                            error -> {
                            }
                    )
            );
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(interrupted).isTrue();
        assertThat(completed).isFalse();
    }

    @Test
    @DisplayName("Cancellation during a tool stops the remaining batch and late completion activity")
    void streamCompletion_whenCancelledDuringToolBatch_stopsRemainingTools() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-cancel-batch");
        Files.writeString(projectRoot.resolve("first.txt"), "first", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("second.txt"), "second", StandardCharsets.UTF_8);
        var cancelled = new AtomicBoolean();
        var turns = new AtomicInteger();
        List<AgentToolActivity> activities = new ArrayList<>();
        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
                            new ToolInvocationRequest("1", "read", "{\"path\":\"first.txt\"}"),
                            new ToolInvocationRequest("2", "read", "{\"path\":\"second.txt\"}")
                    ));
                };
            }
        };
        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(
                List.of(Message.user("read files")),
                ReasoningLevel.OFF,
                projectRoot,
                emptyList(),
                cancelled::get
        );

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
                        activity -> {
                            activities.add(activity);
                            if (activity.status() == AgentToolActivity.Status.STARTED) {
                                cancelled.set(true);
                            }
                        },
                        () -> {
                        },
                        error -> {
                        }
                )
        );

        assertThat(turns).hasValue(1);
        assertThat(activities)
                .extracting(AgentToolActivity::status)
                .containsExactly(AgentToolActivity.Status.STARTED);
        assertThat(activities)
                .extracting(AgentToolActivity::toolName)
                .containsExactly("read");
    }

    @Test
    @DisplayName("Agent orchestrator allows one final completion turn after max tool rounds")
    void streamCompletion_whenToolRoundsHitLimit_allowsFinalCompletionTurn() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-max-rounds-final");
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<List<ToolInvocationResult>> finalTurnResults = new AtomicReference<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool", StandardCharsets.UTF_8);

        AtomicInteger turns = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
        List<AgentToolActivity> toolActivities = new ArrayList<>();

        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
                        toolActivities::add,
                        () -> completed.set(true),
                        error::set
                )
        );

        assertThat(turns.get()).isEqualTo(4);
        assertThat(error.get()).isNull();
        assertThat(completed.get()).isTrue();
        assertThat(toolActivities)
                .extracting(AgentToolActivity::status)
                .contains(AgentToolActivity.Status.SKIPPED);
    }

    @Test
    @DisplayName("Cancellation during loop-guard activity suppresses remaining callbacks")
    void streamCompletion_whenCancelledDuringLoopGuard_stopsCallbacks() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-loop-guard-cancel");
        var cancelled = new AtomicBoolean();
        var turns = new AtomicInteger();
        List<AgentToolActivity> activities = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        var completed = new AtomicBoolean();
        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(ProviderAttachmentTestSupport.authority()) {
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
                            new ToolInvocationRequest("1", "ls", "{\"path\":\".\"}"),
                            new ToolInvocationRequest("2", "find", "{\"path\":\".\",\"pattern\":\"*.java\"}")
                    ));
                };
            }
        };
        var subject = new AgentOrchestrator(adapterFactory, new LocalToolRuntime());
        var request = new AgentRunRequest(
                List.of(Message.user("inspect workspace")),
                ReasoningLevel.OFF,
                projectRoot,
                emptyList(),
                cancelled::get
        );

        subject.streamCompletion(
                "Mistral",
                "devstral-latest",
                "https://api.mistral.ai/v1",
                "test-key",
                "",
                immediateProvider(),
                request,
                new AgentRunCallbacks(
                        tokens::add,
                        ignored -> {
                        },
                        activity -> {
                            activities.add(activity);
                            if (activity.status() == AgentToolActivity.Status.SKIPPED) {
                                cancelled.set(true);
                            }
                        },
                        () -> completed.set(true),
                        error -> {
                        }
                )
        );

        assertThat(turns).hasValue(3);
        assertThat(activities.stream()
                .filter(activity -> activity.status() == AgentToolActivity.Status.SKIPPED))
                .hasSize(1);
        assertThat(tokens).isEmpty();
        assertThat(completed).isFalse();
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

        };
    }
}
