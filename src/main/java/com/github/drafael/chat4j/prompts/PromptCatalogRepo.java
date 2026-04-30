package com.github.drafael.chat4j.prompts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@Slf4j
public class PromptCatalogRepo {

    private static final TypeReference<List<PromptTemplate>> PROMPT_LIST_TYPE = new TypeReference<>() {
    };

    private final SettingsRepo settingsRepo;
    private final ObjectMapper objectMapper;

    public PromptCatalogRepo(@NonNull SettingsRepo settingsRepo) {
        this(settingsRepo, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    PromptCatalogRepo(@NonNull SettingsRepo settingsRepo, @NonNull ObjectMapper objectMapper) {
        this.settingsRepo = settingsRepo;
        this.objectMapper = objectMapper;
    }

    public List<PromptTemplate> load() {
        try {
            String json = settingsRepo.get(SettingsKeys.PROMPT_CATALOG).orElse(null);
            if (StringUtils.isBlank(json)) {
                return BuiltInPromptCatalog.prompts();
            }

            List<PromptTemplate> prompts = objectMapper.readValue(json, PROMPT_LIST_TYPE);
            PromptCatalogValidator.validateOrThrow(prompts);
            return List.copyOf(prompts);
        } catch (Exception e) {
            log.warn("Failed to load prompt catalog; using built-ins: {}", StringUtils.substringBefore(e.toString(), "\n"));
            log.debug("Prompt catalog load failure", e);
            return BuiltInPromptCatalog.prompts();
        }
    }

    public void save(@NonNull List<PromptTemplate> prompts) {
        PromptCatalogValidator.validateOrThrow(prompts);
        try {
            settingsRepo.put(SettingsKeys.PROMPT_CATALOG, objectMapper.writeValueAsString(prompts));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save prompt catalog", e);
        }
    }

    public void resetToBuiltIns() {
        save(BuiltInPromptCatalog.prompts());
    }
}
