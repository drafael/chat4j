package com.github.drafael.chat4j.stt.provider.sphinx4;

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

public class Sphinx4InstalledModelScanner {

    private final Sphinx4ModelValidator validator;

    public Sphinx4InstalledModelScanner(Sphinx4ModelValidator validator) {
        this.validator = validator;
    }

    public List<Sphinx4InstalledModel> scan(Path root, List<Sphinx4ModelCatalogEntry> catalog, boolean heavySelectedValidation, String selectedModelId) {
        return scan(root, catalog, heavySelectedValidation, selectedModelId, "");
    }

    public List<Sphinx4InstalledModel> scan(Path root, List<Sphinx4ModelCatalogEntry> catalog, boolean heavySelectedValidation, String selectedModelId, String forcedValidationModelId) {
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            return emptyList();
        }
        Map<String, Sphinx4ModelCatalogEntry> officialById = catalog.stream()
                .filter(entry -> StringUtils.isNotBlank(entry.id()))
                .collect(toMap(Sphinx4ModelCatalogEntry::id, Function.identity(), (existing, replacement) -> existing));
        List<Path> directories = directories(root);
        Map<String, Long> customSlugCounts = directories.stream()
                .map(path -> path.getFileName().toString())
                .filter(folderName -> !officialById.containsKey(folderName))
                .collect(groupingBy(this::customSlug, counting()));
        List<Sphinx4InstalledModel> models = new ArrayList<>();
        directories.forEach(path -> models.add(modelFor(root, path, officialById, customSlugCounts, heavySelectedValidation, selectedModelId, forcedValidationModelId)));
        return List.copyOf(models);
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

    private Sphinx4InstalledModel modelFor(
            Path root,
            Path path,
            Map<String, Sphinx4ModelCatalogEntry> officialById,
            Map<String, Long> customSlugCounts,
            boolean heavySelectedValidation,
            String selectedModelId,
            String forcedValidationModelId
    ) {
        String folderName = path.getFileName().toString();
        Sphinx4ModelCatalogEntry official = officialById.get(folderName);
        boolean custom = official == null;
        String id = custom ? customId(folderName, customSlugCounts) : folderName;
        boolean heavy = (heavySelectedValidation && StringUtils.equals(id, selectedModelId))
                || StringUtils.equals(id, forcedValidationModelId);
        Sphinx4ModelValidator.ValidationResult validation = heavy
                ? validator.validateWithRecognizer(path, root)
                : validator.validateStructural(path, root);
        if (!heavy && validation.status() == Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED && StringUtils.isBlank(selectedModelId)) {
            validation = validator.validateWithRecognizer(path, root);
        }
        Path real = null;
        try {
            real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
        }
        Sphinx4ModelMetadata metadata = validation.metadata();
        String label = custom
                ? StringUtils.defaultIfBlank(metadata == null ? "" : metadata.label(), folderName)
                : official.label();
        String language = custom
                ? StringUtils.defaultIfBlank(metadata == null ? "" : metadata.language(), "Custom")
                : official.language();
        return new Sphinx4InstalledModel(
                id,
                label,
                language,
                path,
                real,
                official,
                custom,
                path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()),
                validation.status(),
                validation.message(),
                validation.fingerprint(),
                metadata
        );
    }

    private String customId(String folderName, Map<String, Long> customSlugCounts) {
        String slug = customSlug(folderName);
        String id = slug.startsWith("local-") ? slug : "local-%s".formatted(slug);
        if (customSlugCounts.getOrDefault(slug, 0L) <= 1) {
            return id;
        }
        return "%s-%s".formatted(id, stableSuffix(folderName));
    }

    public String customSlug(String folderName) {
        String slug = StringUtils.defaultString(folderName).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "model");
    }

    public String stableSuffix(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc32.getValue());
    }
}
