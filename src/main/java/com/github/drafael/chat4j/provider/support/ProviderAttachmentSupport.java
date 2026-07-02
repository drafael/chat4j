package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.joining;

public final class ProviderAttachmentSupport {

    private static final int MAX_EXTRACTED_CHARS = 40_000;
    private static final long MAX_TEXT_ATTACHMENT_BYTES = 1_000_000L;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "rst", "adoc", "csv", "tsv",
            "json", "yaml", "yml", "xml", "toml", "ini", "conf", "properties", "env", "log",
            "html", "htm", "css", "scss", "less", "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "java", "kt", "kts", "groovy", "gradle", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp",
            "cs", "php", "swift", "scala", "sql", "sh", "zsh", "bash", "fish"
    );

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

    public static String textProjection(List<ContentPart> parts) {
        return ObjectUtils.isEmpty(parts)
            ? ""
            : parts.stream()
                .map(ProviderAttachmentSupport::textProjection)
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
    }

    public static String textProjection(ContentPart part) {
        return switch (part) {
            case FilePart filePart -> fileTextProjection(filePart);
            case null -> "";
            default -> part.asTextProjection();
        };
    }

    private static String fileTextProjection(FilePart filePart) {
        String projection = filePart.asTextProjection();
        return extractAttachmentText(filePart)
                .map(text -> "%s\n\nExtracted attachment text:\n%s".formatted(projection, text))
                .orElse(projection);
    }

    private static Optional<String> extractAttachmentText(FilePart filePart) {
        Path path = attachmentPath(filePart).orElse(null);
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try {
            String mimeType = resolvedMimeType(filePart, path);
            String extension = fileExtension(path);
            if (isPdf(mimeType, extension)) {
                return extractedText(readPdfText(path));
            }
            if (isTextFile(mimeType, extension) && Files.size(path) <= MAX_TEXT_ATTACHMENT_BYTES) {
                return extractedText(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    public static Optional<Path> attachmentPath(FilePart filePart) {
        String storagePath = filePart.attachmentRef().storagePath();
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Path.of(storagePath));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String resolvedMimeType(FilePart filePart, Path path) throws IOException {
        String mimeType = filePart.attachmentRef().mimeType();
        if (StringUtils.isBlank(mimeType)) {
            mimeType = Files.probeContentType(path);
        }
        return StringUtils.defaultString(mimeType).toLowerCase(Locale.ROOT);
    }

    public static boolean isPdf(String mimeType, String extension) {
        return "application/pdf".equals(mimeType) || "pdf".equals(extension);
    }

    public static boolean isTextFile(String mimeType, String extension) {
        return mimeType.startsWith("text/") || TEXT_EXTENSIONS.contains(extension);
    }

    private static String readPdfText(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static Optional<String> extractedText(String text) {
        String normalized = StringUtils.trimToEmpty(text);
        return StringUtils.isBlank(normalized)
                ? Optional.empty()
                : Optional.of(StringUtils.abbreviate(normalized, MAX_EXTRACTED_CHARS));
    }

    public static String fileExtension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record EncodedImage(String mediaType, String base64Data) {

        @Override
        public String toString() {
            return "EncodedImage[mediaType=%s, base64Data=<masked>]".formatted(mediaType);
        }
    }
}
