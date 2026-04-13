package com.github.drafael.chat4j.provider.api.content;

import java.util.UUID;

public record AttachmentRef(
    UUID id,
    String storagePath,
    String originalName,
    String mimeType,
    long sizeBytes,
    String sha256
) {

    public AttachmentRef {
        storagePath = storagePath == null ? "" : storagePath;
        originalName = originalName == null ? "" : originalName;
        mimeType = mimeType == null ? "" : mimeType;
        sha256 = sha256 == null ? "" : sha256;
    }
}
