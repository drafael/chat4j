package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport.authority;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void awaitTurnCompletion_whenTokenCallbackFails_doesNotPermitFallback() throws Exception {
        Method method = CodexCliChatCompletionClient.class.getDeclaredMethod(
                "awaitTurnCompletion",
                BufferedReader.class,
                Process.class,
                Consumer.class,
                BooleanSupplier.class,
                AtomicBoolean.class
        );
        method.setAccessible(true);
        var emittedOutput = new AtomicBoolean();
        var reader = new BufferedReader(new StringReader("""
                {"method":"item/agentMessage/delta","params":{"delta":"partial"}}
                """));
        Consumer<String> failingCallback = ignored -> {
            throw new IllegalStateException("callback failed");
        };

        assertThatThrownBy(() -> method.invoke(
                subject,
                reader,
                mock(Process.class),
                failingCallback,
                (BooleanSupplier) () -> false,
                emittedOutput
        )).isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(emittedOutput).isTrue();
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
