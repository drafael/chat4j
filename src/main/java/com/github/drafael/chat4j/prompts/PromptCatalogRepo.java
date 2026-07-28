package com.github.drafael.chat4j.prompts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class PromptCatalogRepo {

    private static final TypeReference<List<PromptTemplate>> PROMPT_LIST_TYPE = new TypeReference<>() {
    };

    private final PromptCatalogStore promptCatalogStore;
    private final ObjectMapper objectMapper;

    public PromptCatalogRepo(@NonNull Path promptsFile) {
        this.promptCatalogStore = new PromptCatalogStore(promptsFile);
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<PromptTemplate> load() {
        return loadResult().prompts();
    }

    public PromptCatalogLoadResult loadResult() {
        try {
            String json = promptCatalogStore.loadCatalogJson().orElse(null);
            if (StringUtils.isBlank(json)) {
                return PromptCatalogLoadResult.success(BuiltInPromptCatalog.prompts());
            }

            List<PromptTemplate> prompts = objectMapper.readValue(json, PROMPT_LIST_TYPE);
            PromptCatalogValidator.validateOrThrow(prompts);
            return PromptCatalogLoadResult.success(prompts);
        } catch (Exception e) {
            log.warn("Failed to load prompt catalog; using built-ins: {}", StringUtils.substringBefore(e.toString(), "\n"));
            log.debug("Prompt catalog load failure", e);
            return PromptCatalogLoadResult.failure(BuiltInPromptCatalog.prompts());
        }
    }

    public void save(@NonNull List<PromptTemplate> prompts) {
        PromptCatalogValidator.validateOrThrow(prompts);
        try {
            promptCatalogStore.saveCatalogJson(objectMapper.writeValueAsString(prompts));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save prompt catalog", e);
        }
    }

    public record PromptCatalogLoadResult(List<PromptTemplate> prompts, boolean failed) {

        public PromptCatalogLoadResult {
            prompts = List.copyOf(prompts);
        }

        private static PromptCatalogLoadResult success(List<PromptTemplate> prompts) {
            return new PromptCatalogLoadResult(prompts, false);
        }

        private static PromptCatalogLoadResult failure(List<PromptTemplate> prompts) {
            return new PromptCatalogLoadResult(prompts, true);
        }
    }
}
