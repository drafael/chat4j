package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

public final class McpConfigurationRepository {

    private static final int MAX_CONFIGURATION_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_CONFIGURATION_BYTES)
                    .maxStringLength(256 * 1024)
                    .maxNestingDepth(64)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path configurationFile;

    public McpConfigurationRepository(@NonNull Path configurationFile) {
        Path normalized = configurationFile.toAbsolutePath().normalize();
        Validate.isTrue(
                normalized.getParent() != null && normalized.getFileName() != null,
                "configurationFile should identify a file"
        );
        this.configurationFile = normalized;
    }

    public McpConfigurationLoadResult load() {
        try {
            if (!Files.exists(configurationFile)) {
                return new McpConfigurationLoadResult.Missing();
            }
            if (Files.size(configurationFile) > MAX_CONFIGURATION_BYTES) {
                return new McpConfigurationLoadResult.Invalid("MCP configuration exceeds the 1 MiB limit.");
            }
            String content = Files.readString(configurationFile, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(content)) {
                return new McpConfigurationLoadResult.Invalid("MCP configuration is blank.");
            }
            McpConfiguration configuration = JSON.readValue(content, McpConfiguration.class);
            McpConfigurationValidator.validate(configuration);
            return new McpConfigurationLoadResult.Valid(configuration);
        } catch (NoSuchFileException e) {
            return new McpConfigurationLoadResult.Missing();
        } catch (IllegalArgumentException e) {
            return new McpConfigurationLoadResult.Invalid(e.getMessage());
        } catch (Exception e) {
            return new McpConfigurationLoadResult.Invalid("MCP configuration is malformed or unreadable.");
        }
    }

    public void save(@NonNull McpConfiguration configuration) {
        McpConfigurationValidator.validate(configuration);
        Path parent = configurationFile.getParent();
        try {
            byte[] content = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(configuration);
            if (content.length > MAX_CONFIGURATION_BYTES) {
                throw new IllegalArgumentException("MCP configuration exceeds the 1 MiB limit.");
            }
            Files.createDirectories(parent);
            Path tempFile = Files.createTempFile(parent, configurationFile.getFileName().toString(), ".tmp");
            try {
                Files.write(tempFile, content);
                moveIntoPlace(tempFile);
            } catch (IOException | SecurityException e) {
                deleteTempFile(tempFile, e);
                throw e;
            }
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("Failed to persist MCP configuration.", e);
        }
    }

    public Path file() {
        return configurationFile;
    }

    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, configurationFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, configurationFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTempFile(Path tempFile, Exception failure) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException | SecurityException e) {
            failure.addSuppressed(e);
        }
    }
}
