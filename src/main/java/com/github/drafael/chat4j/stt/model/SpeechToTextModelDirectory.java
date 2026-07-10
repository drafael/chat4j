package com.github.drafael.chat4j.stt.model;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public class SpeechToTextModelDirectory {

    public static final String SETTINGS_KEY = "chat4j.stt.models.dir";

    private final SettingsRepository settingsRepo;
    private final Path defaultDirectory;

    public SpeechToTextModelDirectory(@NonNull SettingsRepository settingsRepo, @NonNull Path defaultDirectory) {
        this.settingsRepo = settingsRepo;
        this.defaultDirectory = defaultDirectory;
    }

    public Path resolve() {
        String saved = settingsRepo.get(SETTINGS_KEY, "");
        return StringUtils.isBlank(saved) ? defaultDirectory.toAbsolutePath().normalize() : normalize(saved);
    }

    public Path saveAndCreate(String rawPath) throws Exception {
        Path normalized = normalize(StringUtils.defaultIfBlank(rawPath, defaultDirectory.toString()));
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Speech-to-text model directory must be a folder.");
        }
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized) || !Files.isWritable(normalized)) {
            throw new IllegalArgumentException("Speech-to-text model directory is not writable.");
        }
        settingsRepo.put(SETTINGS_KEY, normalized.toString());
        return normalized;
    }

    public Path directoryFor(String providerId, String modelId) {
        return resolve().resolve(slug(providerId)).resolve(slug(modelId));
    }

    public static String slug(String value) {
        String slug = StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "unknown");
    }

    private Path normalize(String rawPath) {
        String expanded = expandUserHome(rawPath);
        Path path = Path.of(expanded);
        return path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
    }

    private String expandUserHome(String rawPath) {
        String trimmed = StringUtils.trimToEmpty(rawPath);
        if (trimmed.equals("~")) {
            return System.getProperty("user.home");
        }
        if (trimmed.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), trimmed.substring(2)).toString();
        }
        return trimmed;
    }
}
