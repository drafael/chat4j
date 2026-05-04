package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.support.AgentSystemPromptContext;
import com.github.drafael.chat4j.provider.support.ExecutionDirectoryContext;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

final class ProviderServiceAgentAdapter implements AgentProviderAdapter {

    private static final int MAX_TOP_LEVEL_ENTRIES = 50;
    private static final int SAMPLE_WALK_DEPTH = 4;
    private static final int MAX_SCANNED_FILES = 200;
    private static final int MAX_SAMPLE_FILES = 8;
    private static final long MAX_EXCERPT_FILE_SIZE_BYTES = 256 * 1024;
    private static final int MAX_EXCERPT_CHARS = 1_800;
    private static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "rst", "org", "csv", "tsv",
            "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "xml", "html", "htm", "css", "scss", "sql", "log",
            "java", "kt", "kts", "groovy", "scala", "clj",
            "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "py", "rb", "php", "go", "rs", "swift", "c", "h", "cpp", "hpp", "cs", "sh", "zsh", "bash",
            "tex", "adoc", "wiki"
    );
    private static final Set<String> BINARY_FILE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tiff", "heic",
            "mp3", "wav", "flac", "aac", "m4a", "ogg",
            "mp4", "mkv", "mov", "avi", "webm",
            "zip", "gz", "tgz", "rar", "7z", "jar", "war", "class", "exe", "dll", "so", "dylib", "pdf"
    );

    private final ProviderService providerService;
    private final String agentSystemPromptAppend;

    ProviderServiceAgentAdapter(ProviderService providerService) {
        this(providerService, "");
    }

    ProviderServiceAgentAdapter(ProviderService providerService, String agentSystemPromptAppend) {
        this.providerService = providerService;
        this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
    }

    @Override
    public AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks) {
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        emitLocalToolsUnavailableActivity(request, callbacks);

        try (ExecutionDirectoryContext.Scope ignored = ExecutionDirectoryContext.open(request.projectRoot());
             AgentSystemPromptContext.Scope promptScope = AgentSystemPromptContext.open(agentSystemPromptAppend)) {
            providerService.streamCompletion(
                    augmentHistoryWithWorkspaceSnapshot(request.history(), request.projectRoot()),
                    request.reasoningLevel(),
                    callbacks.onToken(),
                    callbacks.onThinkingToken(),
                    () -> completed.set(true),
                    error -> {
                        failed.set(true);
                        callbacks.onError().accept(error);
                    },
                    request.isCancelled()
            );
        }

        if (failed.get()) {
            return new AgentTurnResult(false, emptyList());
        }

        if (!completed.get()) {
            completed.set(true);
        }

        return completed.get() ? AgentTurnResult.complete() : new AgentTurnResult(false, emptyList());
    }

    private void emitLocalToolsUnavailableActivity(AgentRunRequest request, AgentRunCallbacks callbacks) {
        if (request == null || request.projectRoot() == null || callbacks == null) {
            return;
        }

        callbacks.onToolActivity().accept(new AgentToolActivity(
                "",
                "workspace-context",
                AgentToolActivity.Status.SUCCEEDED,
                "path=%s".formatted(workspaceDisplayName(request.projectRoot())),
                "using workspace snapshot"
        ));
    }

    private String workspaceDisplayName(Path projectRoot) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        return normalizedRoot.getFileName() == null
                ? normalizedRoot.toString()
                : normalizedRoot.getFileName().toString();
    }

    private List<Message> augmentHistoryWithWorkspaceSnapshot(List<Message> history, Path rootFolder) {
        if (rootFolder == null || !Files.isDirectory(rootFolder)) {
            return history;
        }

        String snapshot = buildWorkspaceSnapshot(rootFolder);
        if (StringUtils.isBlank(snapshot)) {
            return history;
        }

        List<Message> augmented = new ArrayList<>();
        augmented.add(Message.system(snapshot));
        augmented.addAll(history);
        return List.copyOf(augmented);
    }

    private String buildWorkspaceSnapshot(Path rootFolder) {
        Path normalizedRoot = rootFolder.toAbsolutePath().normalize();
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("Agent Mode workspace snapshot from selected folder. Use this context when local tool-calling is unavailable.\n");
        snapshot.append("Selected agent root: ").append(normalizedRoot).append("\n\n");

        List<String> topLevelEntries = listTopLevelEntries(normalizedRoot);
        snapshot.append("Top-level entries:\n");
        if (topLevelEntries.isEmpty()) {
            snapshot.append("- (no visible entries)\n");
        } else {
            topLevelEntries.forEach(entry -> snapshot.append("- ").append(entry).append("\n"));
        }

        List<Path> sampleFiles = collectSampleReadableFiles(normalizedRoot);
        if (sampleFiles.isEmpty()) {
            snapshot.append("\nNo readable text samples detected quickly; folder may contain mostly binary files or unsupported formats.\n");
            return snapshot.toString();
        }

        snapshot.append("\nReadable file excerpts (truncated):\n");
        sampleFiles.forEach(path -> appendExcerpt(snapshot, normalizedRoot, path));
        return snapshot.toString();
    }

    private List<String> listTopLevelEntries(Path rootFolder) {
        try (Stream<Path> paths = Files.list(rootFolder)) {
            return paths
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .limit(MAX_TOP_LEVEL_ENTRIES)
                    .map(path -> {
                        String name = path.getFileName().toString();
                        return Files.isDirectory(path) ? name + "/" : name;
                    })
                    .toList();
        } catch (Exception ignored) {
            return emptyList();
        }
    }

    private List<Path> collectSampleReadableFiles(Path rootFolder) {
        try (Stream<Path> paths = Files.walk(rootFolder, SAMPLE_WALK_DEPTH)) {
            return paths
                    .filter(Files::isRegularFile)
                    .limit(MAX_SCANNED_FILES)
                    .filter(this::isReadableSampleCandidate)
                    .limit(MAX_SAMPLE_FILES)
                    .toList();
        } catch (Exception ignored) {
            return emptyList();
        }
    }

    private boolean isReadableSampleCandidate(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_EXCERPT_FILE_SIZE_BYTES) {
                return false;
            }

            return isLikelyTextFile(file);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLikelyTextFile(Path file) {
        String extension = fileExtension(file);
        if (BINARY_FILE_EXTENSIONS.contains(extension)) {
            return false;
        }

        if (TEXT_FILE_EXTENSIONS.contains(extension)) {
            return true;
        }

        return !containsNullByte(file);
    }

    private String fileExtension(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        return StringUtils.lowerCase(StringUtils.substringAfterLast(fileName, "."));
    }

    private boolean containsNullByte(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int inspected = Math.min(bytes.length, 1024);
            for (int i = 0; i < inspected; i++) {
                if (bytes[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void appendExcerpt(StringBuilder snapshot, Path rootFolder, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String excerpt = StringUtils.abbreviate(content, MAX_EXCERPT_CHARS);
            snapshot.append("\n### ")
                    .append(rootFolder.relativize(file))
                    .append("\n")
                    .append(excerpt)
                    .append("\n");
        } catch (Exception ignored) {
            // ignore unreadable files
        }
    }
}
