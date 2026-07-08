package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import edu.cmu.sphinx.api.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class Sphinx4ModelManagementServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Sphinx4 management uses managed root, safe local ids, and recognizer validation")
    void refreshAsync_whenImportedMetadataValid_marksModelReady() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        Path models = tempDir.resolve("models");
        Path root = models.resolve("sphinx4");
        Path model = root.resolve("custom folder");
        createModel(model, "custom-model");
        var validator = new Sphinx4ModelValidator(new FakeRecognizerAdapter());
        var subject = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp"), validator);

        subject.refreshAsync();
        waitUntil(() -> !subject.snapshot().installedModels().isEmpty());

        assertThat(subject.sphinx4Root()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(subject.snapshot().rows())
                .filteredOn(row -> row.id().startsWith("local-custom-folder"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.installed()).isTrue();
                    assertThat(row.selectable()).isTrue();
                    assertThat(row.actionStatus()).isEqualTo("Ready");
                });
        assertThat(subject.snapshot().selectedModel()).isNotNull();
        assertThat(subject.snapshot().readyToTranscribe()).isTrue();
        subject.close();
    }

    @Test
    @DisplayName("Saved selected Sphinx4 model is heavy-validated on refresh")
    void refreshAsync_whenSavedSelectionExists_validatesSelectedModel() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-saved.properties"));
        Path models = tempDir.resolve("models-saved");
        createModel(models.resolve("sphinx4").resolve("local-ready"), "local-ready");
        repo.put(SettingsKeys.sttModelIdKey(Sphinx4ModelManagementService.PROVIDER_ID), "local-ready");
        var subject = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp-saved"), new Sphinx4ModelValidator(new FakeRecognizerAdapter()));

        subject.refreshAsync();
        waitUntil(() -> subject.snapshot().selectedModel() != null && subject.snapshot().readyToTranscribe());

        assertThat(subject.snapshot().selectedModel().id()).isEqualTo("local-ready");
        assertThat(subject.snapshot().selectedModel().validationStatus()).isEqualTo(Sphinx4ValidationStatus.VALID);
        subject.close();
    }

    @Test
    @DisplayName("Sphinx4 catalog refresh keeps saved selected model ready")
    void refreshCatalogAsync_whenSavedSelectionExists_validatesSelectedModel() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-catalog-refresh.properties"));
        Path models = tempDir.resolve("models-catalog-refresh");
        createModel(models.resolve("sphinx4").resolve("local-ready"), "local-ready");
        repo.put(SettingsKeys.sttModelIdKey(Sphinx4ModelManagementService.PROVIDER_ID), "local-ready");
        var subject = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp-catalog-refresh"), new Sphinx4ModelValidator(new FakeRecognizerAdapter()));

        subject.refreshAsync();
        waitUntil(() -> subject.snapshot().selectedModel() != null && subject.snapshot().readyToTranscribe());
        subject.refreshCatalogAsync();
        waitUntil(() -> !subject.snapshot().operationInProgress() && subject.snapshot().selectedModel() != null);

        assertThat(subject.snapshot().readyToTranscribe()).isTrue();
        assertThat(subject.snapshot().selectedModel().id()).isEqualTo("local-ready");
        assertThat(subject.snapshot().selectedModel().validationStatus()).isEqualTo(Sphinx4ValidationStatus.VALID);
        subject.close();
    }

    @Test
    @DisplayName("Sphinx4 import preserves invalid import warning as completed operation status")
    void importAsync_whenFolderIsNotSelectable_preservesWarningStatus() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-import-warning.properties"));
        Path source = tempDir.resolve("not-a-model");
        Files.createDirectories(source);
        Files.writeString(source.resolve("README.txt"), "not a sphinx model");
        var subject = new Sphinx4ModelManagementService(repo, tempDir.resolve("models-import-warning"), tempDir.resolve("temp-import-warning"), new Sphinx4ModelValidator(new FakeRecognizerAdapter()));

        subject.importAsync(source);
        waitUntil(() -> !subject.snapshot().operationInProgress() && StringUtils.isNotBlank(subject.snapshot().operationStatus()));

        assertThat(subject.snapshot().operationStatus()).contains("Imported local-not-a-model, but it is not selectable");
        assertThat(subject.snapshot().readyToTranscribe()).isFalse();
        subject.close();
    }

    @Test
    @DisplayName("Sphinx4 can select an unverified installed model when another saved model exists")
    void selectModelAsync_whenDifferentSavedSelectionExists_validatesTargetBeforePersisting() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-switch.properties"));
        Path models = tempDir.resolve("models-switch");
        createModel(models.resolve("sphinx4").resolve("local-first"), "local-first");
        createModel(models.resolve("sphinx4").resolve("local-second"), "local-second");
        repo.put(SettingsKeys.sttModelIdKey(Sphinx4ModelManagementService.PROVIDER_ID), "local-first");
        var subject = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp-switch"), new Sphinx4ModelValidator(new FakeRecognizerAdapter()));

        subject.refreshAsync();
        waitUntil(() -> subject.snapshot().selectedModel() != null && subject.snapshot().readyToTranscribe());

        assertThat(subject.snapshot().installedModels())
                .filteredOn(model -> model.id().equals("local-second"))
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.validationStatus()).isEqualTo(Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED);
                    assertThat(model.selectable()).isTrue();
                });
        assertThat(subject.snapshot().rows())
                .filteredOn(row -> row.id().equals("local-second"))
                .singleElement()
                .satisfies(row -> assertThat(row.selectable()).isTrue());

        subject.selectModelAsync("local-second");
        waitUntil(() -> repo.get(SettingsKeys.sttModelIdKey(Sphinx4ModelManagementService.PROVIDER_ID), "").equals("local-second")
                && subject.snapshot().selectedModel() != null
                && subject.snapshot().selectedModel().id().equals("local-second")
                && subject.snapshot().selectedModel().ready());

        assertThat(repo.get(SettingsKeys.sttModelLabelKey(Sphinx4ModelManagementService.PROVIDER_ID), "")).isEqualTo("Ready Model");
        assertThat(subject.snapshot().selectedModel().validationStatus()).isEqualTo(Sphinx4ValidationStatus.VALID);
        subject.close();
    }

    @Test
    @DisplayName("Sphinx4 selection persists safe root and fingerprint")
    void selectModelAsync_whenModelReady_persistsSelectionIdentity() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-select.properties"));
        Path models = tempDir.resolve("models-select");
        createModel(models.resolve("sphinx4").resolve("ready-model"), "ready-model");
        var subject = new Sphinx4ModelManagementService(repo, models, tempDir.resolve("temp-select"), new Sphinx4ModelValidator(new FakeRecognizerAdapter()));
        subject.refreshAsync();
        waitUntil(() -> subject.snapshot().selectedModel() != null);

        String modelId = subject.snapshot().selectedModel().id();
        subject.selectModelAsync(modelId);
        waitUntil(() -> repo.get(SettingsKeys.sttModelIdKey(Sphinx4ModelManagementService.PROVIDER_ID), "").equals(modelId));

        assertThat(repo.get(SettingsKeys.sttModelLabelKey(Sphinx4ModelManagementService.PROVIDER_ID), "")).isEqualTo("Ready Model");
        assertThat(repo.get(SettingsKeys.STT_PREFIX + "sphinx4.model.root", "")).isEqualTo(subject.sphinx4Root().toString());
        assertThat(repo.get(SettingsKeys.STT_PREFIX + "sphinx4.model.fingerprint", "")).isNotBlank();
        subject.close();
    }

    private void createModel(Path model, String id) throws Exception {
        Files.createDirectories(model.resolve("acoustic"));
        Files.createDirectories(model.resolve("dict"));
        Files.createDirectories(model.resolve("lm"));
        Files.writeString(model.resolve("acoustic").resolve("mdef"), "mdef");
        Files.writeString(model.resolve("acoustic").resolve("means"), "means");
        Files.writeString(model.resolve("acoustic").resolve("variances"), "variances");
        Files.writeString(model.resolve("acoustic").resolve("transition_matrices"), "transition");
        Files.writeString(model.resolve("dict").resolve("model.dict"), "WORD W ER D");
        Files.writeString(model.resolve("lm").resolve("model.lm"), "lm");
        var metadata = new Sphinx4ModelMetadata(
                1,
                id,
                "Ready Model",
                "Custom",
                List.of(),
                "acoustic",
                "dict/model.dict",
                "lm/model.lm",
                16_000,
                List.of(
                        "acoustic/mdef",
                        "acoustic/means",
                        "acoustic/variances",
                        "acoustic/transition_matrices",
                        "dict/model.dict",
                        "lm/model.lm"
                ),
                id,
                1,
                false
        );
        new Sphinx4ModelValidator(new FakeRecognizerAdapter()).writeMetadata(model, metadata);
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    private static final class FakeRecognizerAdapter implements Sphinx4RecognizerAdapter {
        @Override
        public RecognizerSession create(Configuration configuration) {
            return new RecognizerSession() {
                @Override
                public void startRecognition(java.io.InputStream inputStream) {
                }

                @Override
                public String nextHypothesis() {
                    return null;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }
}
