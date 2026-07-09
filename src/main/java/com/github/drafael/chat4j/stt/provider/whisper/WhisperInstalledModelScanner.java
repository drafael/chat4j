package com.github.drafael.chat4j.stt.provider.whisper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

public class WhisperInstalledModelScanner {

    public static final String MODEL_FILE_NAME = "model.bin";
    public static final String METADATA_FILE_NAME = "metadata.properties";
    public static final String METADATA_VERSION = "1";

    public List<WhisperInstalledModel> scan(Path root, List<WhisperModelCatalogEntry> catalog) {
        return scan(root, catalog, false);
    }

    public List<WhisperInstalledModel> scan(Path root, List<WhisperModelCatalogEntry> catalog, boolean forceHash) {
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            return emptyList();
        }
        Map<String, WhisperModelCatalogEntry> bySlug = catalog.stream().collect(toMap(entry -> slug(entry.id()), entry -> entry));
        List<Path> directories = directories(root);
        List<WhisperInstalledModel> models = new ArrayList<>();
        directories.stream()
                .filter(path -> bySlug.containsKey(path.getFileName().toString()))
                .map(path -> modelFor(root, path, bySlug.get(path.getFileName().toString()), forceHash))
                .forEach(models::add);
        return List.copyOf(models);
    }

    public ValidationResult validateInstall(Path directory, Path root, WhisperModelCatalogEntry entry) {
        return validate(directory, root, entry, true);
    }

    private WhisperInstalledModel modelFor(Path root, Path directory, WhisperModelCatalogEntry entry, boolean forceHash) {
        ValidationResult validation = validate(directory, root, entry, forceHash);
        Path real = null;
        try {
            real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
        }
        return new WhisperInstalledModel(
                entry.id(),
                entry.label(),
                directory,
                real,
                entry,
                true,
                validation.status(),
                validation.message(),
                validation.fingerprint()
        );
    }

    private ValidationResult validate(Path directory, Path root, WhisperModelCatalogEntry entry, boolean forceHash) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedDirectory = directory.toAbsolutePath().normalize();
            if (!normalizedDirectory.startsWith(normalizedRoot) || Files.isSymbolicLink(directory)) {
                return invalid(WhisperValidationStatus.UNSAFE, "Model directory is unsafe.");
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(WhisperValidationStatus.MISSING, "Model directory is missing.");
            }
            Path metadata = normalizedDirectory.resolve(METADATA_FILE_NAME).normalize();
            Path model = normalizedDirectory.resolve(MODEL_FILE_NAME).normalize();
            if (!metadata.startsWith(normalizedDirectory) || !model.startsWith(normalizedDirectory)) {
                return invalid(WhisperValidationStatus.UNSAFE, "Model files are unsafe.");
            }
            if (Files.isSymbolicLink(metadata) || Files.isSymbolicLink(model)) {
                return invalid(WhisperValidationStatus.UNSAFE, "Model files must not be symbolic links.");
            }
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(WhisperValidationStatus.MISSING, "Whisper.cpp model files are missing.");
            }
            Properties properties = properties(metadata);
            if (!Objects.equals(METADATA_VERSION, properties.getProperty("metadataVersion"))
                    || !Objects.equals(entry.id(), properties.getProperty("id"))
                    || !Objects.equals(entry.downloadUri().toString(), properties.getProperty("sourceUrl"))
                    || !Objects.equals(entry.expectedFileName(), properties.getProperty("expectedFileName"))
                    || !Objects.equals(Long.toString(entry.sizeBytes()), properties.getProperty("sizeBytes"))
                    || !Objects.equals(entry.sha1(), properties.getProperty("sha1"))) {
                return invalid(WhisperValidationStatus.INVALID, "Whisper.cpp model metadata does not match the catalog.");
            }
            BasicFileAttributes attributes = Files.readAttributes(model, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() != entry.sizeBytes()) {
                return invalid(WhisperValidationStatus.INVALID, "Whisper.cpp model file size does not match the catalog.");
            }
            if (forceHash) {
                String sha1 = sha1(model);
                if (!Objects.equals(entry.sha1(), sha1)) {
                    return invalid(WhisperValidationStatus.INVALID, "Whisper.cpp model checksum does not match the catalog.");
                }
            }
            return new ValidationResult(WhisperValidationStatus.VALID, "Installed", fingerprint(entry));
        } catch (Exception e) {
            return invalid(WhisperValidationStatus.INVALID, StringUtils.defaultIfBlank(e.getMessage(), "Whisper.cpp model is invalid."));
        }
    }

    private List<Path> directories(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted((left, right) -> left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString()))
                    .toList();
        } catch (Exception e) {
            return emptyList();
        }
    }

    private Properties properties(Path metadata) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        return properties;
    }

    static String sha1(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify Whisper.cpp model checksum.", e);
        }
    }

    static String fingerprint(WhisperModelCatalogEntry entry) {
        return "%s:%d:%s".formatted(entry.sha1(), entry.sizeBytes(), METADATA_VERSION);
    }

    static String slug(String id) {
        return com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory.slug(id);
    }

    private ValidationResult invalid(WhisperValidationStatus status, String message) {
        return new ValidationResult(status, message, "invalid");
    }

    public record ValidationResult(WhisperValidationStatus status, String message, String fingerprint) {
    }

}
