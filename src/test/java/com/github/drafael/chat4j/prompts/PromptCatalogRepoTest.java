package com.github.drafael.chat4j.prompts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogRepoTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Load returns built-ins when no prompt catalog file exists")
    void load_whenFileAbsent_returnsBuiltIns() {
        var subject = new PromptCatalogRepo(promptsFile());

        assertThat(subject.load()).extracting(PromptTemplate::id)
                .containsExactlyElementsOf(BuiltInPromptCatalog.prompts().stream().map(PromptTemplate::id).toList());
    }

    @Test
    @DisplayName("A blank prompt catalog file returns built-ins")
    void load_whenFileBlank_returnsBuiltIns() throws Exception {
        Files.writeString(promptsFile(), "  \n", StandardCharsets.UTF_8);
        var subject = new PromptCatalogRepo(promptsFile());

        PromptCatalogRepo.PromptCatalogLoadResult result = subject.loadResult();

        assertThat(result.failed()).isFalse();
        assertThat(result.prompts()).containsExactlyElementsOf(BuiltInPromptCatalog.prompts());
    }

    @Test
    @DisplayName("Saved prompt catalog round trips through the JSON file")
    void saveAndLoad_whenCatalogIsValid_roundTrips() throws Exception {
        var subject = new PromptCatalogRepo(promptsFile());
        List<PromptTemplate> prompts = List.of(prompt("custom", "Café résumé"));

        subject.save(prompts);

        assertThat(subject.load()).containsExactlyElementsOf(prompts);
        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8))
                .contains("\n", "[ {", "Café résumé");
    }

    @Test
    @DisplayName("Load falls back to built-ins when saved JSON is malformed")
    void load_whenJsonInvalid_returnsBuiltIns() throws Exception {
        Files.writeString(promptsFile(), "not json", StandardCharsets.UTF_8);
        var subject = new PromptCatalogRepo(promptsFile());

        PromptCatalogRepo.PromptCatalogLoadResult result = subject.loadResult();

        assertThat(result.failed()).isTrue();
        assertThat(result.prompts()).extracting(PromptTemplate::id)
                .containsExactlyElementsOf(BuiltInPromptCatalog.prompts().stream().map(PromptTemplate::id).toList());
    }

    @Test
    @DisplayName("Load falls back to built-ins when saved prompt definitions are invalid")
    void load_whenPromptDefinitionInvalid_returnsBuiltIns() throws Exception {
        Files.writeString(
                promptsFile(),
                "[{\"id\":\"INVALID\",\"title\":\"Invalid\",\"prompt\":\"Text\",\"model\":\"default\",\"variables\":[]}]",
                StandardCharsets.UTF_8
        );
        var subject = new PromptCatalogRepo(promptsFile());

        PromptCatalogRepo.PromptCatalogLoadResult result = subject.loadResult();

        assertThat(result.failed()).isTrue();
        assertThat(result.prompts()).containsExactlyElementsOf(BuiltInPromptCatalog.prompts());
    }

    @Test
    @DisplayName("An unreadable prompt path falls back to built-ins")
    void load_whenPromptPathIsDirectory_returnsBuiltIns() throws Exception {
        Files.createDirectory(promptsFile());
        var subject = new PromptCatalogRepo(promptsFile());

        PromptCatalogRepo.PromptCatalogLoadResult result = subject.loadResult();

        assertThat(result.failed()).isTrue();
        assertThat(result.prompts()).containsExactlyElementsOf(BuiltInPromptCatalog.prompts());
    }

    @Test
    @DisplayName("Invalid prompts are rejected before the existing file is replaced")
    void save_whenPromptDefinitionInvalid_preservesExistingFile() throws Exception {
        Files.writeString(promptsFile(), "previous", StandardCharsets.UTF_8);
        var subject = new PromptCatalogRepo(promptsFile());
        PromptTemplate invalidPrompt = prompt("INVALID", "Invalid");

        assertThatThrownBy(() -> subject.save(List.of(invalidPrompt)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo("previous");
    }

    @Test
    @DisplayName("Legacy prompt settings are ignored and left untouched")
    void loadAndSave_whenLegacySettingExists_ignoresLegacyValue() {
        Path settingsFile = tempDir.resolve("chat4j.properties");
        var settingsRepo = new SettingsRepository(settingsFile);
        settingsRepo.put("chat4j.prompts.catalog", "not json");
        var subject = new PromptCatalogRepo(promptsFile());
        List<PromptTemplate> prompts = List.of(prompt("custom", "Custom"));

        assertThat(subject.load()).containsExactlyElementsOf(BuiltInPromptCatalog.prompts());
        subject.save(prompts);

        assertThat(settingsRepo.get("chat4j.prompts.catalog")).contains("not json");
        assertThat(subject.load()).containsExactlyElementsOf(prompts);
    }

    private PromptTemplate prompt(String id, String title) {
        return new PromptTemplate(
                id,
                title,
                "Do @{{text}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        );
    }

    private Path promptsFile() {
        return tempDir.resolve("prompts.json");
    }
}
