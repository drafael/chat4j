package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("Managed path resolution rejects files outside attachment storage")
    void managedPath_whenPathIsExternal_rejectsPath() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new AttachmentStager(storagePaths);
        Path external = Files.writeString(tempDir.resolve("external-open.txt"), "external");

        assertThat(subject.managedPath(external.toString())).isEmpty();
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
