package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.persistence.StoragePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

public class CopilotModelMetadataStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_COPILOT_BASE_URL = "https://api.githubcopilot.com";
    private static final String STORE_FILE_NAME = "github-copilot-model-metadata.json";

    private final Path storeFile;
    private final Object ioLock = new Object();
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Map<String, List<String>>> supportedEndpointsByBaseUrl = new ConcurrentHashMap<>();

    public CopilotModelMetadataStore() {
        this(StoragePaths.defaultPaths().modelsCacheDirectory());
    }

    public CopilotModelMetadataStore(Path cacheDirectory) {
        this.storeFile = cacheDirectory.resolve(STORE_FILE_NAME);
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

    public void update(String baseUrl, List<ModelMetadata> models) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

        loadIfNecessary();

        synchronized (ioLock) {
            Map<String, List<String>> mergedSupportedEndpoints = new LinkedHashMap<>(
                    supportedEndpointsByBaseUrl.getOrDefault(normalizedBaseUrl, emptyMapCopy())
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
    }

    private void loadIfNecessary() {
        if (loaded.get()) {
            return;
        }

        synchronized (ioLock) {
            if (loaded.get()) {
                return;
            }

            if (!Files.exists(storeFile)) {
                loaded.set(true);
                return;
            }

            try {
                JsonNode root = JSON.readTree(Files.readString(storeFile, StandardCharsets.UTF_8));
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
                loaded.set(true);
            }
        }
    }

    private Map<String, List<String>> readCatalog(JsonNode catalogNode) {
        JsonNode models = catalogNode.path("models");
        if (!models.isObject()) {
            return emptyMapCopy();
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
        try {
            Files.createDirectories(storeFile.getParent());
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
            Files.writeString(
                    storeFile,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignored) {
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

    private Map<String, List<String>> emptyMapCopy() {
        return emptyMap();
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
