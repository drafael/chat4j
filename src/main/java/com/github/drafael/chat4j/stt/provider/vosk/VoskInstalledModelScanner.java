package com.github.drafael.chat4j.stt.provider.vosk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

public class VoskInstalledModelScanner {

    private final VoskModelValidator validator;

    public VoskInstalledModelScanner(VoskModelValidator validator) {
        this.validator = validator;
    }

    public List<VoskInstalledModel> scan(Path root, List<VoskModelCatalogEntry> catalog) {
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            return emptyList();
        }
        Map<String, VoskModelCatalogEntry> officialByName = catalog.stream()
                .filter(VoskModelCatalogEntry::speechRecognition)
                .filter(entry -> StringUtils.isNotBlank(entry.name()))
                .collect(toMap(VoskModelCatalogEntry::name, Function.identity(), (existing, replacement) -> existing));
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            directories = stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted((left, right) -> left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString()))
                    .toList();
        } catch (Exception ignored) {
        }
        Map<String, Long> customSlugCounts = directories.stream()
                .map(path -> path.getFileName().toString())
                .filter(folderName -> !officialByName.containsKey(folderName))
                .collect(groupingBy(this::customSlug, counting()));
        List<VoskInstalledModel> models = new ArrayList<>();
        directories.forEach(path -> models.add(modelFor(root, path, officialByName, customSlugCounts)));
        return List.copyOf(models);
    }

    private VoskInstalledModel modelFor(Path root, Path path, Map<String, VoskModelCatalogEntry> officialByName, Map<String, Long> customSlugCounts) {
        String folderName = path.getFileName().toString();
        VoskModelCatalogEntry official = officialByName.get(folderName);
        boolean custom = official == null;
        String id = custom ? customId(folderName, customSlugCounts) : folderName;
        VoskModelValidator.ValidationResult validation = validator.validate(path, root);
        Path real = null;
        try {
            real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
        }
        return new VoskInstalledModel(
                id,
                custom ? folderName : official.label(),
                path,
                real,
                official,
                custom,
                official != null && official.obsoleteFlag(),
                path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()),
                validation.status(),
                validation.message(),
                validation.fingerprint()
        );
    }

    private String customId(String folderName, Map<String, Long> customSlugCounts) {
        String slug = customSlug(folderName);
        if (customSlugCounts.getOrDefault(slug, 0L) <= 1) {
            return "local:%s".formatted(slug);
        }
        return "local:%s-%s".formatted(slug, stableSuffix(folderName));
    }

    private String customSlug(String folderName) {
        String slug = StringUtils.defaultString(folderName).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "model");
    }

    private String stableSuffix(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc32.getValue());
    }
}
