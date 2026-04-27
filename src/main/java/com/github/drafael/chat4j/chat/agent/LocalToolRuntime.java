package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

public final class LocalToolRuntime {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 120;

    public ToolInvocationResult execute(@NonNull ToolInvocationRequest request, @NonNull Path projectRoot, BooleanSupplier isCancelled) {
        try {
            Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedProjectRoot)) {
                throw new IllegalArgumentException("Project root is not a directory: %s".formatted(projectRoot));
            }

            if (isCancelled != null && isCancelled.getAsBoolean()) {
                return ToolInvocationResult.failure(request, "Tool execution cancelled");
            }

            Map<String, Object> args = parseArguments(request.argumentsJson());
            String output = switch (request.name()) {
                case "read" -> handleRead(args, normalizedProjectRoot);
                case "write" -> handleWrite(args, normalizedProjectRoot);
                case "edit" -> handleEdit(args, normalizedProjectRoot);
                case "ls" -> handleLs(args, normalizedProjectRoot);
                case "find" -> handleFind(args, normalizedProjectRoot);
                case "grep" -> handleGrep(args, normalizedProjectRoot);
                case "bash" -> handleBash(args, normalizedProjectRoot, isCancelled);
                default -> throw new IllegalArgumentException("Unsupported tool: %s".formatted(request.name()));
            };

            return ToolInvocationResult.success(request, output);
        } catch (Exception e) {
            return ToolInvocationResult.failure(request, e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) throws Exception {
        if (StringUtils.isBlank(argumentsJson)) {
            return new LinkedHashMap<>();
        }

        return JSON.readValue(argumentsJson, new TypeReference<>() {
        });
    }

    private String handleRead(Map<String, Object> args, Path projectRoot) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "read.path should not be blank");

        Path target = resolvePath(projectRoot, pathValue);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Not a regular file: %s".formatted(pathValue));
        }

        return Files.readString(target, StandardCharsets.UTF_8);
    }

    private String handleWrite(Map<String, Object> args, Path projectRoot) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "write.path should not be blank");

        String content = StringUtils.defaultString(asString(args.get("content")));
        Path target = resolvePath(projectRoot, pathValue);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(target, content, StandardCharsets.UTF_8);
        return "Wrote %d bytes to %s".formatted(content.getBytes(StandardCharsets.UTF_8).length, projectRoot.relativize(target));
    }

    private String handleEdit(Map<String, Object> args, Path projectRoot) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "edit.path should not be blank");

        Path target = resolvePath(projectRoot, pathValue);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Not a regular file: %s".formatted(pathValue));
        }

        String content = Files.readString(target, StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = args.containsKey("edits")
                ? (List<Map<String, Object>>) args.get("edits")
                : emptyList();

        if (edits.isEmpty()) {
            String oldText = asString(args.get("oldText"));
            String newText = StringUtils.defaultString(asString(args.get("newText")));
            Validate.notBlank(oldText, "edit.oldText should not be blank");
            edits = List.of(Map.of("oldText", oldText, "newText", newText));
        }

        String updated = content;
        int replacements = 0;
        for (Map<String, Object> edit : edits) {
            String oldText = asString(edit.get("oldText"));
            String newText = StringUtils.defaultString(asString(edit.get("newText")));
            Validate.notBlank(oldText, "edit.oldText should not be blank");

            int first = updated.indexOf(oldText);
            if (first < 0) {
                throw new IllegalArgumentException("edit.oldText not found: %s".formatted(shorten(oldText, 40)));
            }
            int second = updated.indexOf(oldText, first + oldText.length());
            if (second >= 0) {
                throw new IllegalArgumentException("edit.oldText must match uniquely: %s".formatted(shorten(oldText, 40)));
            }

            updated = updated.replace(oldText, newText);
            replacements++;
        }

        Files.writeString(target, updated, StandardCharsets.UTF_8);
        return "Applied %d edit(s) to %s".formatted(replacements, projectRoot.relativize(target));
    }

    private String handleLs(Map<String, Object> args, Path projectRoot) throws Exception {
        String pathValue = asString(args.get("path"));
        Path target = resolvePath(projectRoot, StringUtils.defaultIfBlank(pathValue, "."));
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Not a directory: %s".formatted(pathValue));
        }

        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(path -> Files.isDirectory(path) ? "%s/".formatted(path.getFileName()) : path.getFileName().toString())
                    .collect(Collectors.joining("\n"));
        }
    }

    private String handleFind(Map<String, Object> args, Path projectRoot) throws Exception {
        String pathValue = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        Path root = resolvePath(projectRoot, pathValue);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: %s".formatted(pathValue));
        }

        String patternValue = StringUtils.defaultIfBlank(asString(args.get("pattern")), asString(args.get("name")));
        Pattern pattern = StringUtils.isBlank(patternValue)
                ? null
                : Pattern.compile(globToRegex(patternValue), Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> !path.equals(root))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (pattern == null || pattern.matcher(fileName).matches()) {
                            results.add(projectRoot.relativize(path).toString());
                        }
                    });
        }

        results.sort(String::compareToIgnoreCase);
        return String.join("\n", results);
    }

    private String handleGrep(Map<String, Object> args, Path projectRoot) throws Exception {
        String query = asString(args.get("query"));
        Validate.notBlank(query, "grep.query should not be blank");

        String pathValue = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        Path root = resolvePath(projectRoot, pathValue);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Path does not exist: %s".formatted(pathValue));
        }

        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains(query)) {
                            matches.add("%s:%d:%s".formatted(projectRoot.relativize(path), i + 1, line));
                        }
                    }
                } catch (Exception ignored) {
                    // best-effort grep; skip unreadable/binary files
                }
            });
        }

        return String.join("\n", matches);
    }

    private String handleBash(Map<String, Object> args, Path projectRoot, BooleanSupplier isCancelled) throws Exception {
        String command = asString(args.get("command"));
        Validate.notBlank(command, "bash.command should not be blank");

        if (isCancelled != null && isCancelled.getAsBoolean()) {
            throw new IllegalStateException("Tool execution cancelled");
        }

        int timeoutSeconds = parseTimeoutSeconds(args.get("timeoutSeconds"));
        ProcessResult processResult = new ProcessExecutor()
                .commandSplit(command)
                .directory(projectRoot.toFile())
                .exitValueAny()
                .readOutput(true)
                .timeout(timeoutSeconds, TimeUnit.SECONDS)
                .execute();

        String output = processResult.outputUTF8();
        return "exit=%d\n%s".formatted(processResult.getExitValue(), StringUtils.defaultString(output));
    }

    private int parseTimeoutSeconds(Object value) {
        if (value == null) {
            return DEFAULT_COMMAND_TIMEOUT_SECONDS;
        }

        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = Integer.parseInt(String.valueOf(value));
        }

        if (parsed <= 0) {
            return DEFAULT_COMMAND_TIMEOUT_SECONDS;
        }

        return Math.min(parsed, MAX_COMMAND_TIMEOUT_SECONDS);
    }

    private Path resolvePath(Path projectRoot, String pathValue) {
        Validate.notBlank(pathValue, "path should not be blank");

        Path root = projectRoot.toAbsolutePath().normalize();
        Path candidate = root.getFileSystem().getPath(pathValue);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }

        return resolved;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String globToRegex(String glob) {
        String escaped = Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
        return "^%s$".formatted(escaped);
    }

    private String shorten(String value, int max) {
        if (value == null || value.length() <= max) {
            return StringUtils.defaultString(value);
        }

        return "%s…".formatted(value.substring(0, max));
    }
}
