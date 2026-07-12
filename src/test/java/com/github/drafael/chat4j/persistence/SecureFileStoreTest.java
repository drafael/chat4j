package com.github.drafael.chat4j.persistence;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecureFileStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Secret directories are created with owner-only POSIX permissions")
    void createOwnerOnlyDirectory_whenPosixSupported_setsOwnerOnlyPermissions() throws Exception {
        assumeTrue(supportsPosixAttributes(tempDir));
        Path subject = tempDir.resolve("secrets");

        SecureFileStore.createOwnerOnlyDirectory(subject);

        assertThat(Files.getPosixFilePermissions(subject)).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
    }

    @Test
    @DisplayName("Secret temp files are created and moved with owner-only POSIX permissions")
    void writeStringAtomically_whenPosixSupported_setsOwnerOnlyFilePermissions() throws Exception {
        assumeTrue(supportsPosixAttributes(tempDir));
        Path subject = tempDir.resolve("secrets").resolve("vault.json");

        SecureFileStore.writeStringAtomically(subject, "secret", "vault");

        assertThat(Files.readString(subject)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(subject)).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
        assertThat(Files.getPosixFilePermissions(subject.getParent())).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
    }

    @Test
    @DisplayName("Secret files are still written on non-POSIX file systems")
    void writeStringAtomically_whenPosixUnsupported_writesFile() throws Exception {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.windows())) {
            Path directory = fileSystem.getPath("C:\\secrets");
            Path subject = directory.resolve("vault.json");

            SecureFileStore.createOwnerOnlyDirectory(directory);
            SecureFileStore.writeStringAtomically(subject, "secret", "vault");

            assertThat(supportsPosixAttributes(directory)).isFalse();
            assertThat(Files.isDirectory(directory)).isTrue();
            assertThat(Files.readString(subject)).isEqualTo("secret");
        }
    }

    private boolean supportsPosixAttributes(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
