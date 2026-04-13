package com.github.drafael.chat4j.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class AttachmentSelectionPolicy {

    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of("exe", "app", "dmg", "pkg", "bat", "cmd", "command", "msi");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "rst", "adoc", "rtf", "pdf", "csv", "tsv",
            "json", "yaml", "yml", "xml", "toml", "ini", "conf", "properties", "env", "log",
            "html", "htm", "css", "scss", "less", "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "java", "kt", "kts", "groovy", "gradle", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp",
            "cs", "php", "swift", "scala", "sql", "sh", "zsh", "bash", "fish",
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "zip", "tar", "gz", "tgz", "bz2", "xz",
            "doc", "docx", "odt", "ppt", "pptx", "xls", "xlsx"
    );
    private static final Set<String> ALLOWED_BASENAMES = Set.of("dockerfile", "makefile", "readme", "license");
    private static final Set<String> ALLOWED_MIME_PREFIXES = Set.of("text/", "image/");
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/json", "application/xml", "application/yaml", "application/x-yaml", "application/toml",
            "application/pdf", "application/zip", "application/x-zip-compressed",
            "application/gzip", "application/x-gzip", "application/x-tar",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    Set<String> allowedExtensions() {
        return ALLOWED_EXTENSIONS;
    }

    ComposerAttachment create(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Only files can be attached.");
        }

        String mimeType = Files.probeContentType(path);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }

        long size = Files.size(path);
        String validationError = validate(path, mimeType, size);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        return new ComposerAttachment(path, mimeType, size, mimeType.startsWith("image/"));
    }

    private String validate(Path path, String mimeType, long sizeBytes) {
        if (sizeBytes > MAX_ATTACHMENT_BYTES) {
            return "File is too large (max 20MB): " + path.getFileName();
        }

        String extension = fileExtension(path);
        if (!extension.isBlank() && BLOCKED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
            return "Unsupported file type: ." + extension;
        }

        if (mimeType.startsWith("application/x-msdownload")
                || mimeType.startsWith("application/x-mach-binary")
                || mimeType.startsWith("application/x-dosexec")) {
            return "Unsupported executable file type: " + mimeType;
        }

        if (!isMimeAllowed(mimeType) && !"application/octet-stream".equals(mimeType)) {
            return "Unsupported file MIME type: " + mimeType;
        }

        if (!isExtensionAllowed(path, extension)) {
            return "Unsupported file extension: " + (extension.isBlank() ? path.getFileName() : "." + extension);
        }

        return null;
    }

    private boolean isMimeAllowed(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }

        if (ALLOWED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

        return ALLOWED_MIME_PREFIXES.stream().anyMatch(mimeType::startsWith);
    }

    private boolean isExtensionAllowed(Path path, String extension) {
        if (!extension.isBlank()) {
            return ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
        }

        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        return ALLOWED_BASENAMES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    private String fileExtension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }
}
