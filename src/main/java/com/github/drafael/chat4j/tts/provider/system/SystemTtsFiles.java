package com.github.drafael.chat4j.tts.provider.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class SystemTtsFiles {

    private SystemTtsFiles() {
    }

    static byte[] readNonEmpty(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("System Text to Speech did not produce audio.");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new IllegalStateException("System Text to Speech produced empty audio.");
        }
        return bytes;
    }

    static void deleteIfExists(Path file) {
        try {
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectory(file);
                return;
            }
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(SystemTtsFiles::deleteFileQuietly);
        }
    }

    private static void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
