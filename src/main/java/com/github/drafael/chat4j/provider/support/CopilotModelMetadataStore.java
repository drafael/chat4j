package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.persistence.CacheRootHandle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.stream.StreamSupport;
import lombok.NonNull;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

public class CopilotModelMetadataStore {

    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (StringUtils.isBlank(modelId)) {
            return emptyList();
        }

        loadIfNecessary();
        Map<String, List<String>> supportedEndpointsByModel = supportedEndpointsByBaseUrl.get(normalizeBaseUrl(baseUrl));
        if (supportedEndpointsByModel == null) {
            return emptyList();
        }

        return supportedEndpointsByModel.getOrDefault(modelId.trim(), emptyList());
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
        Map<String, List<String>> mergedSupportedEndpoints = new LinkedHashMap<>(
                supportedEndpointsByBaseUrl.getOrDefault(normalizedBaseUrl, emptyMap())
        );

        if (models != null) {
            models.stream()
                    .filter(model -> StringUtils.isNotBlank(model.modelId()))
                    .collect(toMap(
                            model -> model.modelId().trim(),
                            model -> sanitizeEndpoints(model.supportedEndpoints()),
                            (left, right) -> right,
                            LinkedHashMap::new
                    ))
                    .forEach((modelId, supportedEndpoints) -> {
                        if (!supportedEndpoints.isEmpty()) {
                            mergedSupportedEndpoints.put(modelId, supportedEndpoints);
                        }
                    });
        }

        if (mergedSupportedEndpoints.isEmpty()) {
            return;
        }

        supportedEndpointsByBaseUrl.put(normalizedBaseUrl, Map.copyOf(mergedSupportedEndpoints));
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
                JsonNode root = JSON.readTree(readBoundedJson(cacheFile.get()));
                JsonNode catalogs = root.path("catalogsByBaseUrl");
                if (catalogs.isObject()) {
                    catalogs.fields().forEachRemaining(entry -> {
                        String normalizedBaseUrl = normalizeBaseUrl(entry.getKey());
                        supportedEndpointsByBaseUrl.put(normalizedBaseUrl, readCatalog(entry.getValue()));
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

    private Map<String, List<String>> readCatalog(JsonNode catalogNode) {
        JsonNode models = catalogNode.path("models");
        if (!models.isObject()) {
            return emptyMap();
        }

        Map<String, List<String>> supportedEndpointsByModel = new LinkedHashMap<>();
        models.fields().forEachRemaining(entry -> {
            if (StringUtils.isBlank(entry.getKey())) {
                return;
            }

            supportedEndpointsByModel.put(entry.getKey().trim(), readEndpoints(entry.getValue()));
        });
        return Map.copyOf(supportedEndpointsByModel);
    }

    private List<String> readEndpoints(JsonNode endpointsNode) {
        if (!endpointsNode.isArray()) {
            return emptyList();
        }

        return sanitizeEndpoints(StreamSupport.stream(((ArrayNode) endpointsNode).spliterator(), false)
                .map(endpoint -> endpoint.asText(""))
                .toList());
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
            ObjectNode root = JSON.createObjectNode();
            ObjectNode catalogs = root.putObject("catalogsByBaseUrl");
            supportedEndpointsByBaseUrl.forEach((baseUrl, models) -> {
                ObjectNode catalogNode = catalogs.putObject(baseUrl);
                ObjectNode modelsNode = catalogNode.putObject("models");
                models.forEach((modelId, supportedEndpoints) -> {
                    ArrayNode endpointsNode = modelsNode.putArray(modelId);
                    sanitizeEndpoints(supportedEndpoints).forEach(endpointsNode::add);
                });
            });
            byte[] payload = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
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
