package com.github.drafael.chat4j.chat.composer;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentSelectionPolicyTest {

    @Test
    @DisplayName("Executable attachments are rejected")
    void create_whenExecutableExtensionProvided_rejectsAttachment(@TempDir Path tempDir) throws Exception {
        Path executable = Files.writeString(tempDir.resolve("unsafe.exe"), "unsafe");
        var subject = new AttachmentSelectionPolicy();

        assertThatThrownBy(() -> subject.create(executable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    @DisplayName("Markdown attachments retain their file metadata")
    void create_whenMarkdownFileProvided_returnsAttachment(@TempDir Path tempDir) throws Exception {
        Path markdown = Files.writeString(tempDir.resolve("notes.md"), "# hello");
        var subject = new AttachmentSelectionPolicy();

        ComposerAttachment result = subject.create(markdown);

        assertThat(result.path()).isEqualTo(markdown);
        assertThat(result.sizeBytes()).isEqualTo(Files.size(markdown));
        assertThat(result.image()).isFalse();
    }
}
