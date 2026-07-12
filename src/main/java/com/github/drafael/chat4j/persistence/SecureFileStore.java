package com.github.drafael.chat4j.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SecureFileStore {

    private static final FileAttribute<?>[] NO_FILE_ATTRIBUTES = new FileAttribute<?>[0];
    private static final FileAttribute<?> OWNER_ONLY_DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")
    );
    private static final FileAttribute<?> OWNER_ONLY_FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")
    );

    private SecureFileStore() {
    }

    public static void createOwnerOnlyDirectory(Path directory) throws IOException {
        Files.createDirectories(directory, ownerOnlyDirectoryAttributes(directory));
        applyOwnerOnlyDirectoryPermissions(directory);
    }

    public static void writeStringAtomically(Path targetFile, String content, String tempPrefix) throws IOException {
        Path parent = targetFile.getParent();
        createOwnerOnlyDirectory(parent);
        Path tempFile = Files.createTempFile(parent, tempPrefix, ".tmp", ownerOnlyFileAttributes(parent));
        try {
            applyOwnerOnlyFilePermissions(tempFile);
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            applyOwnerOnlyFilePermissions(tempFile);
            moveAtomically(tempFile, targetFile);
            applyOwnerOnlyFilePermissions(targetFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public static void applyOwnerOnlyFilePermissions(Path file) {
        try {
            if (supportsPosixAttributes(file)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (Exception e) {
            log.debug("Unable to apply owner-only file permissions to {}: {}", file, e.getMessage());
        }
    }

    public static void applyOwnerOnlyDirectoryPermissions(Path directory) {
        try {
            if (supportsPosixAttributes(directory)) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (Exception e) {
            log.debug("Unable to apply owner-only directory permissions to {}: {}", directory, e.getMessage());
        }
    }

    private static FileAttribute<?>[] ownerOnlyDirectoryAttributes(Path directory) {
        return supportsPosixAttributes(directory)
                ? new FileAttribute<?>[] {OWNER_ONLY_DIRECTORY_ATTRIBUTE}
                : NO_FILE_ATTRIBUTES;
    }

    private static FileAttribute<?>[] ownerOnlyFileAttributes(Path file) {
        return supportsPosixAttributes(file)
                ? new FileAttribute<?>[] {OWNER_ONLY_FILE_ATTRIBUTE}
                : NO_FILE_ATTRIBUTES;
    }

    private static boolean supportsPosixAttributes(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void moveAtomically(Path sourceFile, Path targetFile) throws IOException {
        try {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
