package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.persistence.CacheRootHandle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

public class CopilotModelMetadataStore {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final String DEFAULT_COPILOT_BASE_URL = "https://api.githubcopilot.com";
    private static final String STORE_FILE_NAME = "github-copilot-model-metadata.json";
    private static final int MAX_STORE_BYTES = 8 * 1024 * 1024;
    private final CacheRootHandle cacheRoot;
    private final Object ioLock = new Object();
    private volatile boolean loaded;
    private long generation;
    private final ConcurrentHashMap<String, Map<String, List<String>>> supportedEndpointsByBaseUrl = new ConcurrentHashMap<>();

    public CopilotModelMetadataStore(@NonNull CacheRootHandle cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public CopilotModelMetadataStore(@NonNull Path cacheDirectory) {
        this(CacheRootHandle.of(cacheDirectory));
    }

    public void prime() {
        loadIfNecessary();
    }

    public List<String> supportedEndpoints(String baseUrl, String modelId) {
        return supportedEndpointsEvidence(baseUrl, modelId).orElse(emptyList());
    }

    public Optional<List<String>> supportedEndpointsEvidence(String baseUrl, String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return Optional.empty();
        }

        loadIfNecessary();
        Map<String, List<String>> supportedEndpointsByModel = supportedEndpointsByBaseUrl.get(normalizeBaseUrl(baseUrl));
        return supportedEndpointsByModel == null
                ? Optional.empty()
                : Optional.ofNullable(supportedEndpointsByModel.get(modelId.trim()));
    }

