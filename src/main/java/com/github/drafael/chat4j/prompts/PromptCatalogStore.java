package com.github.drafael.chat4j.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

final class PromptCatalogStore {

    private final Path promptsFile;

    PromptCatalogStore(@NonNull Path promptsFile) {
        Path normalizedFile = promptsFile.toAbsolutePath().normalize();
        Validate.isTrue(
                normalizedFile.getParent() != null && normalizedFile.getFileName() != null,
                "promptsFile should identify a file"
        );
        this.promptsFile = normalizedFile;
    }

    Optional<String> loadCatalogJson() {
        try {
            return Optional.of(Files.readString(promptsFile, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("Failed to read prompt catalog: %s".formatted(promptsFile), e);
        }
    }

    void saveCatalogJson(String json) {
        Path parent = promptsFile.getParent();
        try {
            Files.createDirectories(parent);
            Path tempFile = Files.createTempFile(parent, promptsFile.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tempFile, json, StandardCharsets.UTF_8);
                moveIntoPlace(tempFile);
            } catch (IOException | SecurityException e) {
                deleteTempFile(tempFile, e);
                throw e;
            }
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("Failed to persist prompt catalog: %s".formatted(promptsFile), e);
        }
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, promptsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, promptsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTempFile(Path tempFile, Exception failure) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException | SecurityException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
