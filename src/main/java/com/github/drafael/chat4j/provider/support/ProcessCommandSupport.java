package com.github.drafael.chat4j.provider.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ProcessCommandSupport {

    private ProcessCommandSupport() {
    }

    public static void applyShellEnvironment(ProcessBuilder processBuilder) {
        Map<String, String> mergedEnvironment = CredentialResolver.mergedEnvironment();
        processBuilder.environment().putAll(mergedEnvironment);

        List<String> resolvedCommand = resolveCommand(processBuilder.command(), mergedEnvironment);
        processBuilder.command(resolvedCommand);
    }

    static List<String> resolveCommand(List<String> command, Map<String, String> environment) {
        if (command == null || command.isEmpty()) {
            return command == null ? List.of() : command;
        }

        String executable = command.getFirst();
        if (executable == null || executable.isBlank() || hasPathComponent(executable)) {
            return command;
        }

        String resolvedExecutable = resolveExecutable(executable.trim(), environment);
        if (resolvedExecutable == null || resolvedExecutable.isBlank()) {
            return command;
        }

        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, resolvedExecutable);
        return List.copyOf(resolved);
    }

    private static boolean hasPathComponent(String executable) {
        return executable.contains("/") || executable.contains("\\");
    }

    private static String resolveExecutable(String executable, Map<String, String> environment) {
        String pathValue = environment.get("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }

        for (String directory : pathValue.split(File.pathSeparator)) {
            String trimmedDirectory = directory == null ? "" : directory.trim();
            if (trimmedDirectory.isEmpty()) {
                continue;
            }

            Path candidate = Path.of(trimmedDirectory, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }

            if (isWindows()) {
                String fromPathext = resolveWindowsExecutable(candidate, environment);
                if (fromPathext != null) {
                    return fromPathext;
                }
            }
        }

        return null;
    }

    private static String resolveWindowsExecutable(Path candidate, Map<String, String> environment) {
        String executableName = candidate.getFileName().toString();
        if (executableName.contains(".")) {
            return null;
        }

        String pathExt = environment.getOrDefault("PATHEXT", ".EXE;.CMD;.BAT;.COM");
        for (String ext : pathExt.split(";")) {
            String normalizedExt = ext == null ? "" : ext.trim();
            if (normalizedExt.isEmpty()) {
                continue;
            }

            Path withExtension = Path.of(candidate.toString() + normalizedExt.toLowerCase());
            if (Files.isRegularFile(withExtension) && Files.isExecutable(withExtension)) {
                return withExtension.toString();
            }
        }

        return null;
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }
}