    public boolean clear() {
        synchronized (ioLock) {
            generation++;
            supportedEndpointsByBaseUrl.clear();
            loaded = true;

            Optional<Path> cacheFile = cacheFile();
            if (cacheFile.isEmpty()) {
                return true;
            }
            try {
                Path file = cacheFile.get();
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !cacheRoot.isSafeRegularFile(file)) {
                    return false;
                }
                Files.deleteIfExists(file);
                return true;
            } catch (IOException | SecurityException e) {
                return false;
            }
        }
    }

    public long currentGeneration() {
        synchronized (ioLock) {
            return generation;
        }
    }

    public boolean updateIfGenerationCurrent(long expectedGeneration, String baseUrl, List<ModelMetadata> models) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        loadIfNecessary();

        synchronized (ioLock) {
            if (generation != expectedGeneration) {
                return false;
            }
            updateUnderLock(normalizedBaseUrl, models);
            return true;
        }
    }

    private void updateUnderLock(String normalizedBaseUrl, List<ModelMetadata> models) {
        if (models == null) {
            return;
        }

        Map<String, List<String>> supportedEndpoints = models.stream()
                .filter(model -> StringUtils.isNotBlank(model.modelId()))
                .collect(toMap(
                        model -> model.modelId().trim(),
                        model -> sanitizeEndpoints(model.supportedEndpoints()),
                        (left, right) -> left.equals(right) ? left : emptyList(),
                        LinkedHashMap::new
                ));
        if (supportedEndpoints.isEmpty()) {
            supportedEndpointsByBaseUrl.remove(normalizedBaseUrl);
        } else {
            supportedEndpointsByBaseUrl.put(normalizedBaseUrl, Map.copyOf(supportedEndpoints));
        }
        persistUnderLock();
    }

    private void loadIfNecessary() {
        if (loaded) {
            return;
        }

        synchronized (ioLock) {
            if (loaded) {
                return;
            }

            try {
                Optional<Path> cacheFile = cacheFile();
                if (cacheFile.isEmpty() || Files.notExists(cacheFile.get(), LinkOption.NOFOLLOW_LINKS)
                        || !cacheRoot.isSafeRegularFile(cacheFile.get())) {
                    return;
                }
                MetadataFile root = JSON.read(readBoundedJson(cacheFile.get()), MetadataFile.class);
                if (root.catalogsByBaseUrl() != null) {
                    root.catalogsByBaseUrl().forEach((baseUrl, catalog) -> {
                        if (!isValidPersistedBaseUrl(baseUrl)) {
                            throw new IllegalArgumentException("Copilot metadata base URL is malformed");
                        }
                        supportedEndpointsByBaseUrl.put(normalizeBaseUrl(baseUrl), readCatalog(catalog));
                    });
                }
            } catch (Exception e) {
                supportedEndpointsByBaseUrl.clear();
            } finally {
                loaded = true;
            }
        }
    }

    private Optional<Path> cacheFile() {
        return cacheRoot.directChild(STORE_FILE_NAME);
    }

    private String readBoundedJson(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_STORE_BYTES + 1);
            if (bytes.length > MAX_STORE_BYTES) {
                throw new IOException("Copilot metadata cache exceeds 8 MiB");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Copilot metadata cache is not valid UTF-8", e);
        }
    }

    private Map<String, List<String>> readCatalog(Catalog catalog) {
        if (catalog == null || catalog.models() == null) {
            return emptyMap();
        }

        Map<String, List<String>> supportedEndpointsByModel = new LinkedHashMap<>();
        catalog.models().forEach((modelId, endpoints) -> {
            if (StringUtils.isNotBlank(modelId)) {
                supportedEndpointsByModel.put(modelId.trim(), readEndpoints(endpoints));
            }
        });
        return Map.copyOf(supportedEndpointsByModel);
    }

    private List<String> readEndpoints(List<Object> endpoints) {
        if (endpoints == null || endpoints.stream().anyMatch(endpoint -> !(endpoint instanceof String text) || StringUtils.isBlank(text))) {
            throw new IllegalArgumentException("Copilot endpoint metadata is malformed");
        }
        return sanitizeEndpoints(endpoints.stream().map(String.class::cast).toList());
    }

    private void persistUnderLock() {
        Optional<Path> cacheFile = cacheFile();
        if (cacheFile.isEmpty()) {
            return;
        }
        try {
            Path file = cacheFile.get();
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !cacheRoot.isSafeRegularFile(file)) {
                return;
            }
            Map<String, Catalog> catalogs = new LinkedHashMap<>();
            supportedEndpointsByBaseUrl.forEach((baseUrl, models) -> {
                Map<String, List<Object>> storedModels = new LinkedHashMap<>();
                models.forEach((modelId, endpoints) -> storedModels.put(
                        modelId,
                        sanitizeEndpoints(endpoints).stream().map(endpoint -> (Object) endpoint).toList()
                ));
                catalogs.put(baseUrl, new Catalog(storedModels));
            });
            byte[] payload = JSON.writePrettyBytes(new MetadataFile(catalogs));
            if (payload.length > MAX_STORE_BYTES) {
                return;
            }
            try (OutputStream output = Files.newOutputStream(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                output.write(payload);
            }
        } catch (IOException | SecurityException ignored) {
            // Metadata cache write failure is non-critical.
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        return BaseUrlNormalizer.normalize(baseUrl, DEFAULT_COPILOT_BASE_URL);
    }

    private boolean isValidPersistedBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.isNotBlank(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<String> sanitizeEndpoints(List<String> supportedEndpoints) {
        if (ObjectUtils.isEmpty(supportedEndpoints)) {
            return emptyList();
        }

        return supportedEndpoints.stream()
                .map(StringUtils::trimToNull)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MetadataFile(@JsonProperty("catalogsByBaseUrl") Map<String, Catalog> catalogsByBaseUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(Map<String, List<Object>> models) {
    }

    public record ModelMetadata(String modelId, List<String> supportedEndpoints) {

        public ModelMetadata {
            supportedEndpoints = supportedEndpoints == null
                    ? emptyList()
                    : supportedEndpoints.stream()
                            .map(StringUtils::trimToNull)
                            .filter(StringUtils::isNotBlank)
                            .distinct()
                            .toList();
        }
    }
}
