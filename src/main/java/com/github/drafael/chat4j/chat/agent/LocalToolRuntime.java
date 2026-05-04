package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.Math.min;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.joining;

@Slf4j
public final class LocalToolRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOL_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_READ_BYTES = MAX_TOOL_OUTPUT_BYTES;
    private static final int MAX_EDIT_FILE_BYTES = 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 1_000;
    private static final int MAX_FIND_RESULTS = 2_000;
    private static final int MAX_GREP_MATCHES = 1_000;
    private static final int MAX_GREP_FILE_BYTES = 1024 * 1024;
    private static final int MAX_GREP_LINE_CHARS = 1_000;
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 120;
    private static final Pattern BASH_PATH_TOKEN_PATTERN = Pattern.compile(
            "(?<![\\w.-])(/[^\\s'\"`;&|<>$()]+|(?:\\.\\./|\\./\\.\\./)[^\\s'\"`;&|<>$()]*)"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b([A-Z0-9_-]*(?:password|token|api[_-]?key|secret|client[_-]?secret)[A-Z0-9_-]*)\\s*=\\s*([^\\s;&|]+)"
    );
    private static final Pattern SENSITIVE_FLAG_PATTERN = Pattern.compile(
            "(?i)(--?(?:password|token|api[_-]?key|secret|client[_-]?secret)\\s+)[^'\"\\s;&|]+"
    );
    private static final Pattern AUTHORIZATION_BEARER_PATTERN = Pattern.compile("(?i)(authorization:\\s*bearer\\s+)[^'\"\\s;&|]+");

    public ToolInvocationResult execute(
            @NonNull ToolInvocationRequest request,
            @NonNull Path projectRoot,
            BooleanSupplier isCancelled
    ) {
        try {
            Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedProjectRoot)) {
                throw new IllegalArgumentException("Project root is not a directory: %s".formatted(projectRoot));
            }

            WorkspaceRoot workspaceRoot = new WorkspaceRoot(normalizedProjectRoot, normalizedProjectRoot.toRealPath());
            ensureNotCancelled(isCancelled);

            Map<String, Object> args = parseArguments(request.argumentsJson());
            String output = switch (request.name()) {
                case "read" -> handleRead(args, workspaceRoot, isCancelled);
                case "write" -> handleWrite(args, workspaceRoot, isCancelled);
                case "edit" -> handleEdit(args, workspaceRoot, isCancelled);
                case "ls" -> handleLs(args, workspaceRoot, isCancelled);
                case "find" -> handleFind(args, workspaceRoot, isCancelled);
                case "grep" -> handleGrep(args, workspaceRoot, isCancelled);
                case "bash" -> handleBash(args, workspaceRoot, isCancelled);
                default -> throw new IllegalArgumentException("Unsupported tool: %s".formatted(request.name()));
            };

            return ToolInvocationResult.success(request, limitOutput(output));
        } catch (Exception e) {
            return ToolInvocationResult.failure(request, e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) throws Exception {
        if (StringUtils.isBlank(argumentsJson)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> arguments = JSON.readValue(argumentsJson, new TypeReference<>() {
        });
        return arguments == null ? new LinkedHashMap<>() : arguments;
    }

    private String handleRead(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "read.path should not be blank");

        Path target = resolveExistingPath(workspaceRoot, pathValue);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Not a regular file: %s".formatted(pathValue));
        }

        ensureNotCancelled(isCancelled);
        return readLimitedUtf8File(target, MAX_READ_BYTES);
    }

    private String handleWrite(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "write.path should not be blank");

        String content = StringUtils.defaultString(asString(args.get("content")));
        Path target = resolveWritablePath(workspaceRoot, pathValue);
        ensureNotCancelled(isCancelled);

        Files.writeString(target, content, StandardCharsets.UTF_8);
        return "Wrote %d bytes to %s".formatted(
                content.getBytes(StandardCharsets.UTF_8).length,
                workspaceRoot.lexical().relativize(target)
        );
    }

    private String handleEdit(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String pathValue = asString(args.get("path"));
        Validate.notBlank(pathValue, "edit.path should not be blank");

        Path target = resolveExistingPath(workspaceRoot, pathValue);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Not a regular file: %s".formatted(pathValue));
        }
        if (Files.size(target) > MAX_EDIT_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large to edit safely: %s".formatted(pathValue));
        }

        String content = Files.readString(target, StandardCharsets.UTF_8);
        List<TextEdit> edits = parseTextEdits(args);
        List<Replacement> replacements = validateReplacements(content, edits);

        StringBuilder updated = new StringBuilder(content);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            Replacement replacement = replacements.get(i);
            updated.replace(replacement.start(), replacement.end(), replacement.newText());
        }

        ensureNotCancelled(isCancelled);
        Files.writeString(target, updated.toString(), StandardCharsets.UTF_8);
        return "Applied %d edit(s) to %s".formatted(
                replacements.size(),
                workspaceRoot.lexical().relativize(target)
        );
    }

    private String handleLs(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String pathValue = asString(args.get("path"));
        Path target = resolveExistingPath(workspaceRoot, StringUtils.defaultIfBlank(pathValue, "."));
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("Not a directory: %s".formatted(pathValue));
        }

        ensureNotCancelled(isCancelled);
        try (Stream<Path> stream = Files.list(target)) {
            List<String> entries = stream
                    .filter(path -> isInsideWorkspace(workspaceRoot, path))
                    .sorted(comparing(path -> path.getFileName().toString().toLowerCase()))
                    .limit(MAX_DIRECTORY_ENTRIES + 1L)
                    .map(path -> Files.isDirectory(path) ? "%s/".formatted(path.getFileName()) : path.getFileName().toString())
                    .toList();

            boolean truncated = entries.size() > MAX_DIRECTORY_ENTRIES;
            String output = entries.stream()
                    .limit(MAX_DIRECTORY_ENTRIES)
                    .collect(joining("\n"));
            return truncated ? appendTruncationNotice(output, "directory entries", MAX_DIRECTORY_ENTRIES) : output;
        }
    }

    private String handleFind(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String pathValue = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        Path root = resolveExistingPath(workspaceRoot, pathValue);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: %s".formatted(pathValue));
        }

        String patternValue = StringUtils.defaultIfBlank(asString(args.get("pattern")), asString(args.get("name")));
        Pattern pattern = StringUtils.isBlank(patternValue)
                ? null
                : Pattern.compile(globToRegex(patternValue), Pattern.CASE_INSENSITIVE);

        List<String> results = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> paths = stream.iterator();
            while (paths.hasNext()) {
                ensureNotCancelled(isCancelled);
                Path path = paths.next();
                if (path.equals(root) || !isInsideWorkspace(workspaceRoot, path)) {
                    continue;
                }

                String fileName = path.getFileName().toString();
                if (pattern == null || pattern.matcher(fileName).matches()) {
                    if (results.size() >= MAX_FIND_RESULTS) {
                        truncated = true;
                        break;
                    }
                    results.add(workspaceRoot.lexical().relativize(path).toString());
                }
            }
        }

        results.sort(String::compareToIgnoreCase);
        String output = String.join("\n", results);
        return truncated ? appendTruncationNotice(output, "find results", MAX_FIND_RESULTS) : output;
    }

    private String handleGrep(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String query = asString(args.get("query"));
        Validate.notBlank(query, "grep.query should not be blank");

        String pathValue = StringUtils.defaultIfBlank(asString(args.get("path")), ".");
        Path root = resolveExistingPath(workspaceRoot, pathValue);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Path does not exist: %s".formatted(pathValue));
        }

        List<String> matches = new ArrayList<>();
        int skippedLargeFiles = 0;
        boolean truncated = false;
        try (Stream<Path> stream = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
            Iterator<Path> paths = stream.iterator();
            while (paths.hasNext()) {
                ensureNotCancelled(isCancelled);
                Path path = paths.next();
                if (!isInsideWorkspace(workspaceRoot, path) || !Files.isRegularFile(path)) {
                    continue;
                }
                if (Files.size(path) > MAX_GREP_FILE_BYTES) {
                    skippedLargeFiles++;
                    continue;
                }

                if (collectGrepMatches(workspaceRoot, path, query, matches, isCancelled)) {
                    truncated = true;
                    break;
                }
            }
        }

        String output = String.join("\n", matches);
        if (truncated) {
            output = appendTruncationNotice(output, "grep matches", MAX_GREP_MATCHES);
        }
        if (skippedLargeFiles > 0) {
            output = appendNotice(
                    output,
                    "Skipped %d file(s) larger than %d bytes.".formatted(skippedLargeFiles, MAX_GREP_FILE_BYTES)
            );
        }
        return output;
    }

    private String handleBash(
            Map<String, Object> args,
            WorkspaceRoot workspaceRoot,
            BooleanSupplier isCancelled
    ) throws Exception {
        String command = asString(args.get("command"));
        Object requestedTimeoutSeconds = args.get("timeoutSeconds");
        String commandLogValue = redactCommandForDisplay(command);
        log.info(
                "Agent bash tool invocation (root={}, requestedTimeoutSeconds={}, command={})",
                workspaceRoot.lexical(),
                requestedTimeoutSeconds,
                commandLogValue
        );
        Validate.notBlank(command, "bash.command should not be blank");

        ensureNotCancelled(isCancelled);
        int timeoutSeconds = parseTimeoutSeconds(requestedTimeoutSeconds);
        warnIfBashMayAccessOutsideRoot(command, commandLogValue, workspaceRoot);
        BoundedOutputStream output = new BoundedOutputStream(MAX_TOOL_OUTPUT_BYTES);
        StartedProcess startedProcess = new ProcessExecutor()
                .command("bash", "-lc", command)
                .directory(workspaceRoot.lexical().toFile())
                .redirectErrorStream(true)
                .redirectOutput(output)
                .exitValueAny()
                .destroyOnExit()
                .start();

        ProcessResult processResult = awaitProcess(startedProcess, timeoutSeconds, isCancelled);
        String result = "exit=%d\n%s".formatted(processResult.getExitValue(), output.asUtf8String());
        if (output.isTruncated()) {
            return appendNotice(result, "truncated after %d bytes".formatted(MAX_TOOL_OUTPUT_BYTES));
        }
        return result;
    }

    private ProcessResult awaitProcess(
            StartedProcess startedProcess,
            int timeoutSeconds,
            BooleanSupplier isCancelled
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            ensureNotCancelledProcess(startedProcess, isCancelled);
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                destroyProcess(startedProcess);
                throw new TimeoutException("bash.command timed out after %d second(s)".formatted(timeoutSeconds));
            }

            try {
                return startedProcess.future().get(
                        min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS
                );
            } catch (TimeoutException e) {
                // Poll again so cancellation can stop the running process promptly.
            }
        }
    }

    private void ensureNotCancelledProcess(StartedProcess startedProcess, BooleanSupplier isCancelled) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            destroyProcess(startedProcess);
            throw new IllegalStateException("Tool execution cancelled");
        }
    }

    private void destroyProcess(StartedProcess startedProcess) {
        Process process = startedProcess.process();
        process.toHandle().descendants().forEach(this::destroyProcessHandle);
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void destroyProcessHandle(ProcessHandle processHandle) {
        processHandle.destroy();
        if (processHandle.isAlive()) {
            processHandle.destroyForcibly();
        }
    }

    private boolean collectGrepMatches(
            WorkspaceRoot workspaceRoot,
            Path path,
            String query,
            List<String> matches,
            BooleanSupplier isCancelled
    ) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                ensureNotCancelled(isCancelled);
                lineNumber++;
                if (line.contains(query)) {
                    if (matches.size() >= MAX_GREP_MATCHES) {
                        return true;
                    }
                    matches.add("%s:%d:%s".formatted(
                            workspaceRoot.lexical().relativize(path),
                            lineNumber,
                            shorten(line, MAX_GREP_LINE_CHARS)
                    ));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Best-effort grep; skip unreadable/binary files.
        }
        return false;
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

        return min(parsed, MAX_COMMAND_TIMEOUT_SECONDS);
    }

    private List<TextEdit> parseTextEdits(Map<String, Object> args) {
        if (!args.containsKey("edits")) {
            String oldText = asString(args.get("oldText"));
            String newText = StringUtils.defaultString(asString(args.get("newText")));
            Validate.notBlank(oldText, "edit.oldText should not be blank");
            return List.of(new TextEdit(oldText, newText));
        }

        Object rawEdits = args.get("edits");
        if (!(rawEdits instanceof List<?> editItems) || editItems.isEmpty()) {
            throw new IllegalArgumentException("edit.edits should be a non-empty array");
        }

        return editItems.stream()
                .map(this::parseTextEdit)
                .toList();
    }

    private TextEdit parseTextEdit(Object rawEdit) {
        if (!(rawEdit instanceof Map<?, ?> edit)) {
            throw new IllegalArgumentException("edit.edits entries should be objects");
        }

        String oldText = asString(edit.get("oldText"));
        String newText = StringUtils.defaultString(asString(edit.get("newText")));
        Validate.notBlank(oldText, "edit.oldText should not be blank");
        return new TextEdit(oldText, newText);
    }

    private List<Replacement> validateReplacements(String content, List<TextEdit> edits) {
        List<Replacement> replacements = edits.stream()
                .map(edit -> toReplacement(content, edit))
                .sorted(comparingInt(Replacement::start))
                .toList();

        int previousEnd = -1;
        for (Replacement replacement : replacements) {
            if (replacement.start() < previousEnd) {
                throw new IllegalArgumentException(
                        "edit.oldText ranges must not overlap: %s".formatted(shorten(replacement.oldText(), 40))
                );
            }
            previousEnd = replacement.end();
        }

        return replacements;
    }

    private Replacement toReplacement(String content, TextEdit edit) {
        int first = content.indexOf(edit.oldText());
        if (first < 0) {
            throw new IllegalArgumentException("edit.oldText not found: %s".formatted(shorten(edit.oldText(), 40)));
        }
        int second = content.indexOf(edit.oldText(), first + edit.oldText().length());
        if (second >= 0) {
            throw new IllegalArgumentException(
                    "edit.oldText must match uniquely: %s".formatted(shorten(edit.oldText(), 40))
            );
        }

        return new Replacement(first, first + edit.oldText().length(), edit.oldText(), edit.newText());
    }

    private Path resolveExistingPath(WorkspaceRoot workspaceRoot, String pathValue) throws Exception {
        Path resolved = resolveLexicalPath(workspaceRoot, pathValue);
        Path realPath = resolved.toRealPath();
        if (!realPath.startsWith(workspaceRoot.real())) {
            logPathEscape(workspaceRoot, pathValue, resolved, realPath);
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }
        return resolved;
    }

    private Path resolveWritablePath(WorkspaceRoot workspaceRoot, String pathValue) throws Exception {
        Path target = resolveLexicalPath(workspaceRoot, pathValue);
        if (Files.exists(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(workspaceRoot.real())) {
                logPathEscape(workspaceRoot, pathValue, target, realTarget);
                throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
            }
            return target;
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Path has no parent: %s".formatted(pathValue));
        }

        Path existingAncestor = parent;
        while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            logPathEscape(workspaceRoot, pathValue, target, null);
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }

        Path realAncestor = existingAncestor.toRealPath();
        if (!realAncestor.startsWith(workspaceRoot.real())) {
            logPathEscape(workspaceRoot, pathValue, existingAncestor, realAncestor);
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }

        Files.createDirectories(parent);
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(workspaceRoot.real())) {
            logPathEscape(workspaceRoot, pathValue, parent, realParent);
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }
        return target;
    }

    private Path resolveLexicalPath(WorkspaceRoot workspaceRoot, String pathValue) {
        Validate.notBlank(pathValue, "path should not be blank");

        Path candidate = workspaceRoot.lexical().getFileSystem().getPath(pathValue);
        Path resolved = (candidate.isAbsolute() ? candidate : workspaceRoot.lexical().resolve(candidate)).normalize();
        if (!resolved.startsWith(workspaceRoot.lexical())) {
            logPathEscape(workspaceRoot, pathValue, resolved, null);
            throw new IllegalArgumentException("Path escapes project root: %s".formatted(pathValue));
        }
        return resolved;
    }

    private boolean isInsideWorkspace(WorkspaceRoot workspaceRoot, Path path) {
        try {
            Path realPath = path.toRealPath();
            boolean insideWorkspace = realPath.startsWith(workspaceRoot.real());
            if (!insideWorkspace) {
                logPathEscape(workspaceRoot, path.toString(), path, realPath);
            }
            return insideWorkspace;
        } catch (Exception e) {
            return false;
        }
    }

    private void warnIfBashMayAccessOutsideRoot(String command, String commandLogValue, WorkspaceRoot workspaceRoot) {
        if (command.contains("~/") || command.contains("$HOME")) {
            log.warn(
                    "Agent bash command may access files outside selected root via home-directory reference (root={}, command={})",
                    workspaceRoot.lexical(),
                    commandLogValue
            );
        }

        BASH_PATH_TOKEN_PATTERN.matcher(command).results()
                .map(match -> match.group(1))
                .filter(StringUtils::isNotBlank)
                .filter(pathToken -> bashPathTokenEscapesRoot(pathToken, workspaceRoot))
                .findFirst()
                .ifPresent(pathToken -> log.warn(
                        "Agent bash command may access files outside selected root (root={}, pathToken={}, command={})",
                        workspaceRoot.lexical(),
                        pathToken,
                        commandLogValue
                ));
    }

    public static String redactCommandForDisplay(String command) {
        String commandValue = StringUtils.defaultString(command);
        String redactedAssignments = SENSITIVE_ASSIGNMENT_PATTERN.matcher(commandValue)
                .replaceAll(match -> "%s=<redacted>".formatted(match.group(1)));
        String redactedFlags = SENSITIVE_FLAG_PATTERN.matcher(redactedAssignments)
                .replaceAll(match -> "%s<redacted>".formatted(match.group(1)));
        return AUTHORIZATION_BEARER_PATTERN.matcher(redactedFlags)
                .replaceAll(match -> "%s<redacted>".formatted(match.group(1)));
    }

    private boolean bashPathTokenEscapesRoot(String pathToken, WorkspaceRoot workspaceRoot) {
        try {
            Path candidate = workspaceRoot.lexical().getFileSystem().getPath(pathToken);
            Path resolved = (candidate.isAbsolute() ? candidate : workspaceRoot.lexical().resolve(candidate)).normalize();
            if (!resolved.startsWith(workspaceRoot.lexical())) {
                return true;
            }

            if (Files.exists(resolved)) {
                return !resolved.toRealPath().startsWith(workspaceRoot.real());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void logPathEscape(WorkspaceRoot workspaceRoot, String requestedPath, Path resolvedPath, Path realPath) {
        log.warn(
                "Agent filesystem tool attempted to access path outside selected root (root={}, requestedPath={}, resolvedPath={}, realPath={})",
                workspaceRoot.lexical(),
                requestedPath,
                resolvedPath,
                realPath
        );
    }

    private String readLimitedUtf8File(Path target, int maxBytes) throws Exception {
        try (InputStream input = Files.newInputStream(target)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length <= maxBytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }

            return "%s\n\n[truncated after %d bytes]".formatted(
                    new String(bytes, 0, maxBytes, StandardCharsets.UTF_8),
                    maxBytes
            );
        }
    }

    private String limitOutput(String output) {
        String value = StringUtils.defaultString(output);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_TOOL_OUTPUT_BYTES) {
            return value;
        }

        return "%s\n\n[truncated after %d bytes]".formatted(
                new String(bytes, 0, MAX_TOOL_OUTPUT_BYTES, StandardCharsets.UTF_8),
                MAX_TOOL_OUTPUT_BYTES
        );
    }

    private String appendTruncationNotice(String output, String itemName, int limit) {
        return appendNotice(output, "Truncated %s after %d item(s).".formatted(itemName, limit));
    }

    private String appendNotice(String output, String notice) {
        String prefix = StringUtils.defaultString(output);
        if (StringUtils.isBlank(prefix)) {
            return "[%s]".formatted(notice);
        }
        return "%s\n\n[%s]".formatted(prefix, notice);
    }

    private void ensureNotCancelled(BooleanSupplier isCancelled) {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            throw new IllegalStateException("Tool execution cancelled");
        }
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

    private static final class BoundedOutputStream extends OutputStream {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        private BoundedOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized void write(int value) {
            if (buffer.size() >= maxBytes) {
                truncated = true;
                return;
            }

            buffer.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            int remaining = maxBytes - buffer.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }

            int bytesToWrite = min(length, remaining);
            buffer.write(bytes, offset, bytesToWrite);
            truncated = bytesToWrite < length;
        }

        private synchronized String asUtf8String() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private synchronized boolean isTruncated() {
            return truncated;
        }
    }

    private record WorkspaceRoot(Path lexical, Path real) {
    }

    private record TextEdit(String oldText, String newText) {
    }

    private record Replacement(int start, int end, String oldText, String newText) {
    }
}
