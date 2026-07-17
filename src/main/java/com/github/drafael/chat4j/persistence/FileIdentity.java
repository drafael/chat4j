package com.github.drafael.chat4j.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

/** Stable best-effort identity for a directory or immutable regular file. */
@EqualsAndHashCode
public final class FileIdentity {

    private final IdentityKey key;

    private FileIdentity(IdentityKey key) {
        this.key = key;
    }

    public static Optional<FileIdentity> directory(@NonNull Path path) {
        return capture(path, EntryKind.DIRECTORY);
    }

    public static Optional<FileIdentity> regularFile(@NonNull Path path) {
        return capture(path, EntryKind.REGULAR_FILE);
    }

    private static Optional<FileIdentity> capture(Path path, EntryKind expectedKind) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!expectedKind.matches(attributes) || attributes.isSymbolicLink()) {
                return Optional.empty();
            }
            Object providerKey = attributes.fileKey();
            if (providerKey == null) {
                providerKey = WindowsFileIdentity.read(path).orElse(null);
            }
            if (providerKey == null) {
                return Optional.empty();
            }
            FileTime creationTime = expectedKind == EntryKind.REGULAR_FILE ? attributes.creationTime() : null;
            FileTime lastModifiedTime = expectedKind == EntryKind.REGULAR_FILE ? attributes.lastModifiedTime() : null;
            if (expectedKind == EntryKind.REGULAR_FILE && (creationTime == null || lastModifiedTime == null)) {
                return Optional.empty();
            }
            return Optional.of(new FileIdentity(new IdentityKey(
                    providerKey,
                    expectedKind,
                    creationTime,
                    lastModifiedTime,
                    expectedKind == EntryKind.REGULAR_FILE ? attributes.size() : 0
            )));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private enum EntryKind {
        DIRECTORY {
            @Override
            boolean matches(BasicFileAttributes attributes) {
                return attributes.isDirectory();
            }
        },
        REGULAR_FILE {
            @Override
            boolean matches(BasicFileAttributes attributes) {
                return attributes.isRegularFile();
            }
        };

        abstract boolean matches(BasicFileAttributes attributes);
    }

    private record IdentityKey(
            Object providerKey,
            EntryKind kind,
            FileTime creationTime,
            FileTime lastModifiedTime,
            long size
    ) {
    }
}
