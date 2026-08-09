package com.github.drafael.chat4j.provider.support;

import static java.util.Collections.emptyList;

import com.formdev.flatlaf.util.SystemInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

public final class ProcessCommandSupport {

    private ProcessCommandSupport() {
    }

    public static void applyEnvironment(ProcessBuilder processBuilder, Map<String, String> environment) {
        Map<String, String> immutableEnvironment = Map.copyOf(environment);
        processBuilder.environment().clear();
        processBuilder.environment().putAll(immutableEnvironment);

        List<String> resolvedCommand = resolveCommand(processBuilder.command(), immutableEnvironment);
        processBuilder.command(resolvedCommand);
    }

    static List<String> resolveCommand(List<String> command, Map<String, String> environment) {
        if (ObjectUtils.isEmpty(command)) {
            return command == null ? emptyList() : command;
        }

        String executable = command.getFirst();
        if (StringUtils.isBlank(executable) || hasPathComponent(executable)) {
            return command;
        }

        String resolvedExecutable = resolveExecutable(executable.trim(), environment, false);
        if (StringUtils.isBlank(resolvedExecutable)) {
            return command;
        }

        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, resolvedExecutable);
        return List.copyOf(resolved);
    }

    private static boolean hasPathComponent(String executable) {
        return executable.contains("/") || executable.contains("\\");
    }

    public static Optional<String> findDirectExecutable(String executable, Map<String, String> environment) {
        if (StringUtils.isBlank(executable) || hasPathComponent(executable)) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolveExecutable(executable.trim(), Map.copyOf(environment), true));
    }

    private static String resolveExecutable(String executable, Map<String, String> environment, boolean directOnly) {
        String pathValue = environmentValue(environment, "PATH");
        if (StringUtils.isBlank(pathValue)) {
            return null;
        }

        for (String directory : pathValue.split(File.pathSeparator)) {
            String trimmedDirectory = directory == null ? "" : directory.trim();
            if (trimmedDirectory.isEmpty()) {
                continue;
            }

            Path candidate = Path.of(trimmedDirectory, executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)
                    && Files.isExecutable(candidate)
                    && (!directOnly || isDirectExecutable(candidate))
            ) {
                return candidate.toString();
            }

            if (SystemInfo.isWindows) {
                String fromPathext = resolveWindowsExecutable(candidate, environment, directOnly);
                if (fromPathext != null) {
                    return fromPathext;
                }
            }
        }

        return null;
    }

    private static String resolveWindowsExecutable(
            Path candidate,
            Map<String, String> environment,
            boolean directOnly
    ) {
        String executableName = candidate.getFileName().toString();
        if (executableName.contains(".")) {
            return null;
        }

        String pathExt = StringUtils.defaultIfBlank(
                environmentValue(environment, "PATHEXT"),
                ".EXE;.CMD;.BAT;.COM"
        );
        for (String ext : pathExt.split(";")) {
            String normalizedExt = ext == null ? "" : ext.trim();
            if (normalizedExt.isEmpty()) {
                continue;
            }

            Path withExtension = Path.of(candidate.toString() + normalizedExt.toLowerCase(Locale.ROOT));
            if (Files.isRegularFile(withExtension)
                    && Files.isExecutable(withExtension)
                    && (!directOnly || isDirectExecutable(withExtension))
            ) {
                return withExtension.toString();
            }
        }

        return null;
    }

    public static boolean isDirectExecutable(Path path) {
        String normalized = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !normalized.endsWith(".cmd") && !normalized.endsWith(".bat");
    }

    private static String environmentValue(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return exact;
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
