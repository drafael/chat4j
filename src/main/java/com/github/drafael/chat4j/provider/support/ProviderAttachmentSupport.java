package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.ImagePart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

public final class ProviderAttachmentSupport {

    private ProviderAttachmentSupport() {
    }

    public static Optional<EncodedImage> loadEncodedImage(ImagePart imagePart) {
        String storagePath = imagePart.attachmentRef().storagePath();
        if (storagePath == null || storagePath.isBlank()) {
            return Optional.empty();
        }

        Path filePath = Path.of(storagePath);
        if (!Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        try {
            String mediaType = imagePart.attachmentRef().mimeType();
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = Files.probeContentType(filePath);
            }
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = "image/png";
            }

            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            return Optional.of(new EncodedImage(mediaType, encoded));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public record EncodedImage(String mediaType, String base64Data) {
    }
}
