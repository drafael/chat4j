package com.github.drafael.chat4j.tts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

class SystemTtsExecutableLocator {

    private static final List<String> PATH_KEYS = List.of("PATH", "Path", "path");

    Optional<Path> findOnPath(String command, Map<String, String> environment) {
        if (StringUtils.isBlank(command)) {
            return Optional.empty();
        }
        return pathValues(environment)
                .flatMap(path -> Arrays.stream(path.split(Pattern.quote(File.pathSeparator))))
                .map(SystemTtsExecutableLocator::cleanPathEntry)
                .filter(StringUtils::isNotBlank)
                .map(directory -> candidateExecutable(directory, command))
                .flatMap(Optional::stream)
                .filter(Files::isExecutable)
                .findFirst();
    }

    private static Stream<String> pathValues(Map<String, String> environment) {
        if (environment == null) {
            return Stream.empty();
        }
        Stream<String> preferred = PATH_KEYS.stream()
                .map(environment::get)
                .filter(StringUtils::isNotBlank);
        Stream<String> remaining = environment.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .filter(entry -> "path".equals(entry.getKey().toLowerCase(Locale.ROOT)))
                .filter(entry -> !PATH_KEYS.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(StringUtils::isNotBlank);
        return Stream.concat(preferred, remaining);
    }

    private static Optional<Path> candidateExecutable(String directory, String command) {
        try {
            return Optional.of(Path.of(directory).resolve(command));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String cleanPathEntry(String entry) {
        return StringUtils.unwrap(StringUtils.trimToEmpty(entry), '"');
    }
}
