package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicChatCompletionClientTest {

    @TempDir
    Path tempDir;

    private final AnthropicChatCompletionClient subject = new AnthropicChatCompletionClient();

    @Test
    @DisplayName("Text files map to citation-enabled Anthropic document blocks")
    void mapUserBlocks_whenTextFileIsSupported_returnsCitationEnabledTextDocument() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "important notes");
        Message message = userMessage(new FilePart(attachment(file, "notes.txt", "text/plain")));

        var blocks = subject.mapUserBlocks(message, runtime(ProviderCapabilities.chatModelsImagesAndFiles()));

        assertThat(blocks).hasSize(1);
        var document = blocks.getFirst().asDocument();
        assertThat(document.source().isText()).isTrue();
        assertThat(document.source().asText().data()).isEqualTo("important notes");
        assertThat(document.title()).contains("notes.txt");
        assertThat(document.citations()).hasValueSatisfying(citations -> assertThat(citations.enabled()).contains(true));
    }

    @Test
    @DisplayName("PDF files map to citation-enabled Anthropic document blocks")
    void mapUserBlocks_whenPdfFileIsSupported_returnsCitationEnabledPdfDocument() throws Exception {
        Path file = tempDir.resolve("paper.pdf");
        Files.write(file, "%PDF-1.4\n% demo".getBytes(StandardCharsets.UTF_8));
        Message message = userMessage(new FilePart(attachment(file, "paper.pdf", "application/pdf")));

        var blocks = subject.mapUserBlocks(message, runtime(ProviderCapabilities.chatModelsImagesAndFiles()));

        assertThat(blocks).hasSize(1);
        var document = blocks.getFirst().asDocument();
        assertThat(document.source().isBase64()).isTrue();
        assertThat(document.source().asBase64().data()).isNotBlank();
        assertThat(document.title()).contains("paper.pdf");
        assertThat(document.citations()).hasValueSatisfying(citations -> assertThat(citations.enabled()).contains(true));
    }

    @Test
    @DisplayName("Files fall back to text projection when native document input is unavailable")
    void mapUserBlocks_whenFileInputUnsupported_returnsTextFallback() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "fallback notes");
        Message message = userMessage(new FilePart(attachment(file, "notes.txt", "text/plain")));

        var blocks = subject.mapUserBlocks(message, runtime(ProviderCapabilities.chatModelsAndImages()));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().isText()).isTrue();
        assertThat(blocks.getFirst().asText().text()).contains("[File attached: notes.txt", "Extracted attachment text:", "fallback notes");
    }

    private Message userMessage(ContentPart part) {
        return new Message(Role.USER, List.of(part), Instant.now());
    }

    private AttachmentRef attachment(Path file, String originalName, String mimeType) throws Exception {
        return new AttachmentRef(null, file.toString(), originalName, mimeType, Files.size(file), "sha");
    }

    private ProviderRuntime runtime(ProviderCapabilities capabilities) {
        var descriptor = new ProviderDescriptor(
                "Anthropic",
                AuthType.ENV_VAR,
                "ANTHROPIC_API_KEY",
                null,
                "https://api.anthropic.com",
                emptyList(),
                capabilities,
                value -> value
        );
        return new ProviderRuntime(descriptor, "ANTHROPIC_API_KEY", descriptor.defaultBaseUrl(), "key", "claude-sonnet-4-6");
    }
}
