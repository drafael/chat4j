package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Comparator.reverseOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentStagerTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Long display names are preserved without entering the managed storage filename")
    void stage_whenSourceNameIsLong_usesBoundedUuidStorageName() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new AttachmentStager(storagePaths);
        String displayName = "%s.txt".formatted("a".repeat(236));
        Path source = Files.writeString(tempDir.resolve(displayName), "content");

        AttachmentRef staged = subject.stage(new ComposerAttachment(
                source,
                "text/plain",
                Files.size(source),
                false
        ));

        assertThat(staged.originalName()).isEqualTo(displayName);
        assertThat(Path.of(staged.storagePath())).exists();
        assertThat(Path.of(staged.storagePath()).getFileName().toString()).isEqualTo(staged.id().toString());
    }

    @Test
    @DisplayName("Staged attachments persist an absolute canonical storage path")
    void stage_whenConfigHomeIsRelative_persistsAbsoluteStoragePath() throws Exception {
        Path relativeConfigHome = Path.of(
                "target",
                "attachment-stager-relative",
                UUID.randomUUID().toString()
        );
        StoragePaths storagePaths = StoragePaths.ofConfigHome(relativeConfigHome);
        var subject = new AttachmentStager(storagePaths);
        Path source = Files.writeString(tempDir.resolve("relative-source.txt"), "content");
        AttachmentRef staged = null;
        try {
            staged = subject.stage(new ComposerAttachment(
                    source,
                    "text/plain",
                    Files.size(source),
                    false
            ));

            assertThat(Path.of(staged.storagePath()))
                    .isAbsolute()
                    .isRegularFile()
                    .startsWith(storagePaths.attachmentsDirectory().toRealPath());
        } finally {
            subject.discard(staged);
            deleteRecursively(relativeConfigHome);
        }
    }

    @Test
    @DisplayName("A file that grows after selection cannot exceed the attachment limit during staging")
    void stage_whenSourceGrowsBeyondSelectionLimit_rejectsAndRemovesPartialFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("growing-source.txt");
        try (var channel = Files.newByteChannel(
                source,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            channel.position(AttachmentSelectionPolicy.MAX_ATTACHMENT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertThatThrownBy(() -> subject.stage(new ComposerAttachment(source, "text/plain", 1, false)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("File is too large (max 20MB): growing-source.txt");
        try (var paths = Files.walk(storagePaths.attachmentsDirectory())) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("A symlinked day directory cannot redirect attachment publication")
    void stage_whenDayDirectoryIsSymlink_rejectsPublication() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        Path attachmentRoot = Files.createDirectories(storagePaths.attachmentsDirectory());
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path dayDirectory = attachmentRoot.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        try {
            Files.createSymbolicLink(dayDirectory, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        Path source = Files.writeString(tempDir.resolve("source-for-symlink.txt"), "content");
        var subject = new AttachmentStager(storagePaths);

        assertThatThrownBy(() -> subject.stage(new ComposerAttachment(
                source,
                "text/plain",
                Files.size(source),
                false
        ))).isInstanceOf(java.io.IOException.class)
                .hasMessage("Managed attachment directory must not be a symbolic link.");
        try (var outsideFiles = Files.list(outside)) {
            assertThat(outsideFiles).isEmpty();
        }
    }

    @Test
    @DisplayName("Managed path resolution rejects files outside attachment storage")
    void managedPath_whenPathIsExternal_rejectsPath() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new AttachmentStager(storagePaths);
        Path external = Files.writeString(tempDir.resolve("external-open.txt"), "external");

        assertThat(subject.managedPath(external.toString())).isEmpty();
    }

    @Test
    @DisplayName("Managed path resolution rejects a managed leaf that links outside storage")
    void managedPath_whenManagedLeafLinksOutside_rejectsPath() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        Path attachmentRoot = Files.createDirectories(storagePaths.attachmentsDirectory());
        var subject = new AttachmentStager(storagePaths);
        Path external = Files.writeString(tempDir.resolve("external-link-target.txt"), "external");
        Path link = attachmentRoot.resolve("linked");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        assertThat(subject.managedPath(link.toString())).isEmpty();
    }

    @Test
    @DisplayName("Discard removes a substituted symlink instead of its managed target")
    void discard_whenManagedLeafBecomesSymlink_preservesTarget() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        Files.createDirectories(storagePaths.attachmentsDirectory());
        var subject = new AttachmentStager(storagePaths);
        Path dayDirectory = Files.createDirectories(storagePaths.attachmentsDirectory().resolve("20260727"));
        Path target = Files.writeString(dayDirectory.resolve("target"), "keep");
        Path substituted = dayDirectory.resolve("substituted");
        try {
            Files.createSymbolicLink(substituted, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        var ref = new AttachmentRef(
                UUID.randomUUID(),
                substituted.toString(),
                "image.png",
                "image/png",
                Files.size(target),
                "sha"
        );

        subject.discard(ref);

        assertThat(substituted).doesNotExist();
        assertThat(target).exists().hasContent("keep");
    }

    @Test
    @DisplayName("Discard removes staged files but never removes files outside managed attachment storage")
    void discard_whenReferencesAreManagedAndExternal_deletesOnlyManagedFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new AttachmentStager(storagePaths);
        Path source = Files.writeString(tempDir.resolve("source.txt"), "content");
        AttachmentRef staged = subject.stage(new ComposerAttachment(
                source,
                "text/plain",
                Files.size(source),
                false
        ));
        Path external = Files.writeString(tempDir.resolve("external.txt"), "external");
        var externalRef = new AttachmentRef(
                UUID.randomUUID(),
                external.toString(),
                "external.txt",
                "text/plain",
                Files.size(external),
                "sha"
        );

        subject.discard(staged);
        subject.discard(externalRef);

        assertThat(Path.of(staged.storagePath())).doesNotExist();
        assertThat(external).exists();
    }
}
