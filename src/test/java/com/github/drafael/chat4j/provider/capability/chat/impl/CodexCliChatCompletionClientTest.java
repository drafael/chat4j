package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport.authority;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexCliChatCompletionClientTest {

    private final ProviderAttachmentSupport attachmentSupport = authority();
    private final CodexCliChatCompletionClient subject = new CodexCliChatCompletionClient(emptyMap(), attachmentSupport);

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Build prompt in normal mode keeps no-command safety instruction")
    void buildPrompt_whenNoExecutionDirectory_keepsNoCommandInstruction() throws Exception {
        String prompt = invokeBuildPrompt(List.of(Message.user("describe current project")));

        assertThat(prompt).contains("Do not execute commands or modify files");
    }

    @Test
    @DisplayName("Build prompt in agent fallback includes project root and read-only discovery guidance")
    void buildPrompt_whenExecutionDirectoryPresent_includesProjectRootAndDiscoveryInstruction() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-root"));

        String prompt;
        try (ExecutionDirectoryContext.Scope ignored = ExecutionDirectoryContext.open(projectRoot);
             AgentSystemPromptContext.Scope promptScope = AgentSystemPromptContext.open("Always mention module layout")) {
            prompt = invokeBuildPrompt(List.of(Message.user("describe current project")));
        }

        assertThat(prompt).contains("expert workspace assistant operating inside Chat4J Agent Mode");
        assertThat(prompt).contains(projectRoot.toAbsolutePath().normalize().toString());
        assertThat(prompt).contains("You may inspect the selected folder and answer the user request");
        assertThat(prompt).contains("Always mention module layout");
        assertThat(prompt).doesNotContain("Do not execute commands or modify files");
    }

    @Test
    @DisplayName("Codex output is marked emitted before the application callback runs")
    void awaitTurnCompletion_whenTokenCallbackFails_doesNotPermitFallback() {
        var emittedOutput = new AtomicBoolean();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/agentMessage/delta","params":{"delta":"partial"}}
                """));
        Consumer<String> failingCallback = ignored -> {
            throw new IllegalStateException("callback failed");
        };

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                failingCallback,
                ignored -> {},
                ignored -> {},
                ignored -> {},
                () -> false,
                emittedOutput
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("callback failed");
        assertThat(emittedOutput).isTrue();
    }

    @Test
    @DisplayName("App server commands force live search for enabled requests")
    void appServerCommand_whenWebSearchEnabled_forcesLiveMode() {
        assertThat(subject.appServerCommand(true)).containsExactly(
                "codex",
                "-c",
                "web_search=\"live\"",
                "app-server",
                "--listen",
                "stdio://"
        );
    }

    @Test
    @DisplayName("App server commands force disabled search for ordinary requests")
    void appServerCommand_whenWebSearchDisabled_forcesDisabledMode() {
        assertThat(subject.appServerCommand(false)).containsExactly(
                "codex",
                "-c",
                "web_search=\"disabled\"",
                "app-server",
                "--listen",
                "stdio://"
        );
    }

    @Test
    @DisplayName("Exec fallback preserves live search and JSON event output")
    void execCommand_whenWebSearchEnabled_preservesLiveModeAndJsonOutput() {
        Path outputFile = tempDir.resolve("response.txt");

        assertThat(subject.execCommand("gpt-5.4-mini", outputFile, ReasoningLevel.OFF, true)).containsExactly(
                "codex",
                "-c",
                "web_search=\"live\"",
                "exec",
                "--json",
                "--sandbox",
                "read-only",
                "--ephemeral",
                "-m",
                "gpt-5.4-mini",
                "-o",
                outputFile.toString(),
                "-"
        );
    }

    @Test
    @DisplayName("Exec fallback preserves disabled search for ordinary requests")
    void execCommand_whenWebSearchDisabled_preservesDisabledMode() {
        Path outputFile = tempDir.resolve("response.txt");

        assertThat(subject.execCommand("gpt-5.4-mini", outputFile, ReasoningLevel.OFF, false))
                .contains("web_search=\"disabled\"");
    }

    @Test
    @DisplayName("Codex cleanup destroys subprocess descendants before the root process")
    void destroyProcessTree_whenWrapperHasDescendants_destroysChildrenBeforeRoot() {
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.isAlive()).thenReturn(true);

        subject.destroyProcessTree(process);

        var order = inOrder(child, process);
        order.verify(child).destroyForcibly();
        order.verify(process).destroyForcibly();
    }

    @Test
    @DisplayName("Codex cleanup retains descendants after the wrapper process exits")
    void destroyProcessTree_whenWrapperExits_retainsDescendantsForRetry() {
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child), Stream.empty());
        when(process.isAlive()).thenReturn(true, false);

        subject.destroyProcessTree(process);
        subject.destroyProcessTree(process);

        verify(child, times(2)).destroyForcibly();
    }

    @Test
    @DisplayName("Codex cleanup discovers descendants spawned by a tracked wrapper")
    void destroyProcessTree_whenTrackedWrapperSpawnsDescendant_destroysNestedChild() {
        Process process = mock(Process.class);
        ProcessHandle wrapper = mock(ProcessHandle.class);
        ProcessHandle nestedChild = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(wrapper), Stream.empty());
        when(process.isAlive()).thenReturn(true, false);
        when(wrapper.pid()).thenReturn(1L);
        when(wrapper.descendants()).thenReturn(Stream.of(nestedChild));
        when(nestedChild.pid()).thenReturn(2L);

        subject.destroyProcessTree(process);
        subject.destroyProcessTree(process);

        verify(nestedChild).destroyForcibly();
    }

    @Test
    @DisplayName("App-server requests are ephemeral, non-interactive, read-only, and use selected reasoning")
    @SuppressWarnings("unchecked")
    void appServerRequests_whenCreated_includeHostControlledRuntimePolicy() {
        Map<String, Object> threadParams = (Map<String, Object>) subject.threadStartRequest("gpt-5.4-mini").get("params");
        Map<String, Object> turnParams = (Map<String, Object>) subject
                .turnStartRequest("thread-1", "prompt", ReasoningLevel.HIGH)
                .get("params");
        Map<String, Object> maxTurnParams = (Map<String, Object>) subject
                .turnStartRequest("thread-1", "prompt", ReasoningLevel.MAX)
                .get("params");
        Map<String, Object> ultraTurnParams = (Map<String, Object>) subject
                .turnStartRequest("thread-1", "prompt", ReasoningLevel.ULTRA)
                .get("params");

        assertThat(threadParams)
                .containsEntry("ephemeral", true)
                .containsEntry("approvalPolicy", "never")
                .containsEntry("sandbox", "read-only")
                .containsEntry("model", "gpt-5.4-mini");
        assertThat(turnParams).containsEntry("effort", "high");
        assertThat(maxTurnParams).containsEntry("effort", "max");
        assertThat(ultraTurnParams).containsEntry("effort", "ultra");
    }

    @Test
    @DisplayName("Exec fallback maps selected reasoning effort explicitly")
    void execCommand_whenReasoningIsEnabled_includesMappedEffort() {
        Path outputFile = tempDir.resolve("response.txt");

        assertThat(subject.execCommand("gpt-5.4-mini", outputFile, ReasoningLevel.EXTRA_HIGH, false))
                .containsSubsequence("-c", "model_reasoning_effort=\"xhigh\"");
        assertThat(subject.execCommand("gpt-5.6-luna", outputFile, ReasoningLevel.MAX, false))
                .containsSubsequence("-c", "model_reasoning_effort=\"max\"");
        assertThat(subject.execCommand("gpt-5.6-sol", outputFile, ReasoningLevel.ULTRA, false))
                .containsSubsequence("-c", "model_reasoning_effort=\"ultra\"");
    }

    @Test
    @DisplayName("App server streams selected reasoning summaries")
    void awaitTurnCompletion_whenReasoningSummaryArrives_emitsThinkingToken() throws Exception {
        var emittedOutput = new AtomicBoolean();
        var tokens = new ArrayList<String>();
        var thinkingTokens = new ArrayList<String>();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/reasoning/summaryTextDelta","params":{"delta":"Checked sources"}}
                {"method":"item/agentMessage/delta","params":{"delta":"Final answer"}}
                {"method":"turn/completed","params":{"turn":{"status":"completed"}}}
                """));

        subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.HIGH,
                tokens::add,
                thinkingTokens::add,
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                emittedOutput
        );

        assertThat(tokens).containsExactly("Final answer");
        assertThat(thinkingTokens).containsExactly("Checked sources");
        assertThat(emittedOutput).isTrue();
    }

    @Test
    @DisplayName("A completed app-server turn without assistant output is not accepted as success")
    void awaitTurnCompletion_whenTurnCompletesWithoutAssistantOutput_throws() {
        var reader = new BufferedReader(new StringReader("""
                {"method":"turn/completed","params":{"turn":{"status":"completed"}}}
                """));

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                new AtomicBoolean()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("codex app-server completed without assistant output");
    }

    @Test
    @DisplayName("Malformed app-server output fails instead of being silently discarded")
    void awaitTurnCompletion_whenOutputIsMalformed_throws() {
        var reader = new BufferedReader(new StringReader("""
                not-json
                {"method":"item/agentMessage/delta","params":{"delta":"Answer"}}
                {"method":"turn/completed","params":{"turn":{"status":"completed"}}}
                """));

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                new AtomicBoolean()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("codex app-server returned malformed JSON");
    }

    @Test
    @DisplayName("Suppressed reasoning still blocks fallback after an app-server failure")
    void awaitTurnCompletion_whenReasoningIsObservedWhileDisabled_marksOutputEmitted() {
        var emittedOutput = new AtomicBoolean();
        var thinkingTokens = new ArrayList<String>();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/reasoning/summaryTextDelta","params":{"delta":"Internal reasoning"}}
                {"method":"turn/completed","params":{"turn":{"status":"failed","error":{"message":"turn failed"}}}}
                """));

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                ignored -> {
                },
                thinkingTokens::add,
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                emittedOutput
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turn failed");

        assertThat(thinkingTokens).isEmpty();
        assertThat(emittedOutput).isTrue();
    }

    @Test
    @DisplayName("App-server EOF is detected even while the process remains alive")
    void awaitTurnCompletion_whenStdoutClosesBeforeProcessExit_failsPromptly() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        var reader = new BufferedReader(new StringReader(""));
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
            try {
                subject.awaitTurnCompletion(
                        reader,
                        process,
                        ReasoningLevel.OFF,
                        ignored -> {
                        },
                        ignored -> {
                        },
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> false,
                        new AtomicBoolean()
                );
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, command -> Thread.ofVirtual().start(command));

        assertThatThrownBy(() -> completion.get(2, TimeUnit.SECONDS))
                .hasRootCauseMessage("codex app-server stopped before turn completed");
    }

    @Test
    @DisplayName("An interrupted app-server turn is not accepted as successful")
    void awaitTurnCompletion_whenTurnIsInterrupted_throws() {
        var reader = new BufferedReader(new StringReader("""
                {"method":"turn/completed","params":{"turn":{"status":"interrupted"}}}
                """));

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                new AtomicBoolean()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("codex app-server turn did not complete: interrupted");
    }

    @Test
    @DisplayName("A temporary Codex home contains only the admitted Chat4J OAuth account")
    void createCodexHome_whenCredentialIsChatGptJwt_writesIsolatedAuthFile() throws Exception {
        String claims = """
                {"https://api.openai.com/auth":{"chatgpt_account_id":"account-123"}}
                """;
        String credential = "%s.%s.signature".formatted(
                Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))
        );
        Path codexHome = subject.createCodexHome(credential);
        try {
            var auth = new ObjectMapper().readTree(codexHome.resolve("auth.json").toFile());

            assertThat(auth.path("auth_mode").asText()).isEqualTo("chatgpt");
            assertThat(auth.path("tokens").path("account_id").asText()).isEqualTo("account-123");
            assertThat(auth.path("tokens").path("access_token").asText()).isEqualTo(credential);
            assertThat(auth.path("tokens").path("id_token").asText()).isEqualTo(credential);
        } finally {
            subject.deleteCodexHome(codexHome);
        }
        assertThat(codexHome).doesNotExist();
    }

    @Test
    @DisplayName("A temporary Codex home writes non-JWT credentials as API-key auth")
    void createCodexHome_whenCredentialIsApiKey_writesApiKeyAuthFile() throws Exception {
        Path codexHome = subject.createCodexHome("sk-test-key");
        try {
            var auth = new ObjectMapper().readTree(codexHome.resolve("auth.json").toFile());

            assertThat(auth.path("auth_mode").asText()).isEqualTo("apikey");
            assertThat(auth.path("OPENAI_API_KEY").asText()).isEqualTo("sk-test-key");
            assertThat(auth.has("tokens")).isFalse();
        } finally {
            subject.deleteCodexHome(codexHome);
        }
        assertThat(codexHome).doesNotExist();
    }

    @Test
    @DisplayName("Codex home cleanup tolerates SQLite sidecar files disappearing concurrently")
    void deleteCodexHome_whenSidecarFilesDisappearConcurrently_doesNotFail() throws Exception {
        Path codexHome = Files.createDirectory(tempDir.resolve("codex-cleanup-race"));
        List<Path> sidecars = IntStream.range(0, 500)
                .mapToObj(index -> codexHome.resolve("goals_%d.sqlite-shm".formatted(index)))
                .toList();
        sidecars.forEach(path -> {
            try {
                Files.writeString(path, "state");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread concurrentCleanup = Thread.startVirtualThread(() -> sidecars.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }));

        try {
            assertThatCode(() -> subject.deleteCodexHome(codexHome)).doesNotThrowAnyException();
            concurrentCleanup.join();
            assertThat(codexHome).doesNotExist();
        } finally {
            concurrentCleanup.join();
            subject.deleteCodexHome(codexHome);
        }
    }

    @Test
    @DisplayName("App server emits only deduplicated opened pages as consulted sources")
    void awaitTurnCompletion_whenWebSearchItemsArrive_emitsOnlyOpenedPageSources() throws Exception {
        var emittedOutput = new AtomicBoolean();
        var tokens = new ArrayList<String>();
        var queries = new ArrayList<String>();
        var sources = new ArrayList<WebSearchSource>();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/started","params":{"item":{"type":"webSearch","action":{"type":"search","query":null,"queries":["release notes","Java news"],"url":"https://ignored.example/result"}}}}
                {"method":"item/completed","params":{"item":{"type":"webSearch","action":{"type":"openPage","url":"https://docs.example/source"}}}}
                {"method":"item/completed","params":{"item":{"type":"webSearch","actions":[{"type":"open_page","url":"https://docs.example/source"}]}}}
                {"method":"item/completed","params":{"item":{"type":"agentMessage","text":"Answer with [link](https://answer.example/not-a-source)"}}}
                {"method":"turn/completed","params":{"turn":{"status":"completed"}}}
                """));

        subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                tokens::add,
                ignored -> {
                },
                queries::add,
                sources::add,
                () -> false,
                emittedOutput
        );

        assertThat(tokens).containsExactly("Answer with [link](https://answer.example/not-a-source)");
        assertThat(queries).containsExactly("release notes", "Java news");
        assertThat(sources).containsExactly(new WebSearchSource("docs.example", "https://docs.example/source"));
        assertThat(emittedOutput).isTrue();
    }

    @Test
    @DisplayName("An emitted app-server source prevents fallback after a later turn failure")
    void awaitTurnCompletion_whenSourcePrecedesFailure_marksOutputEmitted() {
        var emittedOutput = new AtomicBoolean();
        var sources = new ArrayList<WebSearchSource>();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/completed","params":{"item":{"type":"webSearch","action":{"type":"openPage","url":"https://docs.example/source"}}}}
                {"method":"turn/completed","params":{"turn":{"status":"failed","error":{"message":"search failed"}}}}
                """));

        assertThatThrownBy(() -> subject.awaitTurnCompletion(
                reader,
                mock(Process.class),
                ReasoningLevel.OFF,
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                sources::add,
                () -> false,
                emittedOutput
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search failed");

        assertThat(sources).containsExactly(new WebSearchSource("docs.example", "https://docs.example/source"));
        assertThat(emittedOutput).isTrue();
    }

    @Test
    @DisplayName("Exec JSON events emit observed queries and only opened pages")
    void emitExecWebSearchEvents_whenJsonLinesContainMixedActions_emitsQueriesAndOpenedPages() {
        var queries = new ArrayList<String>();
        var sources = new ArrayList<WebSearchSource>();
        String output = """
                not-json diagnostic
                {"type":"item.completed","item":{"type":"web_search","query":"direct query"}}
                {"type":"item.completed","item":{"type":"web_search","action":{"type":"search","query":"release notes","url":"https://ignored.example/result"}}}
                {"type":"item.completed","item":{"type":"web_search","action":{"type":"open_page","url":"https://docs.example/one"}}}
                {"type":"item.completed","item":{"type":"agent_message","text":"[answer](https://answer.example/link)"}}
                {"type":"item.completed","params":{"item":{"type":"webSearch","actions":[{"type":"openPage","url":"https://docs.example/two"}]}}}
                """;

        subject.emitExecWebSearchEvents(output, queries::add, sources::add, () -> false);

        assertThat(queries).containsExactly("direct query", "release notes");
        assertThat(sources).containsExactly(
                new WebSearchSource("docs.example", "https://docs.example/one"),
                new WebSearchSource("docs.example", "https://docs.example/two")
        );
    }

    @Test
    @DisplayName("Cancelled exec parsing suppresses consulted sources")
    void emitExecWebSearchSources_whenCancelled_suppressesSources() {
        var sources = new ArrayList<WebSearchSource>();
        String output = """
                {"type":"item.completed","item":{"type":"web_search","action":{"type":"open_page","url":"https://docs.example/source"}}}
                """;

        subject.emitExecWebSearchEvents(output, ignored -> {
        }, sources::add, () -> true);

        assertThat(sources).isEmpty();
    }

    private String invokeBuildPrompt(List<Message> history) throws Exception {
        AttachmentProjectionPlan projection = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );
        Method method = CodexCliChatCompletionClient.class.getDeclaredMethod(
                "buildPrompt",
                AttachmentProjectionPlan.class
        );
        method.setAccessible(true);
        return (String) method.invoke(subject, projection);
    }
}
