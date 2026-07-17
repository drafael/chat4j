package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechToTextCatalogStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Saved STT catalog uses immutable snapshot references")
    void saveModels_whenProviderKnown_writesSnapshotReference() throws Exception {
        var repo = repo("save.properties");
        var subject = new SpeechToTextCatalogStore(repo);

        subject.saveModels(ElevenLabsSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2")));

        assertThat(repo.get("chat4j.stt.catalog.elevenlabs.modelsFile")).hasValueSatisfying(reference ->
                assertThat(reference).startsWith("stt-elevenlabs-models-").endsWith(".json"));
        assertThat(repo.get("chat4j.stt.catalog.elevenlabs.models")).isEmpty();
        assertThat(repo.get("chat4j.stt.catalog.elevenlabs.updatedAt")).isPresent();
        assertThat(subject.cachedModels(ElevenLabsSpeechToTextProvider.ID))
                .extracting(SpeechToTextCatalogItem::id)
                .containsExactly("scribe_v2");
    }

    @Test
    @DisplayName("Malformed STT catalog falls back to empty cached models")
    void cachedModels_whenJsonMalformed_returnsEmptyList() {
        var repo = repo("malformed.properties");
        repo.put("chat4j.stt.catalog.deepgram.modelsFile", "not json");
        var subject = new SpeechToTextCatalogStore(repo);

        assertThat(subject.cachedModels(DeepgramSpeechToTextProvider.ID)).isEmpty();
    }

    @Test
    @DisplayName("STT stale timestamps keep existing 24-hour behavior")
    void stale_whenTimestampOlderThanWindow_returnsTrue() throws Exception {
        var repo = repo("stale.properties");
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", "2000-01-01T00:00:00Z");
        var subject = new SpeechToTextCatalogStore(repo);

        assertThat(subject.stale(DeepgramSpeechToTextProvider.ID)).isTrue();

        subject.saveModels(DeepgramSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("nova-3", "Nova 3")));

        assertThat(subject.stale(DeepgramSpeechToTextProvider.ID)).isFalse();
    }

    @Test
    @DisplayName("The exact 24-hour STT boundary remains valid and becomes stale one nanosecond later")
    void stale_whenAtExactRefreshBoundary_preservesBoundaryBehavior() throws Exception {
        var repo = repo("boundary.properties");
        Instant updatedAt = Instant.parse("2026-07-15T00:00:00Z");
        new SpeechToTextCatalogStore(repo).saveModels(
                DeepgramSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("nova-3", "Nova 3"))
        );
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", updatedAt.toString());
        CatalogSnapshotStore snapshots = CatalogSnapshotStore.forSettings(repo);
        snapshots.cleanupOrphans();
        snapshots.preloadActiveCatalogs();
        var atBoundary = new SpeechToTextCatalogStore(
                snapshots,
                Clock.fixed(updatedAt.plusSeconds(24 * 60 * 60), ZoneOffset.UTC)
        );
        var afterBoundary = new SpeechToTextCatalogStore(
                snapshots,
                Clock.fixed(updatedAt.plusSeconds(24 * 60 * 60).plusNanos(1), ZoneOffset.UTC)
        );

        assertThat(atBoundary.stale(DeepgramSpeechToTextProvider.ID)).isFalse();
        assertThat(afterBoundary.stale(DeepgramSpeechToTextProvider.ID)).isTrue();
    }

    @Test
    @DisplayName("A recent timestamp is stale when its STT snapshot is malformed")
    void stale_whenSnapshotIsMalformed_returnsTrue() throws Exception {
        var repo = repo("malformed-snapshot.properties");
        repo.put("chat4j.stt.catalog.deepgram.modelsFile", "stt-deepgram-models-12345678123412341234123456789012.json");
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", Instant.now().toString());
        var cacheFile = tempDir.resolve("cache").resolve("stt-deepgram-models-12345678123412341234123456789012.json");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "not json");
        var subject = new SpeechToTextCatalogStore(repo);

        assertThat(subject.stale(DeepgramSpeechToTextProvider.ID)).isTrue();
    }

    @Test
    @DisplayName("Deepgram cached alias models are normalized out of the displayed catalog")
    void models_whenDeepgramCachedModelsContainAliases_showsBundledCanonicalModelsOnly() throws Exception {
        var repo = repo("deepgram-aliases.properties");
        var provider = new DeepgramSpeechToTextProvider((request, cancellationToken) -> new SttHttpResponse(200, null, new byte[0]));
        var subject = new SpeechToTextCatalogStore(repo);
        subject.saveModels(
                DeepgramSpeechToTextProvider.ID,
                List.of(
                        SpeechToTextCatalogItem.of("general", "general"),
                        SpeechToTextCatalogItem.of("finance", "finance"),
                        SpeechToTextCatalogItem.of("general-dQw4w9WgXcQ", "general-dQw4w9WgXcQ")
                )
        );

        var models = subject.models(provider, SpeechToTextCatalogItem.of("general", "general"));

        assertThat(models).extracting(SpeechToTextCatalogItem::id)
                .containsExactly("nova-3", "nova-3-general", "nova-2-general");
    }

    @Test
    @DisplayName("Cached STT catalogs retain a saved model that is unavailable offline")
    void mergeWithSelected_whenSavedModelAbsent_retainsSavedModel() {
        var subject = new SpeechToTextCatalogStore(repo("offline-merge.properties"));
        var selected = SpeechToTextCatalogItem.of("saved-model", "Saved Model");

        var models = subject.mergeWithSelected(
                List.of(SpeechToTextCatalogItem.of("cached-model", "Cached Model")),
                emptyList(),
                selected
        );

        assertThat(models).extracting(SpeechToTextCatalogItem::id)
                .containsExactly("cached-model", "saved-model");
    }

    @Test
    @DisplayName("Authoritative STT catalogs include provider fallback without restoring saved models")
    void authoritativeModels_whenSavedModelAbsent_omitsSavedModel() {
        var subject = new SpeechToTextCatalogStore(repo("authoritative-merge.properties"));
        var provider = new ElevenLabsSpeechToTextProvider(
                (request, cancellationToken) -> new SttHttpResponse(200, null, new byte[0])
        );

        var models = subject.authoritativeModels(
                provider,
                List.of(
                        SpeechToTextCatalogItem.of("account-model", "Account Model"),
                        SpeechToTextCatalogItem.of("account-model", "Duplicate Account Model")
                )
        );

        assertThat(models).extracting(SpeechToTextCatalogItem::id)
                .startsWith("account-model")
                .contains(provider.defaultModel().id())
                .doesNotContain("saved-model");
    }

    @Test
    @DisplayName("Invalidating STT catalog clears stale selected model")
    void invalidate_whenProviderHasSelectedModel_removesTimestampAndSelection() throws Exception {
        var repo = repo("invalidate.properties");
        var settings = SpeechToTextProviderSettingsFactory.forProvider(repo, DeepgramSpeechToTextProvider.ID);
        settings.saveModel(SpeechToTextCatalogItem.of("old-account-model", "Old Account Model"));
        repo.put("chat4j.stt.catalog.deepgram.modelsFile", "unsafe.json");
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", "2026-07-11T00:00:00Z");
        var subject = new SpeechToTextCatalogStore(repo);

        subject.invalidate(DeepgramSpeechToTextProvider.ID);

        assertThat(repo.get("chat4j.stt.catalog.deepgram.modelsFile")).isEmpty();
        assertThat(repo.get("chat4j.stt.catalog.deepgram.updatedAt")).isEmpty();
        assertThat(settings.selectedModelId()).isBlank();
    }

    @Test
    @DisplayName("Failed STT invalidation preserves both snapshot authority and selected model")
    void invalidate_whenSettingsCommitFails_preservesExistingAuthority() {
        var repo = new FailingBatchSettingsRepository(tempDir.resolve("failed-invalidation.properties"));
        var settings = SpeechToTextProviderSettingsFactory.forProvider(repo, DeepgramSpeechToTextProvider.ID);
        settings.saveModel(SpeechToTextCatalogItem.of("selected", "Selected"));
        repo.put("chat4j.stt.catalog.deepgram.modelsFile", "stt-deepgram-models-77777777777777777777777777777777.json");
        repo.put("chat4j.stt.catalog.deepgram.updatedAt", "2026-07-15T00:00:00Z");
        repo.failBatchUpdates = true;
        var subject = new SpeechToTextCatalogStore(repo);

        assertThatThrownBy(() -> subject.invalidate(DeepgramSpeechToTextProvider.ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalidate");
        assertThat(repo.get("chat4j.stt.catalog.deepgram.modelsFile")).isPresent();
        assertThat(repo.get("chat4j.stt.catalog.deepgram.updatedAt")).isPresent();
        assertThat(settings.selectedModelId()).isEqualTo("selected");
    }

    @Test
    @DisplayName("AssemblyAI catalog exposes only bundled official models")
    void models_whenAssemblyAiSelected_usesBundledOfficialModelsOnly() throws Exception {
        var repo = repo("assemblyai.properties");
        var subject = new SpeechToTextCatalogStore(repo);
        var provider = new AssemblyAiSpeechToTextProvider((request, cancellationToken) -> new SttHttpResponse(200, null, new byte[0]));
        subject.saveModels(AssemblyAiSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("stale-model", "Stale Model")));

        var models = subject.models(provider, SpeechToTextCatalogItem.of("selected-stale", "Selected Stale"));

        assertThat(models).extracting(SpeechToTextCatalogItem::id)
                .containsExactly("assemblyai-auto", "universal-3-5-pro", "universal-2");
    }

    private SettingsRepository repo(String fileName) {
        return new SettingsRepository(tempDir.resolve(fileName));
    }

    private static final class FailingBatchSettingsRepository extends SettingsRepository {
        private boolean failBatchUpdates;

        private FailingBatchSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void updateBatch(Consumer<BatchUpdate> updates) {
            if (failBatchUpdates) {
                throw new IllegalStateException("simulated settings failure");
            }
            super.updateBatch(updates);
        }
    }
}
