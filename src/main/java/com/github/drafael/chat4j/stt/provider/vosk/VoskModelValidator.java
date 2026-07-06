package com.github.drafael.chat4j.stt.provider.vosk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

public class VoskModelValidator {

    private static final Pattern SAFE_MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public boolean safeModelName(String name) {
        String safe = StringUtils.trimToEmpty(name);
        return !safe.isBlank()
                && !".".equals(safe)
                && !"..".equals(safe)
                && SAFE_MODEL_NAME.matcher(safe).matches()
                && !safe.contains("/")
                && !safe.contains("\\")
                && safe.chars().noneMatch(Character::isISOControl);
    }

    public ValidationResult validate(Path modelDirectory, Path managedRoot) {
        if (modelDirectory == null) {
            return new ValidationResult(VoskValidationStatus.MISSING, "Model folder is missing.", "missing");
        }
        try {
            Path normalized = modelDirectory.toAbsolutePath().normalize();
            Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
            if (!normalized.startsWith(normalizedRoot)) {
                return new ValidationResult(VoskValidationStatus.UNSAFE, "Model folder is outside the managed Vosk directory.", "unsafe");
            }
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(VoskValidationStatus.MISSING, "Model folder is missing.", "missing");
            }
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(VoskValidationStatus.INVALID, "Model path is not a folder.", fingerprint(normalized));
            }
            if (containsUnsafeSymlink(normalized, normalizedRoot)) {
                return new ValidationResult(VoskValidationStatus.UNSAFE, "Model contains a symlink outside the managed Vosk directory.", fingerprint(normalized));
            }
            boolean hasRequired = Files.isRegularFile(normalized.resolve("am").resolve("final.mdl"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(normalized.resolve("conf").resolve("model.conf"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(normalized.resolve("conf").resolve("mfcc.conf"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(normalized.resolve("graph"), LinkOption.NOFOLLOW_LINKS);
            boolean hasGraph = Files.isRegularFile(normalized.resolve("graph").resolve("HCLG.fst"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isRegularFile(normalized.resolve("graph").resolve("HCLr.fst"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(normalized.resolve("graph").resolve("Gr.fst"), LinkOption.NOFOLLOW_LINKS);
            if (hasRequired && hasGraph) {
                return new ValidationResult(VoskValidationStatus.VALID, "Ready", fingerprint(normalized));
            }
            if (Files.isDirectory(normalized.resolve("am"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(normalized.resolve("conf"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(normalized.resolve("graph"), LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(VoskValidationStatus.PLAUSIBLE_UNVERIFIED, "Model layout looks plausible but needs validation before recording.", fingerprint(normalized));
            }
            return new ValidationResult(VoskValidationStatus.INVALID, "Folder does not look like a Vosk speech-recognition model.", fingerprint(normalized));
        } catch (Exception e) {
            return new ValidationResult(VoskValidationStatus.INVALID, StringUtils.defaultIfBlank(e.getMessage(), "Could not validate model."), "error");
        }
    }

    public Path safeChild(Path root, String name) {
        if (!safeModelName(name)) {
            throw new IllegalArgumentException("Unsafe Vosk model name: %s".formatted(StringUtils.abbreviate(name, 120)));
        }
        Path child = root.resolve(name).normalize();
        if (!child.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Model path escapes the Vosk model directory.");
        }
        return child;
    }

    private boolean containsUnsafeSymlink(Path directory, Path managedRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.anyMatch(path -> unsafeSymlink(path, managedRoot));
        }
    }

    private boolean unsafeSymlink(Path path, Path managedRoot) {
        if (!Files.isSymbolicLink(path)) {
            return false;
        }
        try {
            Path real = path.toRealPath();
            return !real.startsWith(managedRoot.toRealPath());
        } catch (Exception e) {
            return true;
        }
    }

    private String fingerprint(Path directory) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String source = "%s:%s:%s".formatted(
                    directory.toAbsolutePath().normalize(),
                    attrs.lastModifiedTime().toInstant().toEpochMilli(),
                    attrs.size()
            );
            return HexFormat.of().formatHex(source.getBytes()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "unknown-%d".formatted(Instant.now().toEpochMilli());
        }
    }

    public record ValidationResult(VoskValidationStatus status, String message, String fingerprint) {
    }
}
