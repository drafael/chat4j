package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ExecutionDirectoryContext {

    private static final ThreadLocal<Path> CURRENT_DIRECTORY = new ThreadLocal<>();

    private ExecutionDirectoryContext() {
    }

    public static Scope open(Path directory) {
        if (directory == null) {
            return Scope.NO_OP;
        }

        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Validate.isTrue(Files.isDirectory(normalizedDirectory),
                "Execution directory is not valid: %s", normalizedDirectory);

        Path previous = CURRENT_DIRECTORY.get();
        CURRENT_DIRECTORY.set(normalizedDirectory);
        return new Scope(previous, true);
    }

    public static Optional<Path> currentDirectory() {
        return Optional.ofNullable(CURRENT_DIRECTORY.get());
    }

    public static final class Scope implements AutoCloseable {

        private static final Scope NO_OP = new Scope(null, false);

        private final Path previous;
        private final boolean restore;

        private Scope(Path previous, boolean restore) {
            this.previous = previous;
            this.restore = restore;
        }

        @Override
        public void close() {
            if (!restore) {
                return;
            }

            if (previous == null) {
                CURRENT_DIRECTORY.remove();
            } else {
                CURRENT_DIRECTORY.set(previous);
            }
        }
    }
}
