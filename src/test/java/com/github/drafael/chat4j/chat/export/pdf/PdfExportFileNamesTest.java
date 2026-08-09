package com.github.drafael.chat4j.chat.export.pdf;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportFileNamesTest {

    @Test
    @DisplayName("Missing PDF extension is appended without changing the selected directory")
    void ensurePdfExtension_whenExtensionMissing_appendsExtension() {
        Path result = PdfExportFileNames.ensurePdfExtension(Path.of("exports", "Conversation"));

        assertThat(result).isEqualTo(Path.of("exports", "Conversation.pdf"));
    }

    @Test
    @DisplayName("Suggested filenames remove platform-reserved characters")
    void suggestedFileName_whenTitleContainsReservedCharacters_returnsPortableName() {
        String result = PdfExportFileNames.suggestedFileName("Provider/model: export?");

        assertThat(result).isEqualTo("Provider-model- export-.pdf");
    }

    @Test
    @DisplayName("Windows device names receive a portable conversation suffix")
    void suggestedFileName_whenTitleIsWindowsDeviceName_returnsPortableName() {
        assertThat(PdfExportFileNames.suggestedFileName("CON")).isEqualTo("CON-conversation.pdf");
        assertThat(PdfExportFileNames.suggestedFileName("com1")).isEqualTo("com1-conversation.pdf");
        assertThat(PdfExportFileNames.suggestedFileName("NUL.txt")).isEqualTo("NUL.txt-conversation.pdf");
    }
}
