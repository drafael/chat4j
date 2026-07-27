package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAttachmentSupportTest {

    @TempDir
    Path tempDir;

    private Path managedRoot;
    private ProviderAttachmentSupport subject;

    @BeforeEach
    void setUp() throws Exception {
        managedRoot = tempDir.resolve("attachments");
        Files.createDirectories(managedRoot);
        subject = new ProviderAttachmentSupport(managedRoot);
    }

    @Test
    @DisplayName("PDF file fallback includes bounded extracted text")
    void extractedAttachment_whenFilePartIsPdf_includesExtractedText() throws Exception {
        Path pdf = managedRoot.resolve(UUID.randomUUID().toString());
        writePdf(pdf, "Purchasing government bonds with credit funds");
        FilePart filePart = filePart(pdf, "bonds.pdf", "application/pdf", 1L);

        var resolved = subject.resolve(filePart.attachmentRef(), false).orElseThrow();

        assertThat(extract(subject, resolved, ProviderAttachmentSupport.MAX_PDF_BYTES))
                .hasValueSatisfying(extracted -> assertThat(extracted.text())
                        .contains("Purchasing government bonds with credit funds"));
    }

    @Test
    @DisplayName("UUID-named text attachment uses original-name suffix and live size")
    void resolve_whenStorageNameHasNoSuffix_usesOriginalNameAndActualSize() throws Exception {
        Path text = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(text, "Summarize this attachment content.");
        FilePart filePart = filePart(text, "notes.JSON", "text/plain; charset=UTF-8", 99_999L);

        var resolved = subject.resolve(filePart.attachmentRef(), false);
        long actualSize = Files.size(text);

        assertThat(resolved).hasValueSatisfying(attachment -> {
            assertThat(attachment.actualSize()).isEqualTo(actualSize);
            assertThat(attachment.mimeType()).isEqualTo("text/plain");
            assertThat(attachment.kind()).isEqualTo(ProviderAttachmentSupport.AttachmentKind.TEXT);
        });
        assertThat(extract(
                subject,
                resolved.orElseThrow(),
                ProviderAttachmentSupport.MAX_TEXT_BYTES
        )).hasValueSatisfying(extracted -> assertThat(extracted.text())
                .contains("Summarize this attachment content."));
    }

    @Test
    @DisplayName("Outside-root and symlink-escape paths are unavailable")
    void resolve_whenCandidateEscapesManagedRoot_returnsEmpty() throws Exception {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "private");
        AttachmentRef direct = attachment(outside, "outside.txt", "text/plain", Files.size(outside));

        assertThat(subject.resolve(direct, false)).isEmpty();
        AttachmentRef relative = new AttachmentRef(
                UUID.randomUUID(),
                Path.of("relative", "outside.txt").toString(),
                "outside.txt",
                "text/plain",
                Files.size(outside),
                ""
        );
        assertThat(subject.resolve(relative, false)).isEmpty();

        Path link = managedRoot.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
            AttachmentRef escapedLink = attachment(link, "outside.txt", "text/plain", Files.size(outside));
            assertThat(subject.resolve(escapedLink, false)).isEmpty();
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Direct outside-root containment remains covered when symlinks are unavailable.
        }
    }

    @Test
    @DisplayName("MIME aliases and valid parameters normalize deterministically")
    void resolve_whenMimeUsesAliasAndParameters_returnsCanonicalMime() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        AttachmentRef attachment = attachment(file, "photo.JPEG", "IMAGE/JPG; charset=binary", 1L);

        assertThat(subject.resolve(attachment, true)).hasValueSatisfying(resolved -> {
            assertThat(resolved.mimeType()).isEqualTo("image/jpeg");
            assertThat(resolved.kind()).isEqualTo(ProviderAttachmentSupport.AttachmentKind.IMAGE);
        });
    }

    @Test
    @DisplayName("Quoted MIME parameters preserve semicolons outside parameter separators")
    void resolve_whenQuotedMimeParameterContainsSemicolon_acceptsParameter() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        AttachmentRef attachment = attachment(file, "notes.json", "application/json; name=\"a;b\"; charset=utf-8", 1L);

        assertThat(subject.resolve(attachment, false)).hasValueSatisfying(resolved -> {
            assertThat(resolved.mimeType()).isEqualTo("application/json");
            assertThat(resolved.kind()).isEqualTo(ProviderAttachmentSupport.AttachmentKind.TEXT);
        });
    }

    @Test
    @DisplayName("Long MIME parameters do not hide an exact suffix conflict")
    void resolve_whenMimeParametersAreLong_stillDetectsConflict() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        AttachmentRef attachment = attachment(
                file,
                "photo.jpg",
                "image/png; note=%s".formatted("x".repeat(300)),
                1L
        );

        assertThat(subject.resolve(attachment, true))
                .hasValueSatisfying(resolved -> assertThat(resolved.kind())
                        .isEqualTo(ProviderAttachmentSupport.AttachmentKind.CONFLICT));
    }

    @Test
    @DisplayName("MIME and exact image suffix conflicts block byte-bearing mapping")
    void resolve_whenExactImageMimeConflictsWithSuffix_marksConflict() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        AttachmentRef attachment = attachment(file, "photo.jpg", "image/png", 1L);

        assertThat(subject.resolve(attachment, true))
                .hasValueSatisfying(resolved -> assertThat(resolved.kind())
                        .isEqualTo(ProviderAttachmentSupport.AttachmentKind.CONFLICT));
    }

    @Test
    @DisplayName("Safe names remove path prefixes controls and bidi while preserving a recognized suffix")
    void safeName_whenNameContainsUnsafePresentationCharacters_returnsBoundedBasename() {
        String stem = "x".repeat(300);
        String name = "folder\\nested/\u202E\u0000  %s.JSON\u00A0".formatted(stem);

        String result = subject.safeName(name, false);

        assertThat(result).doesNotContain("folder", "nested", "\u202E", "\u0000").endsWith(".json");
        assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
    }

    @Test
    @DisplayName("Safe labels replace unpaired surrogates and ignore generated alt text and paths")
    void safeLabel_whenGeneratedImageContainsUntrustedMetadata_usesOnlySafeOriginalName() {
        AttachmentRef attachment = new AttachmentRef(
                UUID.randomUUID(),
                "/secret/storage/uuid",
                "../\uD800 image.png",
                "image/png",
                Long.MAX_VALUE,
                ""
        );
        GeneratedImagePart part = new GeneratedImagePart(attachment, null, null, "secret".repeat(100_000));

        String result = subject.safeLabel(part);

        assertThat(result).isEqualTo("[Generated image: � image.png]")
                .doesNotContain("secret", "storage", "uuid");
    }

    @Test
    @DisplayName("Large persisted metadata is normalized directly into bounded provider values")
    void resolve_whenPersistedMetadataIsLarge_boundsNormalizedIntermediates() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        String originalName = "x".repeat(1_000) + ".JSON";
        String mime = "text/" + "x".repeat(1_000);
        AttachmentRef attachment = attachment(file, originalName, mime, 1L);

        assertThat(subject.resolve(attachment, false)).hasValueSatisfying(resolved -> {
            assertThat(resolved.safeName()).hasSize(255).endsWith(".json");
            assertThat(resolved.mimeType()).isEqualTo("application/json");
            assertThat(resolved.kind()).isEqualTo(ProviderAttachmentSupport.AttachmentKind.TEXT);
        });
    }

    @Test
    @DisplayName("Strict UTF-8 decoding rejects malformed input")
    void extractedText_whenUtf8IsMalformed_returnsEmpty() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{(byte) 0xC3, 0x28});
        FilePart part = filePart(file, "bad.txt", "text/plain", 2L);

        var resolved = subject.resolve(part.attachmentRef(), false).orElseThrow();
        assertThat(extract(subject, resolved, ProviderAttachmentSupport.MAX_TEXT_BYTES)).isEmpty();
    }

    @Test
    @DisplayName("Text reads accept the exact byte limit and reject limit plus one")
    void extractedText_whenSourceCrossesByteLimit_enforcesInclusiveLimit() throws Exception {
        Path exact = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(exact, filledBytes((int) ProviderAttachmentSupport.MAX_TEXT_BYTES));
        Path over = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(over, filledBytes((int) ProviderAttachmentSupport.MAX_TEXT_BYTES + 1));

        var exactResolved = subject.resolve(
                filePart(exact, "exact.txt", "text/plain", 1L).attachmentRef(),
                false
        ).orElseThrow();
        var overResolved = subject.resolve(
                filePart(over, "over.txt", "text/plain", 1L).attachmentRef(),
                false
        ).orElseThrow();
        assertThat(extract(subject, exactResolved, ProviderAttachmentSupport.MAX_TEXT_BYTES)).isPresent();
        assertThat(extract(subject, overResolved, ProviderAttachmentSupport.MAX_TEXT_BYTES)).isEmpty();
    }

    @Test
    @DisplayName("Authority rejects file growth without reading beyond the byte limit")
    void readBytes_whenChannelGrowsAfterStat_doesNotReadBeyondLimit() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.write(file, new byte[]{1});
        byte[] changedBytes = new byte[8192];
        AtomicReference<ByteArrayChannel> openedChannel = new AtomicReference<>();
        var changingSubject = new ProviderAttachmentSupport(managedRoot, ignored -> {
            var channel = new ByteArrayChannel(changedBytes);
            openedChannel.set(channel);
            return channel;
        });
        var resolved = changingSubject.resolve(
                attachment(file, "image.png", "image/png", 1L),
                true
        ).orElseThrow();

        assertThat(changingSubject.readBytes(resolved, 10))
                .hasValueSatisfying(bytes -> {
                    assertThat(bytes.complete()).isFalse();
                    assertThat(bytes.actualBytes()).isEqualTo(10L);
                });
        assertThat(openedChannel.get().position()).isEqualTo(10L);
    }

    @Test
    @DisplayName("A path replaced by a symlink after resolution is not opened")
    void readBytes_whenResolvedFileBecomesSymlink_rejectsReplacement() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(file, "managed");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside-secret");
        var resolved = subject.resolve(attachment(file, "notes.txt", "text/plain", 1L), false)
                .orElseThrow();
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        assertThat(subject.readBytes(resolved, ProviderAttachmentSupport.MAX_TEXT_BYTES)).isEmpty();
    }

    @Test
    @DisplayName("A single-frame GIF is accepted by the OpenAI image check")
    void isSingleFrameGif_whenGifHasOneFrame_returnsTrue() throws Exception {
        assertThat(subject.isSingleFrameGif(gifBytes())).isTrue();
    }

    @Test
    @DisplayName("Metadata-only projection never opens an attachment channel")
    void safeLabel_whenAttachmentIsPresent_doesNotReadPath() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var metadataSubject = new ProviderAttachmentSupport(managedRoot, path -> {
            opens.incrementAndGet();
            throw new AssertionError("metadata projection must not open a channel");
        });
        AttachmentRef attachment = new AttachmentRef(null, "/outside", "notes.txt", "text/plain", 1L, "");

        String result = metadataSubject.safeLabel(new FilePart(attachment));

        assertThat(result).isEqualTo("[File attached: notes.txt]");
        assertThat(opens).hasValue(0);
    }

    private static Optional<ProviderAttachmentSupport.ExtractedAttachment> extract(
            ProviderAttachmentSupport support,
            ProviderAttachmentSupport.ResolvedAttachment attachment,
            long maximumBytes
    ) {
        return support.readBytes(attachment, maximumBytes)
                .filter(ProviderAttachmentSupport.BoundedBytes::complete)
                .flatMap(bytes -> support.extractedAttachment(attachment, bytes));
    }

    private static byte[] gifBytes() throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", output);
        return output.toByteArray();
    }

    private FilePart filePart(Path file, String originalName, String mimeType, long displaySize) {
        return new FilePart(attachment(file, originalName, mimeType, displaySize));
    }

    private AttachmentRef attachment(Path file, String originalName, String mimeType, long displaySize) {
        return new AttachmentRef(UUID.randomUUID(), file.toString(), originalName, mimeType, displaySize, "");
    }

    private static byte[] filledBytes(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'a');
        return bytes;
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

    private static final class ByteArrayChannel implements SeekableByteChannel {
        private final byte[] bytes;
        private int position;
        private boolean open = true;

        private ByteArrayChannel(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(ByteBuffer destination) {
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(destination.remaining(), bytes.length - position);
            destination.put(bytes, position, count);
            position += count;
            return count;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = Math.toIntExact(newPosition);
            return this;
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
