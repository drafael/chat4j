package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.ConversationRecord;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.LoadedConversation;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.MessageRecord;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPrintHtmlRendererTest {

    private final ConversationPrintHtmlRenderer subject = new ConversationPrintHtmlRenderer();

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Print rendering keeps safe links and replaces remote images with references")
    void render_whenMessageContainsLinksAndRemoteImages_keepsOnlySafeLinks() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("[OpenAI](https://openai.com) [unsafe](javascript:alert(1)) [hostless](https:example) ![remote](https://example.com/image.png)")),
                        Instant.parse("2026-08-08T10:00:00Z")
                ))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        String html = subject.render(document);

        assertThat(html)
                .contains("href=\"https://openai.com\"")
                .contains("remote image")
                .contains("href=\"https://example.com/image.png\"")
                .doesNotContain("href=\"javascript:")
                .doesNotContain("href=\"https:example\"")
                .doesNotContain("src=\"https://example.com/image.png\"");
    }

    @Test
    @DisplayName("Standard PDF source links use clickable superscript brackets")
    void render_whenMessageContainsNumericSourceLinks_stylesOnlyHttpCitationNumbers() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        Research [3](https://citation.example) [12](http://citation.example/twelve)
                        [999](https://citation.example/999) [4](mailto:test@example.com)
                        [2024](https://example.com/report).
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        Document rendered = Jsoup.parse(subject.render(document));

        assertThat(rendered.select("sup.source-citation-print > a.source-citation-link").eachText())
                .containsExactly("[3]", "[12]", "[999]");
        assertThat(rendered.select("sup.source-citation-print > a[href]").eachAttr("href"))
                .containsExactly(
                        "https://citation.example",
                        "http://citation.example/twelve",
                        "https://citation.example/999"
                );
        assertThat(rendered.select("a[href='mailto:test@example.com']").text()).isEqualTo("[4]");
        assertThat(rendered.select("a[href='https://example.com/report']").text()).isEqualTo("[2024]");
        assertThat(rendered.select("sup a[href='mailto:test@example.com'], sup a[href='https://example.com/report']"))
                .isEmpty();
    }

    @Test
    @DisplayName("Print rendering uses medium localized formatting for message timestamps")
    void render_whenMessageHasTimestamp_usesMediumDateTimeFormat() {
        Instant timestamp = Instant.parse("2026-08-08T10:00:00Z");
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("Timestamped")),
                        timestamp
                ))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        String html = subject.render(document);
        String expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .format(timestamp.atZone(ZoneId.systemDefault()));

        assertThat(html).contains(Entities.escape(expected));
    }

    @Test
    @DisplayName("Print rendering preserves highlighted code and diagram source fallbacks")
    void render_whenMessageContainsCodeAndDiagram_preservesFormattedFallbacks() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        ```java
                        public class Example {}
                        ```

                        ```mermaid
                        graph TD
                          A --> B
                        ```
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        String html = subject.render(document);

        assertThat(html)
                .contains("data-chat4j-highlighted=\"server\"")
                .contains("hljs-keyword")
                .contains("md-mermaid-block")
                .contains("Diagram source is shown because this export backend does not execute browser renderers.");
    }

    @Test
    @DisplayName("Standard tables use consistent lightweight horizontal rules")
    void render_whenMessageContainsTable_usesUndoubledPrintBorders() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        | Aspect | SQLite | H2 |
                        | --- | --- | --- |
                        | Storage | File | File or memory |
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        String html = subject.render(document);

        assertThat(html)
                .contains("<table class=\"md-table\"")
                .contains("border-top: 0.7pt solid #aeb6c2")
                .contains("border-bottom: 0.35pt solid #d8dde5")
                .contains("border-bottom: 0.8pt solid #aeb6c2")
                .contains("table tbody tr:last-child td")
                .doesNotContain("border: 0.6pt solid #b8bec7");
    }

    @Test
    @DisplayName("Standard rendering replaces valid SMILES source with an owned diagram and exact caption")
    void render_whenMessageContainsValidSmiles_rendersDiagramAndCaption() {
        String smiles = "CC(=O)Oc1ccccc1C(=O)O";
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        ```smiles
                        %s
                        ```
                        """.formatted(smiles)))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        var html = Jsoup.parse(subject.render(document));

        assertThat(html.select("figure.smiles-diagram")).hasSize(1);
        assertThat(html.selectFirst("figure.smiles-diagram").hasClass("smiles-diagram-large")).isTrue();
        var image = html.selectFirst("figure.smiles-diagram img");
        assertThat(image).isNotNull();
        assertThat(image.attr("src")).startsWith("data:image/png;base64,");
        assertThat(html.select("figure.smiles-diagram figcaption").text()).isEqualTo("SMILES: %s".formatted(smiles));
        assertThat(html.select("table.md-smiles-block")).isEmpty();
        assertThat(html.select(".diagram-fallback-note")).isEmpty();
    }

    @Test
    @DisplayName("Standard rendering preserves invalid SMILES source with one specific fallback")
    void render_whenMessageContainsInvalidSmiles_preservesSourceAndSpecificFallback() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        ```smiles
                        not a smiles
                        ```
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        var html = Jsoup.parse(subject.render(document));

        assertThat(html.select("table.md-smiles-block pre").text()).isEqualTo("not a smiles");
        assertThat(html.select(".diagram-fallback-note"))
                .singleElement()
                .satisfies(note -> assertThat(note.text()).isEqualTo("SMILES diagram could not be rendered."));
        assertThat(html.select("figure.smiles-diagram")).isEmpty();
    }

    @Test
    @DisplayName("Standard rendering never trusts conversation supplied image data while adding owned diagrams")
    void render_whenMessageContainsDataImageAndSmiles_keepsOnlyRendererOwnedDataImage() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        ![untrusted](data:image/png;base64,AAAA)

                        ```smiles
                        CCO
                        ```
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        var html = Jsoup.parse(subject.render(document));

        assertThat(html.select("img"))
                .singleElement()
                .satisfies(image -> assertThat(image.attr("src")).startsWith("data:image/png;base64,"));
        assertThat(html.text()).contains("![untrusted](data:image/png;base64,AAAA)");
    }

    @Test
    @DisplayName("Standard rendering cancellation stops before the next SMILES diagram")
    void render_whenCancelledBetweenSmiles_stopsRenderingDiagrams() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("""
                        ```smiles
                        CCO
                        ```

                        ```smiles
                        c1ccccc1
                        ```
                        """))),
                Instant.parse("2026-08-08T11:00:00Z")
        );
        var checks = new AtomicInteger();

        var html = Jsoup.parse(subject.render(document, () -> checks.incrementAndGet() >= 3));

        assertThat(html.select("figure.smiles-diagram")).hasSize(1);
        assertThat(html.select("table.md-smiles-block")).hasSize(1);
    }

    @Test
    @DisplayName("Standard rendering keeps math readable when browser KaTeX layout is unavailable")
    void render_whenMessageContainsMath_preservesReadableFormulaSource() {
        ConversationPdfDocument document = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.assistant("The result is $x^2 + y^2$."))),
                Instant.parse("2026-08-08T11:00:00Z")
        );

        String html = subject.render(document);

        assertThat(html).contains("x^2 + y^2");
    }

    @Test
    @DisplayName("Print rendering safely embeds titles containing XML and style delimiters")
    void render_whenTitleContainsMarkup_keepsStylesheetWellFormed() {
        ConversationPdfDocument base = ConversationPdfDocument.from(
                loadedConversation(List.of(Message.user("hello"))),
                Instant.parse("2026-08-08T11:00:00Z")
        );
        var document = new ConversationPdfDocument(
                "A & B </style><img src=\"https://example.com/tracker.png\" />",
                base.provider(),
                base.model(),
                base.createdAt(),
                base.exportedAt(),
                base.turns()
        );

        String html = subject.render(document);

        assertThat(html)
                .doesNotContain("</style><img")
                .doesNotContain("src=\"https://example.com/tracker.png\"")
                .contains("\\26 ")
                .contains("&lt;/style&gt;&lt;img");
    }

    @Test
    @DisplayName("Citation numbers are rendered once using their persisted values")
    void render_whenCitationIsPresent_doesNotDuplicateOrderedListNumbers() {
        CitationRef citation = CitationRef.builder()
                .number(4)
                .kind(CitationKind.WEB)
                .title("Source")
                .url("https://example.com")
                .build();
        var document = ConversationPdfDocument.builder()
                .title("Citations")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new TextPart("Answer")),
                        List.of(),
                        false,
                        "",
                        List.of(citation)
                )))
                .build();

        String html = subject.render(document);

        assertThat(html)
                .contains("<ul class=\"citation-list\">")
                .contains("4. Source")
                .doesNotContain("<ol class=\"citation-list\">");
    }

    @Test
    @DisplayName("Corrupt image attachments produce an unavailable placeholder")
    void render_whenManagedImageIsCorrupt_showsUnavailablePlaceholder() throws Exception {
        Path corruptImage = tempDirectory.resolve("corrupt.png");
        Files.writeString(corruptImage, "not an image");
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                corruptImage.toString(),
                "corrupt.png",
                "image/png",
                Files.size(corruptImage),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Corrupt image")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new GeneratedImagePart(reference, null, null, "Broken image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        assertThat(subject.render(document)).contains("Image unavailable: corrupt.png");
    }

    @Test
    @DisplayName("Standard rendering uses decoded image format instead of an incorrect declared MIME type")
    void render_whenImageMimeTypeIsWrong_usesActualDataUriType() throws Exception {
        Path mislabeledImage = tempDirectory.resolve("mislabeled.png");
        assertThat(ImageIO.write(
                new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                "jpeg",
                mislabeledImage.toFile()
        )).isTrue();
        var reference = new AttachmentRef(
                UUID.randomUUID(),
                mislabeledImage.toString(),
                "mislabeled.png",
                "image/png",
                Files.size(mislabeledImage),
                ""
        );
        var document = ConversationPdfDocument.builder()
                .title("Mislabeled image")
                .exportedAt(Instant.EPOCH)
                .turns(List.of(new ConversationPdfDocument.Turn(
                        Role.ASSISTANT,
                        Instant.EPOCH,
                        List.of(new GeneratedImagePart(reference, 40, 30, "Mislabeled image")),
                        List.of(),
                        false,
                        "",
                        List.of()
                )))
                .build();

        String html = subject.render(document);

        assertThat(html)
                .contains("src=\"data:image/jpeg;base64,")
                .doesNotContain("src=\"data:image/png;base64,");
    }

    private LoadedConversation loadedConversation(List<Message> messages) {
        UUID conversationId = UUID.randomUUID();
        ConversationRecord conversation = new ConversationRecord(
                conversationId,
                "PDF Export",
                "Anthropic",
                "claude-sonnet-5",
                false,
                "off",
                false,
                null,
                LocalDateTime.of(2026, 8, 8, 9, 0),
                LocalDateTime.of(2026, 8, 8, 10, 0)
        );
        List<MessageRecord> records = java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> new MessageRecord(UUID.randomUUID(), index + 1, messages.get(index)))
                .toList();
        return new LoadedConversation(conversation, records);
    }
}
