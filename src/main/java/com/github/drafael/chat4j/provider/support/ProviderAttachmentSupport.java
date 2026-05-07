package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.ImagePart;
import org.apache.commons.lang3.StringUtils;

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
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }

        Path filePath = Path.of(storagePath);
        if (!Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        try {
            String mediaType = imagePart.attachmentRef().mimeType();
            if (StringUtils.isBlank(mediaType)) {
                mediaType = Files.probeContentType(filePath);
            }
            if (StringUtils.isBlank(mediaType)) {
                mediaType = "image/png";
            }

            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            return Optional.of(new EncodedImage(mediaType, encoded));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public record EncodedImage(String mediaType, String base64Data) {

        @Override
        public String toString() {
            return "EncodedImage[mediaType=%s, base64Data=<masked>]".formatted(mediaType);
        }
    }
}
