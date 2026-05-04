package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexCliChatCompletionClientTest {

    private final CodexCliChatCompletionClient subject = new CodexCliChatCompletionClient();

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

    private String invokeBuildPrompt(List<Message> history) throws Exception {
        Method method = CodexCliChatCompletionClient.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);
        return (String) method.invoke(subject, history);
    }
}
