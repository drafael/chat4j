package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentProjectionPlanTest {

    @TempDir
    Path tempDir;

    private Path managedRoot;
    private ProviderAttachmentSupport attachmentSupport;

    @BeforeEach
    void setUp() throws Exception {
        managedRoot = tempDir.resolve("attachments");
        Files.createDirectories(managedRoot);
        attachmentSupport = new ProviderAttachmentSupport(managedRoot);
    }

    @Test
    @DisplayName("Shared text fallback defines every projected part outcome")
    void textFallback_whenProjectedPartsVary_returnsSharedProviderText() {
        assertThat(List.of(
                new AttachmentProjectionPlan.PlainText("plain"),
                new AttachmentProjectionPlan.Label("label"),
                new AttachmentProjectionPlan.ExtractedText("file.txt", "contents"),
                new AttachmentProjectionPlan.NativeTextDocument("file.txt", "document"),
                new AttachmentProjectionPlan.NativePdf("file.pdf", "base64"),
                new AttachmentProjectionPlan.NativeImage("image/png", "base64")
        ).stream().map(AttachmentProjectionPlan::textFallback))
                .containsExactly(
                        "plain",
                        "label",
                        "file.txt\n\nExtracted attachment text:\ncontents",
                        "document",
                        "[File attached: file.pdf]",
                        ""
                );
    }

    @Test
    @DisplayName("Cancellation stops attachment projection before another managed file is opened")
    void create_whenCancelledDuringProjection_stopsBeforeNextAttachmentRead() throws Exception {
        Path first = managedRoot.resolve(UUID.randomUUID().toString());
        Path second = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        var cancelled = new AtomicBoolean();
        var opens = new AtomicInteger();
        var countingSupport = new ProviderAttachmentSupport(managedRoot, path -> {
            opens.incrementAndGet();
            cancelled.set(true);
            return Files.newByteChannel(path);
        });
        var message = new Message(Role.USER, List.of(
                new FilePart(attachment(first, "first.txt", "text/plain")),
                new FilePart(attachment(second, "second.txt", "text/plain"))
        ), Instant.now());

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(message),
                countingSupport,
                AttachmentProjectionPlan.textOnly(),
                cancelled::get
        );

        assertThat(opens).hasValue(1);
        assertThat(subject.messages()).isEmpty();
    }

    @Test
    @DisplayName("The newest 256 attachment occurrences are retained and one marker occupies the earliest omitted position")
    void create_whenOccurrenceLimitIsExceeded_retainsNewestAndEmitsOneForwardMarker() {
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < 260; index++) {
            parts.add(new FilePart(unavailableAttachment("file-%03d.txt".formatted(index))));
        }

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, parts, Instant.now())),
                attachmentSupport,
                AttachmentProjectionPlan.metadataOnly(),
                () -> false
        );

        List<AttachmentProjectionPlan.ProjectedPart> projected = subject.messages().getFirst().parts();
        assertThat(projected).hasSize(257);
        assertThat(projected.getFirst()).isEqualTo(new AttachmentProjectionPlan.Label(
                AttachmentProjectionPlan.OMISSION_MARKER
        ));
        assertThat(projected.stream()
                .filter(AttachmentProjectionPlan.Label.class::isInstance)
                .map(AttachmentProjectionPlan.Label.class::cast)
                .map(AttachmentProjectionPlan.Label::text)
                .filter(AttachmentProjectionPlan.OMISSION_MARKER::equals))
                .hasSize(1);
        assertThat(((AttachmentProjectionPlan.Label) projected.get(1)).text())
                .isEqualTo("[File attached: file-004.txt]");
        assertThat(((AttachmentProjectionPlan.Label) projected.getLast()).text())
                .isEqualTo("[File attached: file-259.txt]");
    }

    @Test
    @DisplayName("Thirty-two newest byte-bearing attachments are admitted and older attachments degrade to labels")
    void create_whenByteBearingCountLimitIsReached_degradesOlderOccurrenceWithoutStoppingScan() throws Exception {
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            Path file = managedRoot.resolve(UUID.randomUUID().toString());
            Files.writeString(file, "text-%02d".formatted(index));
            parts.add(new FilePart(attachment(file, "file-%02d.txt".formatted(index), "text/plain")));
        }

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, parts, Instant.now())),
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        List<AttachmentProjectionPlan.ProjectedPart> projected = subject.messages().getFirst().parts();
        assertThat(projected).hasSize(33);
        assertThat(projected.getFirst()).isEqualTo(new AttachmentProjectionPlan.Label(
                "[File attached: file-00.txt]"
        ));
        assertThat(projected.subList(1, projected.size()))
                .allMatch(AttachmentProjectionPlan.ExtractedText.class::isInstance);
    }

    @Test
    @DisplayName("Failed extraction attempts still consume the byte-bearing read budget")
    void create_whenTextDecodingFails_stopsReadingAfterByteBearingLimit() throws Exception {
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            Path file = managedRoot.resolve(UUID.randomUUID().toString());
            Files.write(file, new byte[]{(byte) 0xff});
            parts.add(new FilePart(attachment(file, "file-%02d.txt".formatted(index), "text/plain")));
        }
        AtomicInteger opens = new AtomicInteger();
        var countingSupport = new ProviderAttachmentSupport(managedRoot, path -> {
            opens.incrementAndGet();
            return Files.newByteChannel(path);
        });

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, parts, Instant.now())),
                countingSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        assertThat(opens).hasValue(AttachmentProjectionPlan.MAX_BYTE_BEARING_OCCURRENCES);
        assertThat(subject.messages().getFirst().parts())
                .allMatch(AttachmentProjectionPlan.Label.class::isInstance);
    }

    @Test
    @DisplayName("Files that grow beyond the read limit still consume the byte-bearing budget")
    void create_whenFilesGrowDuringRead_stopsOpeningAfterByteBearingLimit() throws Exception {
        Path resolvedFile = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(resolvedFile, "x");
        Path grownFile = tempDir.resolve("grown.txt");
        Files.write(grownFile, new byte[(int) ProviderAttachmentSupport.MAX_TEXT_BYTES + 1]);
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            parts.add(new FilePart(attachment(resolvedFile, "file-%02d.txt".formatted(index), "text/plain")));
        }
        AtomicInteger opens = new AtomicInteger();
        var growingSupport = new ProviderAttachmentSupport(managedRoot, ignored -> {
            opens.incrementAndGet();
            return Files.newByteChannel(grownFile);
        });

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, parts, Instant.now())),
                growingSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        assertThat(opens).hasValue(AttachmentProjectionPlan.MAX_BYTE_BEARING_OCCURRENCES);
        assertThat(subject.messages().getFirst().parts())
                .allMatch(AttachmentProjectionPlan.Label.class::isInstance);
    }

    @Test
    @DisplayName("Newest extracted attachments are retained when the aggregate text budget is reached")
    void create_whenExtractedTextBudgetIsReached_degradesOlderAttachmentToLabel() throws Exception {
        List<ContentPart> parts = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            Path file = managedRoot.resolve(UUID.randomUUID().toString());
            Files.writeString(file, "x".repeat(ProviderAttachmentSupport.MAX_EXTRACTED_CODE_POINTS));
            parts.add(new FilePart(attachment(file, "file-%02d.txt".formatted(index), "text/plain")));
        }

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, parts, Instant.now())),
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        List<AttachmentProjectionPlan.ProjectedPart> projected = subject.messages().getFirst().parts();
        assertThat(projected).hasSize(27);
        assertThat(projected.getFirst()).isEqualTo(new AttachmentProjectionPlan.Label(
                "[File attached: file-00.txt]"
        ));
        assertThat(projected.subList(1, projected.size()))
                .allMatch(AttachmentProjectionPlan.ExtractedText.class::isInstance);
    }

    @Test
    @DisplayName("Only user image and file parts may open managed attachment channels")
    void create_whenAttachmentsBelongToRestrictedRolesOrTypes_projectsMetadataWithoutReads() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(file, "private attachment text");
        AtomicInteger opens = new AtomicInteger();
        var countingSupport = new ProviderAttachmentSupport(managedRoot, path -> {
            opens.incrementAndGet();
            return Files.newByteChannel(path);
        });
        AttachmentRef ref = attachment(file, "notes.txt", "text/plain");
        List<Message> history = List.of(
                new Message(Role.SYSTEM, List.of(new FilePart(ref)), Instant.now()),
                new Message(Role.ASSISTANT, List.of(new ImagePart(ref, null, null)), Instant.now()),
                new Message(Role.USER, List.of(
                        new GeneratedImagePart(ref, null, null, "unbounded".repeat(10_000)),
                        new FilePart(ref)
                ), Instant.now())
        );

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                history,
                countingSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        assertThat(opens).hasValue(1);
        assertThat(subject.messages().stream()
                .flatMap(message -> message.parts().stream())
                .filter(AttachmentProjectionPlan.Label.class::isInstance)
                .map(AttachmentProjectionPlan.Label.class::cast)
                .map(AttachmentProjectionPlan.Label::text))
                .containsExactly(
                        "[File attached: notes.txt]",
                        "[Image attached: notes.txt]",
                        "[Generated image: notes.txt]"
                );
    }

    @Test
    @DisplayName("Extracted projection preserves the attachment label and wrapper")
    void create_whenTextIsExtracted_preservesProjectionWrapper() throws Exception {
        Path file = managedRoot.resolve(UUID.randomUUID().toString());
        Files.writeString(file, "content");
        String label = "[File attached: notes.txt]";

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(
                        Role.USER,
                        List.of(new FilePart(attachment(file, "notes.txt", "text/plain"))),
                        Instant.now()
                )),
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        assertThat(subject.messages().getFirst().parts()).containsExactly(
                new AttachmentProjectionPlan.ExtractedText(label, "content")
        );
    }

    @Test
    @DisplayName("Ordinary message text is retained unchanged outside attachment-derived counters")
    void create_whenOrdinaryTextIsLarge_doesNotChargeAttachmentBudgets() {
        String text = "message".repeat(200_000);

        AttachmentProjectionPlan subject = AttachmentProjectionPlan.create(
                List.of(new Message(Role.USER, List.of(new TextPart(text)), Instant.now())),
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                () -> false
        );

        assertThat(subject.messages().getFirst().parts()).containsExactly(
                new AttachmentProjectionPlan.PlainText(text)
        );
    }

    private AttachmentRef attachment(Path file, String name, String mimeType) throws Exception {
        return new AttachmentRef(UUID.randomUUID(), file.toString(), name, mimeType, Files.size(file), "sha");
    }

    private AttachmentRef unavailableAttachment(String name) {
        return new AttachmentRef(UUID.randomUUID(), "/unavailable", name, "text/plain", 1L, "sha");
    }
}
