package com.github.drafael.chat4j.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigurationRepositoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("A missing file produces an empty missing state")
    void load_whenFileIsMissing_returnsMissingState() {
        var subject = new McpConfigurationRepository(tempDirectory.resolve("mcp.json"));

        assertThat(subject.load()).isInstanceOf(McpConfigurationLoadResult.Missing.class);
    }

    @Test
    @DisplayName("A valid catalog round trips through UTF-8 JSON")
    void save_whenConfigurationIsValid_roundTripsCompleteConfiguration() {
        var subject = new McpConfigurationRepository(tempDirectory.resolve("mcp.json"));
        var configuration = configuration("server_one");

        subject.save(configuration);

        assertThat(subject.load()).isEqualTo(new McpConfigurationLoadResult.Valid(configuration));
    }

    @Test
    @DisplayName("Malformed JSON remains untouched and returns an invalid state")
    void load_whenJsonIsMalformed_preservesFileAndReturnsInvalidState() throws Exception {
        Path file = tempDirectory.resolve("mcp.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        var subject = new McpConfigurationRepository(file);

        assertThat(subject.load()).isInstanceOf(McpConfigurationLoadResult.Invalid.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{broken");
    }

    @Test
    @DisplayName("Trailing garbage after a valid document remains invalid and untouched")
    void load_whenValidDocumentHasTrailingGarbage_preservesInvalidFile() throws Exception {
        Path file = tempDirectory.resolve("mcp.json");
        String content = "{\"version\":1,\"servers\":[]} garbage";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        var subject = new McpConfigurationRepository(file);

        assertThat(subject.load()).isInstanceOf(McpConfigurationLoadResult.Invalid.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("An adjacent second JSON document remains invalid and untouched")
    void load_whenValidDocumentHasAdjacentDocument_preservesInvalidFile() throws Exception {
        Path file = tempDirectory.resolve("mcp.json");
        String content = "{\"version\":1,\"servers\":[]}{\"version\":1,\"servers\":[]}";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        var subject = new McpConfigurationRepository(file);

        assertThat(subject.load()).isInstanceOf(McpConfigurationLoadResult.Invalid.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("Invalid replacement is rejected before the previous file changes")
    void save_whenReplacementIsInvalid_preservesPreviousFile() throws Exception {
        Path file = tempDirectory.resolve("mcp.json");
        var subject = new McpConfigurationRepository(file);
        subject.save(configuration("server_one"));
        String previous = Files.readString(file, StandardCharsets.UTF_8);
        var duplicate = new McpConfiguration(1, List.of(
                server("duplicate"),
                server("duplicate")
        ));

        assertThatThrownBy(() -> subject.save(duplicate)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(previous);
    }

    private McpConfiguration configuration(String modelId) {
        return new McpConfiguration(1, List.of(server(modelId)));
    }

    private McpServerConfiguration server(String modelId) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Server",
                modelId,
                true,
                false,
                McpTransportType.STREAMABLE_HTTP,
                "https://example.test/mcp?mode=tools",
                "",
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                Set.of("disabled_tool")
        );
    }
}
