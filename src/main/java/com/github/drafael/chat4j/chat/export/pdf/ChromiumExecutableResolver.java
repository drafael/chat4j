package com.github.drafael.chat4j.chat.export.pdf;

import com.formdev.flatlaf.util.SystemInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.findDirectExecutable;
import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.isDirectExecutable;

public final class ChromiumExecutableResolver {

    public static final String PUPPETEER_EXECUTABLE_PATH = "PUPPETEER_EXECUTABLE_PATH";
    private static final List<String> PATH_EXECUTABLE_NAMES = List.of(
            "google-chrome-stable",
            "google-chrome",
            "chromium",
            "chromium-browser",
            "chrome",
            "msedge"
    );

    private ChromiumExecutableResolver() {
    }

    public static Optional<String> discover(@NonNull Map<String, String> environment) {
        Optional<String> configuredEnvironment = directPath(environmentValue(environment, PUPPETEER_EXECUTABLE_PATH));
        if (configuredEnvironment.isPresent()) {
            return configuredEnvironment;
        }
        Optional<String> fromPath = PATH_EXECUTABLE_NAMES.stream()
                .map(name -> findDirectExecutable(name, environment))
                .flatMap(Optional::stream)
                .findFirst();
        return fromPath.isPresent() ? fromPath : standardLocations(environment).stream()
                .map(ChromiumExecutableResolver::directPath)
                .flatMap(Optional::stream)
                .findFirst();
    }

    public static Map<String, String> withExecutable(
            @NonNull Map<String, String> environment,
            String configuredPath
    ) {
        String executable = configuredExecutable(configuredPath, environment)
                .or(() -> discover(environment))
                .orElse("");
        if (executable.isEmpty()) {
            return Map.copyOf(environment);
        }
        Map<String, String> resolved = new LinkedHashMap<>(environment);
        resolved.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(PUPPETEER_EXECUTABLE_PATH));
        resolved.put(PUPPETEER_EXECUTABLE_PATH, executable);
        return Map.copyOf(resolved);
    }

    private static Optional<String> configuredExecutable(String configuredPath, Map<String, String> environment) {
        String configured = StringUtils.trimToEmpty(configuredPath);
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        if (!configured.contains("/") && !configured.contains("\\")) {
            return findDirectExecutable(configured, environment).or(() -> Optional.of(configured));
        }
        try {
            return Optional.of(Path.of(configured).toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) {
            return Optional.of(configured);
        }
    }

    private static Optional<String> directPath(String value) {
        if (StringUtils.isBlank(value)) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(value.trim()).toAbsolutePath().normalize();
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate) && isDirectExecutable(candidate)
                    ? Optional.of(candidate.toString())
                    : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<String> standardLocations(Map<String, String> environment) {
        if (SystemInfo.isMacOS) {
            String home = StringUtils.defaultIfBlank(environmentValue(environment, "HOME"), System.getProperty("user.home"));
            return List.of(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "%s/Applications/Google Chrome.app/Contents/MacOS/Google Chrome".formatted(home),
                    "/Applications/Chromium.app/Contents/MacOS/Chromium",
                    "%s/Applications/Chromium.app/Contents/MacOS/Chromium".formatted(home),
                    "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
            );
        }
        if (SystemInfo.isWindows) {
            String localAppData = environmentValue(environment, "LOCALAPPDATA");
            String programFiles = environmentValue(environment, "PROGRAMFILES");
            String programFilesX86 = environmentValue(environment, "PROGRAMFILES(X86)");
            return List.of(
                    child(localAppData, "Google/Chrome/Application/chrome.exe"),
                    child(programFiles, "Google/Chrome/Application/chrome.exe"),
                    child(programFilesX86, "Google/Chrome/Application/chrome.exe"),
                    child(localAppData, "Chromium/Application/chrome.exe"),
                    child(programFiles, "Microsoft/Edge/Application/msedge.exe"),
                    child(programFilesX86, "Microsoft/Edge/Application/msedge.exe")
            );
        }
        return List.of(
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/snap/bin/chromium"
        );
    }

    private static String child(String parent, String child) {
        return StringUtils.isBlank(parent) ? "" : Path.of(parent, child).toString();
    }

    private static String environmentValue(Map<String, String> environment, String name) {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }
}
