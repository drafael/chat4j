package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAttachmentSupportTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("PDF file fallback includes extracted text")
    void textProjection_whenFilePartIsPdf_includesExtractedText() throws Exception {
        Path pdf = tempDir.resolve("bonds.pdf");
        writePdf(pdf, "Purchasing government bonds with credit funds");
        FilePart filePart = new FilePart(new AttachmentRef(
                UUID.randomUUID(),
                pdf.toString(),
                "bonds.pdf",
                "application/pdf",
                Files.size(pdf),
                ""
        ));

        String projection = ProviderAttachmentSupport.textProjection(List.of(filePart));

        assertThat(projection)
                .contains("[File attached: bonds.pdf")
                .contains("Extracted attachment text:")
                .contains("Purchasing government bonds with credit funds");
    }

    @Test
    @DisplayName("Text file fallback includes file contents")
    void textProjection_whenFilePartIsText_includesFileContents() throws Exception {
        Path text = tempDir.resolve("notes.txt");
        Files.writeString(text, "Summarize this attachment content.");
        FilePart filePart = new FilePart(new AttachmentRef(
                UUID.randomUUID(),
                text.toString(),
                "notes.txt",
                "text/plain",
                Files.size(text),
                ""
        ));

        String projection = ProviderAttachmentSupport.textProjection(List.of(filePart));

        assertThat(projection)
                .contains("[File attached: notes.txt")
                .contains("Extracted attachment text:")
                .contains("Summarize this attachment content.");
    }

    private static void writePdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(path.toFile());
        }
    }
}
