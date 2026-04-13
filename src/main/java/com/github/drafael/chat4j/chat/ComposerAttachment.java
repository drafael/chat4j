package com.github.drafael.chat4j.chat;

import java.nio.file.Path;
import java.util.Objects;

public record ComposerAttachment(Path path, String mimeType, long sizeBytes, boolean image) {

    public ComposerAttachment {
        path = Objects.requireNonNull(path, "path can't be null").toAbsolutePath().normalize();
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
    }

    public String displayName() {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
