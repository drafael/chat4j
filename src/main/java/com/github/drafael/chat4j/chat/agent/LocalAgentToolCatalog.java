package com.github.drafael.chat4j.chat.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public final class LocalAgentToolCatalog {

    private static final String BASH_DESCRIPTION = String.join(
            " ",
            "Execute a bash shell command with the project root as working directory.",
            "This command is not sandboxed and can access files outside the project root",
            "with the Chat4J app user's permissions."
    );
    private static final List<AgentToolDefinition> DEFINITIONS = List.of(
            definition("read", "Read a UTF-8 text file from the project", Map.of(
                    "path", stringProperty("Path to file, relative to project root")
            ), List.of("path")),
            definition("write", "Write UTF-8 text content to a file", Map.of(
                    "path", stringProperty("Path to file, relative to project root"),
                    "content", stringProperty("File content")
            ), List.of("path", "content")),
            definition("edit", "Apply exact text replacement edits in a file", Map.of(
                    "path", stringProperty("Path to file, relative to project root"),
                    "oldText", stringProperty("Old text to replace"),
                    "newText", stringProperty("Replacement text")
            ), List.of("path", "oldText", "newText")),
            definition("ls", "List files in a directory", Map.of(
                    "path", stringProperty("Directory path, defaults to .")
            ), emptyList()),
            definition("find", "Find files recursively by name pattern", Map.of(
                    "path", stringProperty("Directory path, defaults to ."),
                    "pattern", stringProperty("Glob-style pattern, for example *.java")
            ), emptyList()),
            definition("grep", "Search for text in files", Map.of(
                    "query", stringProperty("Text to search for"),
                    "path", stringProperty("Path to file or directory, defaults to .")
            ), List.of("query")),
            definition("bash", BASH_DESCRIPTION, Map.of(
                    "command", stringProperty("Command to execute"),
                    "timeoutSeconds", Map.of("type", "integer", "description", "Timeout in seconds")
            ), List.of("command"))
    );

    private LocalAgentToolCatalog() {
    }

    public static List<AgentToolDefinition> definitions() {
        return DEFINITIONS;
    }

    private static AgentToolDefinition definition(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return new AgentToolDefinition(name, description, schema, AgentToolSource.LOCAL);
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }
}
