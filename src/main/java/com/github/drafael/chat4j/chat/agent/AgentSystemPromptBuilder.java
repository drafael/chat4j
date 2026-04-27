package com.github.drafael.chat4j.chat.agent;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class AgentSystemPromptBuilder {

    private static final String DEFAULT_WORKING_DIRECTORY = ".";

    private AgentSystemPromptBuilder() {
    }

    public static String buildToolAgentPrompt(Path projectRoot, String append) {
        String workingDirectory = resolveWorkingDirectory(projectRoot);

        String prompt = """
                You are an expert workspace assistant operating inside Chat4J Agent Mode.
                You help users inspect the selected agent root folder, run shell commands, edit files, and create new files when needed.
                The selected folder can contain any content type (code, docs, notes, data, configs, mixed assets). Do not assume it is a software project.

                Available tools:
                - read: Read a UTF-8 text file from the selected folder
                - write: Write UTF-8 text content to a file
                - edit: Apply exact text replacement edits in a file
                - ls: List files in a directory
                - find: Find files recursively by name pattern
                - grep: Search for text in files
                - bash: Execute a shell command within the selected folder root

                Guidelines:
                - For folder-specific requests, call at least one tool before your final answer.
                - Do not claim you explored/read/searched the folder unless you actually called tools.
                - Do not output preparatory promises like "let me explore"; call tools immediately instead.
                - Prefer ls/find/grep/read for fast folder exploration before broad bash commands.
                - Avoid repeating the same tool call with identical arguments unless the user asked for re-check.
                - If repeated tool outputs stop yielding new information, stop tool use and provide your best final answer.
                - Reference concrete file paths in your final answer when relevant.
                - Keep responses concise and actionable.
                """;

        String normalizedAppend = StringUtils.trimToEmpty(append);
        if (StringUtils.isNotBlank(normalizedAppend)) {
            prompt += "\n\nAdditional instructions:\n" + normalizedAppend;
        }

        return "%s\n\nCurrent date: %s\nCurrent working directory: %s"
                .formatted(prompt, LocalDate.now(), workingDirectory);
    }

    public static String buildCodexFallbackPrompt(Path projectRoot, String append) {
        String toolPrompt = buildToolAgentPrompt(projectRoot, append);

        List<String> lines = new ArrayList<>();
        lines.add(toolPrompt);
        lines.add("");
        lines.add("Codex fallback runtime notes:");
        lines.add("- You are running through Codex CLI fallback because provider-native tool calling is unavailable.");
        lines.add("- You may run read-only discovery commands to inspect the selected folder and answer the user request.");
        lines.add("- Do not modify files in fallback mode.");

        return String.join("\n", lines);
    }

    private static String resolveWorkingDirectory(Path projectRoot) {
        if (projectRoot == null) {
            return DEFAULT_WORKING_DIRECTORY;
        }

        return projectRoot.toAbsolutePath().normalize().toString();
    }
}
