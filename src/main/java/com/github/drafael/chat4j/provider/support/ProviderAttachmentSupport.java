package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves and reads provider attachments only beneath one bootstrap-configured managed root.
 */
public final class ProviderAttachmentSupport {

    static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    static final long MAX_TEXT_BYTES = 1_000_000L;
    static final long MAX_PDF_BYTES = 20L * 1024L * 1024L;
    static final int MAX_EXTRACTED_CODE_POINTS = 40_000;
    static final int MAX_SAFE_NAME_CODE_POINTS = 255;

    private static final int MAX_NORMALIZED_MIME_CHARACTERS = 255;
    private static final Set<Integer> BIDI_CONTROLS = Set.of(
            0x061C, 0x200E, 0x200F,
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069
    );
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml")
    );
    private static final Map<String, String> TEXT_EXTENSIONS = Map.ofEntries(
            Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"),
            Map.entry("rst", "text/x-rst"), Map.entry("adoc", "text/asciidoc"),
            Map.entry("csv", "text/csv"), Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("json", "application/json"), Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"), Map.entry("xml", "application/xml"),
            Map.entry("toml", "application/toml"), Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"), Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("cjs", "text/javascript"), Map.entry("txt", "text/plain"),
            Map.entry("ini", "text/plain"), Map.entry("conf", "text/plain"),
            Map.entry("properties", "text/plain"), Map.entry("env", "text/plain"),
            Map.entry("log", "text/plain"), Map.entry("scss", "text/plain"),
            Map.entry("less", "text/plain"), Map.entry("jsx", "text/plain"),
            Map.entry("ts", "text/plain"), Map.entry("tsx", "text/plain"),
            Map.entry("java", "text/plain"), Map.entry("kt", "text/plain"),
            Map.entry("kts", "text/plain"), Map.entry("groovy", "text/plain"),
            Map.entry("gradle", "text/plain"), Map.entry("py", "text/plain"),
            Map.entry("rb", "text/plain"), Map.entry("go", "text/plain"),
            Map.entry("rs", "text/plain"), Map.entry("c", "text/plain"),
            Map.entry("h", "text/plain"), Map.entry("cc", "text/plain"),
            Map.entry("cpp", "text/plain"), Map.entry("hpp", "text/plain"),
            Map.entry("cs", "text/plain"), Map.entry("php", "text/plain"),
            Map.entry("swift", "text/plain"), Map.entry("scala", "text/plain"),
            Map.entry("sql", "text/plain"), Map.entry("sh", "text/plain"),
            Map.entry("zsh", "text/plain"), Map.entry("bash", "text/plain"),
            Map.entry("fish", "text/plain")
    );
    private static final Set<String> SPECIFIC_IMAGE_MIMES = Set.copyOf(IMAGE_EXTENSIONS.values());
    private static final Set<String> STRUCTURED_TEXT_MIMES = Set.of(
            "application/json", "application/yaml", "application/xml", "application/toml"
    );

    private final Path managedRoot;
    private final ChannelOpener channelOpener;

    public ProviderAttachmentSupport(@NonNull Path managedRoot) throws IOException {
        this(managedRoot, path -> Files.newByteChannel(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        ));
    }

    ProviderAttachmentSupport(@NonNull Path managedRoot, @NonNull ChannelOpener channelOpener) throws IOException {
        Path resolvedRoot = managedRoot.toRealPath();
        if (!Files.isDirectory(resolvedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed attachment root is not a directory.");
        }
        this.managedRoot = resolvedRoot;
        this.channelOpener = channelOpener;
    }

    Path managedRoot() {
        return managedRoot;
    }

    /**
     * Resolves live file metadata. Display size and storage-path spelling never authorize a read.
     */
    Optional<ResolvedAttachment> resolve(@NonNull AttachmentRef attachmentRef, boolean image) {
        if (StringUtils.isBlank(attachmentRef.storagePath())) {
            return Optional.empty();
        }
        try {
            Path configuredCandidate = Path.of(attachmentRef.storagePath());
            if (!configuredCandidate.isAbsolute()) {
                return Optional.empty();
            }
            Path candidate = configuredCandidate.toRealPath();
            if (!candidate.startsWith(managedRoot)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            long actualSize = Files.size(candidate);
            if (actualSize <= 0) {
                return Optional.empty();
            }
            String safeName = safeName(attachmentRef.originalName(), image);
            String extension = recognizedExtension(safeName);
            MimeDecision mimeDecision = decideMime(attachmentRef.mimeType(), extension);
            return Optional.of(new ResolvedAttachment(
                    candidate,
                    actualSize,
                    safeName,
                    mimeDecision.mimeType(),
                    mimeDecision.kind()
            ));
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    Optional<BoundedBytes> readBytes(@NonNull ResolvedAttachment attachment, long maximumBytes) {
        if (maximumBytes <= 0 || attachment.actualSize() <= 0 || attachment.actualSize() > maximumBytes) {
            return Optional.empty();
        }
        long total = 0;
        try (SeekableByteChannel channel = channelOpener.open(attachment.canonicalPath())) {
            int initialCapacity = (int) Math.min(attachment.actualSize(), 64 * 1024L);
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (total < maximumBytes) {
                int allowedRead = (int) Math.min(buffer.capacity(), maximumBytes - total);
                buffer.clear();
                buffer.limit(allowedRead);
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                total += read;
                output.write(buffer.array(), 0, read);
            }
            if (channel.size() > total) {
                return Optional.of(BoundedBytes.incomplete(total));
            }
            return total == 0
                    ? Optional.empty()
                    : Optional.of(BoundedBytes.complete(output.toByteArray(), total));
        } catch (IOException | RuntimeException e) {
            return total == 0 ? Optional.empty() : Optional.of(BoundedBytes.incomplete(total));
        }
    }

    Optional<ExtractedAttachment> extractedAttachment(
            @NonNull ResolvedAttachment attachment,
            @NonNull BoundedBytes bytes
    ) {
        if (!bytes.complete()) {
            return Optional.empty();
        }
        return switch (attachment.kind()) {
            case TEXT -> decodeStrictUtf8(bytes).map(ExtractedAttachment::new);
            case PDF -> extractPdfText(bytes).map(ExtractedAttachment::new);
            default -> Optional.empty();
        };
    }

    /**
     * Builds a metadata-only label without resolving, stating, or reading an attachment path.
     */
    String safeLabel(@NonNull ContentPart part) {
        return switch (part) {
            case ImagePart imagePart -> "[Image attached: %s]".formatted(
                    safeName(imagePart.attachmentRef().originalName(), true));
            case FilePart filePart -> "[File attached: %s]".formatted(
                    safeName(filePart.attachmentRef().originalName(), false));
            case GeneratedImagePart generatedImagePart -> "[Generated image: %s]".formatted(
                    safeName(generatedImagePart.attachmentRef().originalName(), true));
            case TextPart textPart -> textPart.text();
        };
    }

    String safeName(String originalName, boolean image) {
        String source = StringUtils.defaultString(originalName);
        int basenameStart = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\')) + 1;
        String basename = normalizeUtf16(source.substring(basenameStart));
        StringBuilder filtered = new StringBuilder(basename.length());
        basename.codePoints()
                .filter(codePoint -> !isRemovedControl(codePoint))
                .forEach(filtered::appendCodePoint);
        String cleaned = trimSpecifiedWhitespace(filtered.toString());
        String suffix = recognizedExtension(cleaned);
        int suffixCodePoints = suffix.isEmpty() ? 0 : suffix.codePointCount(0, suffix.length()) + 1;
        String stem = suffix.isEmpty()
                ? cleaned
                : trimSpecifiedWhitespace(cleaned.substring(0, cleaned.lastIndexOf('.')));
        stem = truncateCodePoints(stem, MAX_SAFE_NAME_CODE_POINTS - suffixCodePoints);
        if (StringUtils.isEmpty(stem)) {
            stem = image ? "Image" : "Attachment";
        }
        return suffix.isEmpty() ? stem : "%s.%s".formatted(stem, suffix);
    }

    boolean isSingleFrameGif(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return false;
            }
            var readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                return reader.getNumImages(true) == 1;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Optional<String> decodeStrictUtf8(BoundedBytes bytes) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.bytes()))
                    .toString();
            return normalizeExtractedText(decoded);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private Optional<String> extractPdfText(BoundedBytes bytes) {
        try (PDDocument document = Loader.loadPDF(bytes.bytes())) {
            if (document.isEncrypted()) {
                return Optional.empty();
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(100, document.getNumberOfPages()));
            BoundedTextWriter writer = new BoundedTextWriter(MAX_EXTRACTED_CODE_POINTS + 1);
            stripper.writeText(document, writer);
            return normalizeExtractedText(writer.value());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeExtractedText(String text) {
        String normalized = normalizeUtf16(text).replace("\r\n", "\n").replace('\r', '\n').strip();
        if (StringUtils.isBlank(normalized)) {
            return Optional.empty();
        }
        return Optional.of(truncateCodePoints(normalized, MAX_EXTRACTED_CODE_POINTS));
    }

    private static MimeDecision decideMime(String storedMime, String extension) {
        String parsedMime = parseMime(storedMime);
        String inferredImage = IMAGE_EXTENSIONS.get(extension);
        String inferredText = TEXT_EXTENSIONS.get(extension);
        String inferredPdf = "pdf".equals(extension) ? "application/pdf" : "";

        if (StringUtils.isBlank(parsedMime)) {
            if (StringUtils.isNotBlank(inferredImage)) {
                return new MimeDecision(inferredImage, AttachmentKind.IMAGE);
            }
            if (StringUtils.isNotBlank(inferredPdf)) {
                return new MimeDecision(inferredPdf, AttachmentKind.PDF);
            }
            if (StringUtils.isNotBlank(inferredText)) {
                return new MimeDecision(inferredText, AttachmentKind.TEXT);
            }
            return new MimeDecision("", AttachmentKind.UNKNOWN);
        }

        AttachmentKind mimeKind = mimeKind(parsedMime);
        if (StringUtils.isNotBlank(inferredImage)) {
            return inferredImage.equals(parsedMime)
                    ? new MimeDecision(parsedMime, AttachmentKind.IMAGE)
                    : new MimeDecision(parsedMime, AttachmentKind.CONFLICT);
        }
        if (StringUtils.isNotBlank(inferredPdf)) {
            return inferredPdf.equals(parsedMime)
                    ? new MimeDecision(parsedMime, AttachmentKind.PDF)
                    : new MimeDecision(parsedMime, AttachmentKind.CONFLICT);
        }
        if (StringUtils.isNotBlank(inferredText)) {
            return mimeKind == AttachmentKind.TEXT
                    ? new MimeDecision(parsedMime, AttachmentKind.TEXT)
                    : new MimeDecision(parsedMime, AttachmentKind.CONFLICT);
        }
        return new MimeDecision(parsedMime, mimeKind);
    }

    private static String parseMime(String storedMime) {
        String source = StringUtils.trimToEmpty(storedMime);
        int parameterStart = source.indexOf(';');
        String token = StringUtils.trim(parameterStart < 0 ? source : source.substring(0, parameterStart));
        if (StringUtils.isEmpty(token)
                || token.length() > MAX_NORMALIZED_MIME_CHARACTERS
                || token.indexOf(',') >= 0) {
            return "";
        }
        int slash = token.indexOf('/');
        if (slash <= 0 || slash != token.lastIndexOf('/') || slash == token.length() - 1) {
            return "";
        }
        for (int index = 0; index < token.length(); index++) {
            if (index != slash && !isRfcTokenCharacter(token.charAt(index))) {
                return "";
            }
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(normalized)) {
            return "";
        }
        return switch (normalized) {
            case "image/jpg" -> "image/jpeg";
            case "application/x-pdf" -> "application/pdf";
            case "text/json", "application/x-json" -> "application/json";
            case "text/xml" -> "application/xml";
            case "text/yaml", "application/x-yaml" -> "application/yaml";
            case "application/javascript" -> "text/javascript";
            default -> normalized;
        };
    }

    private static AttachmentKind mimeKind(String mime) {
        if (SPECIFIC_IMAGE_MIMES.contains(mime)) {
            return AttachmentKind.IMAGE;
        }
        if ("application/pdf".equals(mime)) {
            return AttachmentKind.PDF;
        }
        if (mime.startsWith("text/") || STRUCTURED_TEXT_MIMES.contains(mime)) {
            return AttachmentKind.TEXT;
        }
        return AttachmentKind.UNKNOWN;
    }

    private static boolean isRfcTokenCharacter(char character) {
        return character >= '0' && character <= '9'
                || character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || "!#$%&'*+.^_`|~-".indexOf(character) >= 0;
    }

    private static String recognizedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return recognizedExtensionValue(name.substring(dot + 1));
    }

    private static String recognizedExtensionValue(String extension) {
        String normalized = extension.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.containsKey(normalized)
                || TEXT_EXTENSIONS.containsKey(normalized)
                || "pdf".equals(normalized)
                ? normalized
                : "";
    }

    private static String trimSpecifiedWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpecifiedWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isSpecifiedWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean isSpecifiedWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isRemovedControl(int codePoint) {
        return codePoint <= 0x1F
                || codePoint >= 0x7F && codePoint <= 0x9F
                || BIDI_CONTROLS.contains(codePoint);
    }

    private static String normalizeUtf16(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    normalized.append(current).append(value.charAt(++index));
                } else {
                    normalized.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(current)) {
                normalized.append('\uFFFD');
            } else {
                normalized.append(current);
            }
        }
        return normalized.toString();
    }

    private static String truncateCodePoints(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maximumCodePoints
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    enum AttachmentKind {
        IMAGE,
        PDF,
        TEXT,
        UNKNOWN,
        CONFLICT
    }

    record ResolvedAttachment(
            Path canonicalPath,
            long actualSize,
            String safeName,
            String mimeType,
            AttachmentKind kind
    ) {
        @Override
        public String toString() {
            return "ResolvedAttachment[canonicalPath=<masked>, actualSize=%d, safeName=%s, mimeType=%s, kind=%s]".formatted(
                    actualSize,
                    safeName,
                    mimeType,
                    kind
            );
        }
    }

    record BoundedBytes(byte[] bytes, long actualBytes, boolean complete) {
        private static BoundedBytes complete(byte[] bytes, long actualBytes) {
            return new BoundedBytes(bytes, actualBytes, true);
        }

        private static BoundedBytes incomplete(long actualBytes) {
            return new BoundedBytes(new byte[0], actualBytes, false);
        }

        @Override
        public String toString() {
            return "BoundedBytes[bytes=<masked>, actualBytes=%d, complete=%s]".formatted(actualBytes, complete);
        }
    }

    record ExtractedAttachment(String text) {
    }

    @FunctionalInterface
    interface ChannelOpener {
        SeekableByteChannel open(Path path) throws IOException;
    }

    private record MimeDecision(String mimeType, AttachmentKind kind) {
    }

    private static final class BoundedTextWriter extends Writer {
        private final int maximumCodePoints;
        private final StringBuilder value = new StringBuilder();
        private int codePoints;
        private Character pendingHighSurrogate;

        private BoundedTextWriter(int maximumCodePoints) {
            this.maximumCodePoints = maximumCodePoints;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            int end = offset + length;
            int index = offset;
            if (pendingHighSurrogate != null && index < end && codePoints < maximumCodePoints) {
                char current = characters[index];
                if (Character.isLowSurrogate(current)) {
                    value.append(pendingHighSurrogate).append(current);
                    index++;
                } else {
                    value.append('\uFFFD');
                }
                pendingHighSurrogate = null;
                codePoints++;
            }
            while (index < end && codePoints < maximumCodePoints) {
                char current = characters[index++];
                if (Character.isHighSurrogate(current)) {
                    if (index < end && Character.isLowSurrogate(characters[index])) {
                        value.append(current).append(characters[index++]);
                        codePoints++;
                    } else if (index == end) {
                        pendingHighSurrogate = current;
                    } else {
                        value.append('\uFFFD');
                        codePoints++;
                    }
                } else if (Character.isLowSurrogate(current)) {
                    value.append('\uFFFD');
                    codePoints++;
                } else {
                    value.append(current);
                    codePoints++;
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String value() {
            if (pendingHighSurrogate != null && codePoints < maximumCodePoints) {
                value.append('\uFFFD');
                pendingHighSurrogate = null;
                codePoints++;
            }
            return value.toString();
        }
    }
}
