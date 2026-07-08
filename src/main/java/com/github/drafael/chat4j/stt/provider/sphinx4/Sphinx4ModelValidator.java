package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import edu.cmu.sphinx.api.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Sphinx4ModelValidator {

    private static final Pattern SAFE_MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RUNTIME_VERSION = Sphinx4ModelValidator.class.getPackage().getImplementationVersion() == null
            ? "sphinx4-core-5prealpha"
            : Sphinx4ModelValidator.class.getPackage().getImplementationVersion();

    private final Sphinx4RecognizerAdapter recognizerAdapter;
    private final Map<CacheKey, ValidationResult> heavyValidationCache = new ConcurrentHashMap<>();

    public Sphinx4ModelValidator() {
        this(Sphinx4RecognizerAdapter.defaultAdapter());
    }

    public Sphinx4ModelValidator(Sphinx4RecognizerAdapter recognizerAdapter) {
        this.recognizerAdapter = recognizerAdapter;
    }

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

    public Path safeChild(Path root, String name) {
        if (!safeModelName(name)) {
            throw new IllegalArgumentException("Unsafe Sphinx4 model name: %s".formatted(StringUtils.abbreviate(name, 120)));
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path child = normalizedRoot.resolve(name).normalize();
        if (!child.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Model path escapes the Sphinx4 model directory.");
        }
        return child;
    }

    public ValidationResult validateStructural(Path modelDirectory, Path managedRoot) {
        return validate(modelDirectory, managedRoot, false);
    }

    public ValidationResult validateWithRecognizer(Path modelDirectory, Path managedRoot) {
        return validate(modelDirectory, managedRoot, true);
    }

    public Optional<Sphinx4ModelMetadata> readMetadata(Path modelDirectory) {
        try {
            Path metadataFile = modelDirectory.resolve(Sphinx4ModelMetadata.FILE_NAME);
            if (!Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(OBJECT_MAPPER.readValue(metadataFile.toFile(), Sphinx4ModelMetadata.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void writeMetadata(Path modelDirectory, Sphinx4ModelMetadata metadata) throws IOException {
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(modelDirectory.resolve(Sphinx4ModelMetadata.FILE_NAME).toFile(), metadata);
    }

    public ValidationResult validateRecipe(Path modelDirectory, Path managedRoot, Sphinx4ModelMetadata metadata, boolean heavy) {
        ValidationResult base = validateDirectory(modelDirectory, managedRoot);
        if (!base.validDirectory()) {
            return base;
        }
        if (metadata == null) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Sphinx4 model metadata is missing.", base.fingerprint(), null);
        }
        if (metadata.schemaVersion() != 1) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Unsupported Sphinx4 model metadata version.", base.fingerprint(), metadata);
        }
        if (!safeModelName(metadata.id())) {
            return new ValidationResult(Sphinx4ValidationStatus.UNSAFE, "Sphinx4 model metadata has an unsafe model id.", base.fingerprint(), metadata);
        }
        if (metadata.sampleRateHz() <= 0) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Sphinx4 model sample rate is missing.", base.fingerprint(), metadata);
        }
        List<String> requiredPaths = metadata.requiredFiles();
        for (String requiredPath : requiredPaths) {
            Path resolved = safeRelativePath(modelDirectory, requiredPath);
            if (resolved == null || !Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Sphinx4 model is missing required file %s.".formatted(requiredPath), fingerprint(modelDirectory, metadata), metadata);
            }
        }
        Path acoustic = safeRelativePath(modelDirectory, metadata.acousticModelPath());
        Path dictionary = safeRelativePath(modelDirectory, metadata.dictionaryPath());
        Path languageModel = safeRelativePath(modelDirectory, metadata.languageModelPath());
        if (acoustic == null || dictionary == null || languageModel == null) {
            return new ValidationResult(Sphinx4ValidationStatus.UNSAFE, "Sphinx4 model metadata contains unsafe paths.", fingerprint(modelDirectory, metadata), metadata);
        }
        if (!Files.isDirectory(acoustic, LinkOption.NOFOLLOW_LINKS)) {
            return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Sphinx4 acoustic model directory is missing.", fingerprint(modelDirectory, metadata), metadata);
        }
        if (!Files.isRegularFile(dictionary, LinkOption.NOFOLLOW_LINKS)) {
            return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Sphinx4 pronunciation dictionary is missing.", fingerprint(modelDirectory, metadata), metadata);
        }
        if (!Files.isRegularFile(languageModel, LinkOption.NOFOLLOW_LINKS)) {
            return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Sphinx4 language model is missing.", fingerprint(modelDirectory, metadata), metadata);
        }
        String fingerprint = fingerprint(modelDirectory, metadata);
        if (!heavy) {
            return new ValidationResult(Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED, "Model layout looks plausible but needs Sphinx4 validation before recording.", fingerprint, metadata);
        }
        CacheKey cacheKey = new CacheKey(fingerprint, RUNTIME_VERSION);
        return heavyValidationCache.computeIfAbsent(cacheKey, ignored -> constructRecognizer(acoustic, dictionary, languageModel, fingerprint, metadata));
    }

    public Path safeRelativePath(Path modelDirectory, String relativePath) {
        String value = StringUtils.trimToEmpty(relativePath).replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("\0")) {
            return null;
        }
        if (value.contains("//")) {
            return null;
        }
        Path root = modelDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(value).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private ValidationResult validate(Path modelDirectory, Path managedRoot, boolean heavy) {
        ValidationResult base = validateDirectory(modelDirectory, managedRoot);
        if (!base.validDirectory()) {
            return base;
        }
        Optional<Sphinx4ModelMetadata> metadata = readMetadata(modelDirectory);
        if (metadata.isPresent()) {
            return validateRecipe(modelDirectory, managedRoot, metadata.orElseThrow(), heavy);
        }
        InferredModel inferred = infer(modelDirectory);
        if (inferred == null) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Folder does not contain a complete Sphinx4 model layout.", base.fingerprint(), null);
        }
        if (inferred.sampleRateHz() <= 0) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Sphinx4 model sample rate could not be inferred safely.", base.fingerprint(), null);
        }
        return new ValidationResult(Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED, "Model layout looks plausible but metadata must be normalized before selection.", base.fingerprint(), inferred.toMetadata(modelDirectory));
    }

    public Optional<Sphinx4ModelMetadata> inferMetadata(Path modelDirectory, String id, String label) {
        InferredModel inferred = infer(modelDirectory);
        if (inferred == null || inferred.sampleRateHz() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Sphinx4ModelMetadata(
                1,
                id,
                label,
                "Custom",
                List.of(),
                inferred.acousticPath(),
                inferred.dictionaryPath(),
                inferred.languageModelPath(),
                inferred.sampleRateHz(),
                inferred.requiredFiles(),
                id,
                1,
                false
        ));
    }

    private ValidationResult validateDirectory(Path modelDirectory, Path managedRoot) {
        if (modelDirectory == null) {
            return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Model folder is missing.", "missing", null);
        }
        try {
            Path normalized = modelDirectory.toAbsolutePath().normalize();
            Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
            if (!normalized.startsWith(normalizedRoot)) {
                return new ValidationResult(Sphinx4ValidationStatus.UNSAFE, "Model folder is outside the managed Sphinx4 directory.", "unsafe", null);
            }
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(Sphinx4ValidationStatus.MISSING, "Model folder is missing.", "missing", null);
            }
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Model path is not a folder.", fingerprint(normalized, null), null);
            }
            if (containsUnsafeSymlink(normalized, normalizedRoot)) {
                return new ValidationResult(Sphinx4ValidationStatus.UNSAFE, "Model contains a symlink outside the managed Sphinx4 directory.", fingerprint(normalized, null), null);
            }
            return new ValidationResult(Sphinx4ValidationStatus.VALIDATING, "Structurally safe.", fingerprint(normalized, null), null);
        } catch (Exception e) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, StringUtils.defaultIfBlank(e.getMessage(), "Could not validate model."), "error", null);
        }
    }

    private ValidationResult constructRecognizer(Path acoustic, Path dictionary, Path languageModel, String fingerprint, Sphinx4ModelMetadata metadata) {
        try (Sphinx4RecognizerAdapter.RecognizerSession ignored = recognizerAdapter.create(configuration(acoustic, dictionary, languageModel, metadata.sampleRateHz()))) {
            return new ValidationResult(Sphinx4ValidationStatus.VALID, "Ready", fingerprint, metadata);
        } catch (Exception | LinkageError e) {
            return new ValidationResult(Sphinx4ValidationStatus.INVALID, "Sphinx4 runtime could not load this model.", fingerprint, metadata);
        }
    }

    public Configuration configuration(Path acoustic, Path dictionary, Path languageModel, int sampleRateHz) {
        Configuration configuration = new Configuration();
        configuration.setAcousticModelPath(acoustic.toUri().toString());
        configuration.setDictionaryPath(dictionary.toUri().toString());
        configuration.setLanguageModelPath(languageModel.toUri().toString());
        configuration.setSampleRate(sampleRateHz);
        return configuration;
    }

    private InferredModel infer(Path modelDirectory) {
        try (Stream<Path> stream = Files.walk(modelDirectory, 8)) {
            List<Path> paths = stream.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList();
            List<Path> acousticMarkers = paths.stream()
                    .filter(path -> "mdef".equals(path.getFileName().toString()))
                    .filter(path -> Files.isRegularFile(path.getParent().resolve("means"), LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(path.getParent().resolve("variances"), LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(path.getParent().resolve("transition_matrices"), LinkOption.NOFOLLOW_LINKS))
                    .map(Path::getParent)
                    .distinct()
                    .toList();
            List<Path> dictionaries = paths.stream()
                    .filter(path -> path.getFileName().toString().matches("(?i).+\\.(dic|dict)"))
                    .toList();
            List<Path> languageModels = paths.stream()
                    .filter(path -> path.getFileName().toString().matches("(?i).+\\.(lm|dmp|bin)") || path.getFileName().toString().matches("(?i).+\\.lm\\.bin"))
                    .toList();
            if (acousticMarkers.size() != 1 || dictionaries.size() != 1 || languageModels.size() != 1) {
                return null;
            }
            Path acoustic = acousticMarkers.getFirst();
            int sampleRate = inferSampleRate(acoustic.resolve("feat.params"));
            String acousticPath = relative(modelDirectory, acoustic);
            String dictionaryPath = relative(modelDirectory, dictionaries.getFirst());
            String languageModelPath = relative(modelDirectory, languageModels.getFirst());
            return new InferredModel(acousticPath, dictionaryPath, languageModelPath, sampleRate, List.of(
                    "%s/mdef".formatted(acousticPath),
                    "%s/means".formatted(acousticPath),
                    "%s/variances".formatted(acousticPath),
                    "%s/transition_matrices".formatted(acousticPath),
                    dictionaryPath,
                    languageModelPath
            ));
        } catch (Exception e) {
            return null;
        }
    }

    private int inferSampleRate(Path featParams) {
        if (!Files.isRegularFile(featParams, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try {
            String content = Files.readString(featParams);
            Matcher matcher = Pattern.compile("(?m)^\\s*-samprate\\s+(\\d+(?:\\.\\d+)?)").matcher(content);
            if (matcher.find()) {
                return Math.round(Float.parseFloat(matcher.group(1)));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private String relative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
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

    private String fingerprint(Path directory, Sphinx4ModelMetadata metadata) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(directory.toAbsolutePath().normalize().toString().getBytes(UTF_8));
            if (metadata != null) {
                digest.update(OBJECT_MAPPER.writeValueAsBytes(metadata));
                for (String requiredFile : metadata.requiredFiles()) {
                    Path path = safeRelativePath(directory, requiredFile);
                    if (path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        digest.update(requiredFile.getBytes(UTF_8));
                        digest.update(Long.toString(attrs.size()).getBytes(UTF_8));
                        digest.update(Long.toString(attrs.lastModifiedTime().toMillis()).getBytes(UTF_8));
                    }
                }
            } else {
                BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                digest.update(Long.toString(attrs.lastModifiedTime().toMillis()).getBytes(UTF_8));
                digest.update(Long.toString(attrs.size()).getBytes(UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "unknown-%d".formatted(System.currentTimeMillis());
        }
    }

    private record CacheKey(String fingerprint, String runtimeVersion) {
    }

    private record InferredModel(String acousticPath, String dictionaryPath, String languageModelPath, int sampleRateHz, List<String> requiredFiles) {
        Sphinx4ModelMetadata toMetadata(Path root) {
            String label = root.getFileName() == null ? "Sphinx4 Model" : root.getFileName().toString();
            return new Sphinx4ModelMetadata(1, label, label, "Custom", List.of(), acousticPath, dictionaryPath, languageModelPath, sampleRateHz, requiredFiles, label, 1, false);
        }
    }

    public record ValidationResult(Sphinx4ValidationStatus status, String message, String fingerprint, Sphinx4ModelMetadata metadata) {
        public boolean validDirectory() {
            return status == Sphinx4ValidationStatus.VALIDATING || status == Sphinx4ValidationStatus.VALID || status == Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED;
        }
    }
}
