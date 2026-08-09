package com.github.drafael.chat4j.chat.export.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum PdfExportImageFormat {

    PNG(".png", "image/png", true),
    JPEG(".jpg", "image/jpeg", true),
    GIF(".gif", "image/gif", false);

    private final String extension;
    private final String mimeType;
    private final boolean publicationPassthrough;

    static Optional<PdfExportImageFormat> detect(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            byte[] signature = input.readNBytes(8);
            if (isPng(signature)) {
                return Optional.of(PNG);
            }
            if (isJpeg(signature)) {
                return Optional.of(JPEG);
            }
            return isGif(signature) ? Optional.of(GIF) : Optional.empty();
        }
    }

    private static boolean isPng(byte[] signature) {
        return signature.length >= 8
                && (signature[0] & 0xff) == 0x89
                && signature[1] == 0x50
                && signature[2] == 0x4e
                && signature[3] == 0x47
                && signature[4] == 0x0d
                && signature[5] == 0x0a
                && signature[6] == 0x1a
                && signature[7] == 0x0a;
    }

    private static boolean isJpeg(byte[] signature) {
        return signature.length >= 3
                && (signature[0] & 0xff) == 0xff
                && (signature[1] & 0xff) == 0xd8
                && (signature[2] & 0xff) == 0xff;
    }

    private static boolean isGif(byte[] signature) {
        return signature.length >= 6
                && signature[0] == 'G'
                && signature[1] == 'I'
                && signature[2] == 'F'
                && signature[3] == '8'
                && (signature[4] == '7' || signature[4] == '9')
                && signature[5] == 'a';
    }
}
