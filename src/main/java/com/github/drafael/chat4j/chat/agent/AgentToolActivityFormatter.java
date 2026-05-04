package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

final class AgentToolActivityFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUMMARY_CHARS = 180;
    private static final int MAX_MESSAGE_CHARS = 120;

    private AgentToolActivityFormatter() {
    }

    static AgentToolActivity started(@NonNull ToolInvocationRequest request) {
        return new AgentToolActivity(
                request.id(),
                request.name(),
                AgentToolActivity.Status.STARTED,
                summarizeArguments(request),
                ""
        );
    }

    static AgentToolActivity completed(
            @NonNull ToolInvocationRequest request,
            @NonNull ToolInvocationResult result
    ) {
        return new AgentToolActivity(
                request.id(),
                request.name(),
                result.success() ? AgentToolActivity.Status.SUCCEEDED : AgentToolActivity.Status.FAILED,
                summarizeArguments(request),
                result.success() ? "" : shorten(StringUtils.defaultIfBlank(result.error(), "Tool failed"), MAX_MESSAGE_CHARS)
        );
    }

    static AgentToolActivity skipped(@NonNull ToolInvocationRequest request, String message) {
        return new AgentToolActivity(
                request.id(),
                request.name(),
                AgentToolActivity.Status.SKIPPED,
                summarizeArguments(request),
                shorten(StringUtils.defaultIfBlank(message, "Skipped"), MAX_MESSAGE_CHARS)
        );
    }

    private static String summarizeArguments(ToolInvocationRequest request) {
        Map<String, Object> args = parseArguments(request.argumentsJson());
        String summary = switch (StringUtils.lowerCase(request.name())) {
            case "read", "write" -> pathSummary(args);
            case "edit" -> editSummary(args);
            case "ls" -> "path=%s".formatted(StringUtils.defaultIfBlank(asString(args.get("path")), "."));
            case "find" -> findSummary(args);
            case "grep" -> grepSummary(args);
            case "bash" -> bashSummary(args);
            default -> "arguments omitted";
        };
        return shorten(summary, MAX_SUMMARY_CHARS);
    }

    private static String pathSummary(Map<String, Object> args) {
        return "path=%s".formatted(StringUtils.defaultIfBlank(asString(args.get("path")), "?"));
    }

    private static String editSummary(Map<String, Object> args) {
        Object edits = args.get("edits");
        String editCount = edits instanceof Iterable<?> iterable
                ? ", edits=%d".formatted(count(iterable))
                : "";
        return "%s%s".formatted(pathSummary(args), editCount);
    }

    private static String findSummary(Map<String, Object> args) {
        String path = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        String pattern = StringUtils.defaultIfBlank(asString(args.get("pattern")), asString(args.get("name")));
        return StringUtils.isBlank(pattern)
                ? "path=%s".formatted(path)
                : "path=%s, pattern=%s".formatted(path, pattern);
    }

    private static String grepSummary(Map<String, Object> args) {
        String path = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        String query = StringUtils.defaultIfBlank(asString(args.get("query")), "?");
        return "path=%s, query=%s".formatted(path, query);
    }

    private static String bashSummary(Map<String, Object> args) {
        String command = LocalToolRuntime.redactCommandForDisplay(asString(args.get("command")));
        return "command=%s".formatted(StringUtils.defaultIfBlank(command, "?"));
    }

    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (StringUtils.isBlank(argumentsJson)) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, Object> arguments = JSON.readValue(argumentsJson, new TypeReference<>() {
            });
            return arguments == null ? new LinkedHashMap<>() : arguments;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static int count(Iterable<?> items) {
        return (int) StreamSupport.stream(items.spliterator(), false).count();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String shorten(String value, int max) {
        String text = StringUtils.defaultString(value);
        if (text.length() <= max) {
            return text;
        }
        return "%s…".formatted(text.substring(0, max));
    }
}
