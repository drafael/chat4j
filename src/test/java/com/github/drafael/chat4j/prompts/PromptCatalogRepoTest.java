package com.github.drafael.chat4j.prompts;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCatalogRepoTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Load returns built-ins when no prompt catalog is saved")
    void load_whenSettingAbsent_returnsBuiltIns() {
        var subject = new PromptCatalogRepo(new SettingsRepo(tempDir.resolve("settings.properties")));

        assertThat(subject.load()).extracting(PromptTemplate::id)
                .containsExactlyElementsOf(BuiltInPromptCatalog.prompts().stream().map(PromptTemplate::id).toList());
    }

    @Test
    @DisplayName("Saved prompt catalog round trips through settings")
    void saveAndLoad_whenCatalogIsValid_roundTrips() {
        var subject = new PromptCatalogRepo(new SettingsRepo(tempDir.resolve("settings.properties")));
        List<PromptTemplate> prompts = List.of(new PromptTemplate(
                "custom",
                "Custom",
                "Do @{{text}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        ));

        subject.save(prompts);

        assertThat(subject.load()).containsExactlyElementsOf(prompts);
    }

    @Test
    @DisplayName("Load falls back to built-ins when saved JSON is invalid")
    void load_whenJsonInvalid_returnsBuiltIns() throws Exception {
        SettingsRepo settingsRepo = new SettingsRepo(tempDir.resolve("settings.properties"));
        settingsRepo.put(SettingsKeys.PROMPT_CATALOG, "not json");
        var subject = new PromptCatalogRepo(settingsRepo);

        assertThat(subject.load()).extracting(PromptTemplate::id)
                .containsExactlyElementsOf(BuiltInPromptCatalog.prompts().stream().map(PromptTemplate::id).toList());
    }

    @Test
    @DisplayName("Reset replaces custom prompts with built-ins")
    void resetToBuiltIns_whenCustomCatalogExists_replacesCatalog() {
        var subject = new PromptCatalogRepo(new SettingsRepo(tempDir.resolve("settings.properties")));
        subject.save(List.of(new PromptTemplate(
                "custom",
                "Custom",
                "Do @{{text}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        )));

        subject.resetToBuiltIns();

        assertThat(subject.load()).extracting(PromptTemplate::id)
                .containsExactlyElementsOf(BuiltInPromptCatalog.prompts().stream().map(PromptTemplate::id).toList());
    }
}
