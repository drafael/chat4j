package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.persistence.settings.SettingsStorageException;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogStore;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextToSpeechPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Changing the implicit System model persists the provider")
    void onModelSelected_whenSystemProviderImplicit_persistsSystemProvider() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-model-settings.properties"));
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        try {
            SwingUtilities.invokeAndWait(() -> selectCatalogItem(subject, "modelComboBox", "system-model-alt"));

            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).contains(SystemTextToSpeechProvider.ID);
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Changing the implicit System voice persists the provider")
    void onVoiceSelected_whenSystemProviderImplicit_persistsSystemProvider() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-voice-settings.properties"));
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        try {
            SwingUtilities.invokeAndWait(() -> selectCatalogItem(subject, "voiceComboBox", "system-voice-alt"));

            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).contains(SystemTextToSpeechProvider.ID);
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Provider selection save failure reverts controls and reports an error")
    void onProviderSelected_whenSaveFails_revertsProviderSelection() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-provider-save-failure.properties"));
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        repo.failPut = true;
        try {
            SwingUtilities.invokeAndWait(() -> {
                selectProviderOption(subject, TextToSpeechSettings.PROVIDER_OFF);

                assertThat(selectedProviderId(subject)).isEqualTo(SystemTextToSpeechProvider.ID);
                assertThat(subject.statusLabel().getText()).contains("Could not save Text to Speech provider selection.");
            });
            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).isEmpty();
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Model selection save failure reverts controls and reports an error")
    void onModelSelected_whenSaveFails_revertsModelSelection() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-model-save-failure.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, SystemTextToSpeechProvider.ID);
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        repo.failBatch = true;
        try {
            SwingUtilities.invokeAndWait(() -> {
                selectCatalogItem(subject, "modelComboBox", "system-model-alt");

                assertThat(selectedCatalogItemId(subject, "modelComboBox")).isEqualTo("system-model");
                assertThat(subject.statusLabel().getText()).contains("Could not save Text to Speech model selection.");
            });
            assertThat(repo.get("chat4j.tts.system.model.id")).isEmpty();
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Voice selection save failure reverts controls and reports an error")
    void onVoiceSelected_whenSaveFails_revertsVoiceSelection() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-voice-save-failure.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, SystemTextToSpeechProvider.ID);
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        repo.failBatch = true;
        try {
            SwingUtilities.invokeAndWait(() -> {
                selectCatalogItem(subject, "voiceComboBox", "system-voice-alt");

                assertThat(selectedCatalogItemId(subject, "voiceComboBox")).isEqualTo("system-voice");
                assertThat(subject.statusLabel().getText()).contains("Could not save Text to Speech voice selection.");
            });
            assertThat(repo.get("chat4j.tts.system.voice.id")).isEmpty();
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Implicit System provider save failure stops model persistence and reports an error")
    void onModelSelected_whenImplicitProviderSaveFails_doesNotSaveModelSelection() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-implicit-provider-save-failure.properties"));
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        repo.failPut = true;
        try {
            SwingUtilities.invokeAndWait(() -> {
                selectCatalogItem(subject, "modelComboBox", "system-model-alt");

                assertThat(selectedCatalogItemId(subject, "modelComboBox")).isEqualTo("system-model");
                assertThat(subject.statusLabel().getText()).contains("Could not save Text to Speech provider selection.");
            });
            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).isEmpty();
            assertThat(repo.get("chat4j.tts.system.model.id")).isEmpty();
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Implicit first voice save failure reports an error without selecting the unsaved voice")
    void refreshControlsFromSettings_whenImplicitFirstVoiceSaveFails_doesNotSelectUnsavedVoice() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-first-voice-save-failure.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, "limited-voice");
        repo.failBatch = true;

        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new LimitedVoiceProvider()))
        );
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertThat(selectedCatalogItemId(subject, "voiceComboBox")).isNull();
                assertThat(subject.statusLabel().getText()).contains("Could not save Text to Speech voice selection.");
            });
            assertThat(repo.get("chat4j.tts.limited-voice.voice.id")).isEmpty();
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Deepgram legacy cached model catalog is displayed as model families")
    void refreshControlsFromSettings_whenDeepgramCachedModelsContainVoiceIds_showsModelFamilies() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-deepgram-legacy-cache.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, DeepgramTextToSpeechProvider.ID);
        var cachedItems = List.of(
                new TextToSpeechCatalogItem("aura-2-thalia-en", "thalia", "clear"),
                new TextToSpeechCatalogItem("aura-2-zeus-en", "zeus", "deep")
        );
        new TextToSpeechCatalogStore(repo).saveCatalogs(DeepgramTextToSpeechProvider.ID, cachedItems, cachedItems);
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new DeepgramTextToSpeechProvider(request -> {
                    throw new AssertionError("Unavailable Deepgram provider should not refresh catalogs");
                }) {
                    @Override
                    public boolean available() {
                        return false;
                    }
                }))
        );
        try {
            List<String> modelIds = callOnEdt(() -> comboItemIds(catalogComboBox(subject, "modelComboBox")));
            List<String> voiceIds = callOnEdt(() -> comboItemIds(catalogComboBox(subject, "voiceComboBox")));

            assertThat(modelIds).containsExactly("aura-2");
            assertThat(voiceIds).containsExactly("aura-2-thalia-en", "aura-2-zeus-en");
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Pending token save failure uses original token field after rebuild")
    void savePendingChangesAsync_whenTokenFieldRebuiltBeforeFailure_usesOriginalError() throws Exception {
        var subject = createPanel(
                new SettingsRepository(tempDir.resolve("tts-token-rebuild.properties")),
                new TextToSpeechProviderRegistry(List.of())
        );
        ApiTokenFieldPanel originalField = mock(ApiTokenFieldPanel.class);
        ApiTokenFieldPanel replacementField = mock(ApiTokenFieldPanel.class);
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        when(originalField.dirty()).thenReturn(true);
        when(originalField.savePendingChangesAsync()).thenReturn(save);
        when(originalField.lastSaveError()).thenReturn("original token save failed");
        when(replacementField.lastSaveError()).thenReturn("replacement token save failed");
        try {
            runOnEdt(() -> setField(subject, "tokenField", originalField));
            CompletableFuture<Boolean> pendingSave = callOnEdt(subject::savePendingChangesAsync);
            runOnEdt(() -> setField(subject, "tokenField", replacementField));

            save.complete(false);

            assertThat(pendingSave.get(2, TimeUnit.SECONDS)).isFalse();
            assertThat(subject.lastSaveError()).isEqualTo("original token save failed");
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Token changes cancel stale Text to Speech catalog refresh before invalidation")
    void prepareForCredentialChange_whenCatalogRefreshInFlight_preventsStaleCatalogPersistence() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-token-catalog-refresh.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, "credential-refreshing");
        var provider = new CredentialRefreshingProvider();
        var subject = createRefreshingPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(provider))
        );
        try {
            assertThat(provider.firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> tokenField(subject).prepareForCredentialChange());
            new TextToSpeechCatalogStore(repo).invalidate(provider.id());
            provider.releaseFirst.countDown();
            assertThat(provider.firstFinished.await(2, TimeUnit.SECONDS)).isTrue();

            TextToSpeechCatalogStore.Catalogs invalidatedCatalogs = new TextToSpeechCatalogStore(repo).catalogs(
                    provider,
                    provider.defaultModel(),
                    provider.defaultVoice()
            );
            assertThat(invalidatedCatalogs.models()).extracting(TextToSpeechCatalogItem::id).doesNotContain("stale-model");
            assertThat(invalidatedCatalogs.voices()).extracting(TextToSpeechCatalogItem::id).doesNotContain("stale-voice");
            assertThat(repo.get(ttsCatalogUpdatedAtKey(provider.id()), "")).isBlank();

            SwingUtilities.invokeAndWait(() -> tokenField(subject).reloadAfterPeerCredentialChanged());

            assertThat(provider.secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> new TextToSpeechCatalogStore(repo).catalogs(
                    provider,
                    provider.defaultModel(),
                    provider.defaultVoice()
            ).models().stream().anyMatch(model -> "fresh-model".equals(model.id())));
            TextToSpeechCatalogStore.Catalogs refreshedCatalogs = new TextToSpeechCatalogStore(repo).catalogs(
                    provider,
                    provider.defaultModel(),
                    provider.defaultVoice()
            );
            assertThat(refreshedCatalogs.models()).extracting(TextToSpeechCatalogItem::id)
                    .contains("fresh-model")
                    .doesNotContain("stale-model");
            assertThat(refreshedCatalogs.voices()).extracting(TextToSpeechCatalogItem::id)
                    .contains("fresh-voice")
                    .doesNotContain("stale-voice");
        } finally {
            provider.releaseFirst.countDown();
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Pending catalog refresh is ignored after the panel is removed")
    void refreshCatalogs_whenPanelRemovedBeforeFetchCompletes_doesNotApplyFetchedCatalogs() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-removed-settings.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, "slow");
        var provider = new SlowProvider();
        var subject = createRefreshingPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(provider))
        );
        try {
            assertThat(provider.fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            removePanel(subject);
            provider.releaseFetch.countDown();

            assertThat(provider.modelsReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            assertThat(provider.voiceFetchStarted.getCount()).isEqualTo(1);
            SwingUtilities.invokeAndWait(() -> {
            });

            List<String> modelIds = callOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<TextToSpeechCatalogItem> modelComboBox = (JComboBox<TextToSpeechCatalogItem>) fieldValue(
                        subject,
                        "modelComboBox"
                );
                return comboItemIds(modelComboBox);
            });
            assertThat(modelIds).doesNotContain("fetched-model");
        } finally {
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Catalog refresh apply read failures report an error without leaking EDT exceptions")
    void refreshCatalogs_whenSettingsReadFailsDuringApply_reportsRefreshError() throws Exception {
        var repo = new FailingSettingsRepository(tempDir.resolve("tts-refresh-apply-read-failure.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, "slow");
        var provider = new SlowProvider();
        var subject = createRefreshingPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(provider))
        );
        try {
            assertThat(provider.fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
            repo.failGet = true;
            provider.releaseFetch.countDown();

            assertThat(provider.modelsReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(callOnEdt(() -> subject.statusLabel().getText())).contains("Could not refresh Slow catalogs.");
        } finally {
            repo.failGet = false;
            provider.releaseFetch.countDown();
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Queued preview failure status is ignored after panel removal")
    void previewSelection_whenPanelRemovedBeforeFailureStatusRuns_doesNotShowStaleStatus() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-preview-removed-settings.properties"));
        repo.put(TextToSpeechSettings.PROVIDER_KEY, "preview-failure");
        var provider = new PreviewFailureProvider();
        var subject = createPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(provider))
        );
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        try {
            JButton previewButton = callOnEdt(() -> (JButton) fieldValue(subject, "previewButton"));
            SwingUtilities.invokeAndWait(previewButton::doClick);
            assertThat(provider.synthesizeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            SwingUtilities.invokeLater(() -> {
                edtBlocked.countDown();
                try {
                    releaseEdt.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(edtBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            provider.releaseSynthesize.countDown();
            assertThat(provider.synthesizeFinished.await(5, TimeUnit.SECONDS)).isTrue();
            waitForPreviewThreadToClear(subject);
            subject.removeNotify();

            releaseEdt.countDown();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(callOnEdt(() -> subject.statusLabel().getText())).doesNotContain("Preview failed");
        } finally {
            provider.releaseSynthesize.countDown();
            releaseEdt.countDown();
            removePanel(subject);
        }
    }

    @Test
    @DisplayName("Provider option uses provider unavailable label and message")
    void providerOption_unavailableProvider_usesProviderText() {
        TextToSpeechPanel.ProviderOption option = TextToSpeechPanel.ProviderOption.of(new FakeProvider(), false);

        assertThat(option.label()).isEqualTo("Fake System (unavailable)");
        assertThat(option.unavailableMessage()).isEqualTo("Fake System is unavailable.");
    }

    private void selectProviderOption(TextToSpeechPanel subject, String providerId) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<TextToSpeechPanel.ProviderOption> comboBox = (JComboBox<TextToSpeechPanel.ProviderOption>) fieldValue(
                    subject,
                    "providerComboBox"
            );
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                TextToSpeechPanel.ProviderOption option = comboBox.getItemAt(i);
                if (providerId.equals(option.providerId())) {
                    comboBox.setSelectedItem(option);
                    return;
                }
            }
            throw new AssertionError("Provider option not found: %s".formatted(providerId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectedProviderId(TextToSpeechPanel subject) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<TextToSpeechPanel.ProviderOption> comboBox = (JComboBox<TextToSpeechPanel.ProviderOption>) fieldValue(
                    subject,
                    "providerComboBox"
            );
            Object selected = comboBox.getSelectedItem();
            return selected instanceof TextToSpeechPanel.ProviderOption option ? option.providerId() : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void selectCatalogItem(TextToSpeechPanel subject, String fieldName, String itemId) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<TextToSpeechCatalogItem> comboBox = (JComboBox<TextToSpeechCatalogItem>) fieldValue(
                    subject,
                    fieldName
            );
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                TextToSpeechCatalogItem item = comboBox.getItemAt(i);
                if (itemId.equals(item.id())) {
                    comboBox.setSelectedItem(item);
                    return;
                }
            }
            throw new AssertionError("Catalog item not found: %s".formatted(itemId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String selectedCatalogItemId(TextToSpeechPanel subject, String fieldName) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<TextToSpeechCatalogItem> comboBox = (JComboBox<TextToSpeechCatalogItem>) fieldValue(
                    subject,
                    fieldName
            );
            Object selected = comboBox.getSelectedItem();
            return selected instanceof TextToSpeechCatalogItem item ? item.id() : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private ApiTokenFieldPanel tokenField(TextToSpeechPanel subject) {
        try {
            return (ApiTokenFieldPanel) fieldValue(subject, "tokenField");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private TextToSpeechPanel createPanel(SettingsRepository repo, TextToSpeechProviderRegistry registry) throws Exception {
        return callOnEdt(() -> new TextToSpeechPanel(
                repo,
                registry,
                new ApiTokenFieldRegistry(),
                SettingsCredentialChangeListener.NO_OP,
                false
        ));
    }

    private TextToSpeechPanel createRefreshingPanel(
            SettingsRepository repo,
            TextToSpeechProviderRegistry registry
    ) throws Exception {
        return callOnEdt(() -> new TextToSpeechPanel(repo, registry));
    }

    private void removePanel(TextToSpeechPanel subject) throws Exception {
        runOnEdt(subject::removeNotify);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private void waitForPreviewThreadToClear(TextToSpeechPanel subject) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fieldValue(subject, "previewThread") != null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(fieldValue(subject, "previewThread")).isNull();
    }

    private JComboBox<TextToSpeechCatalogItem> catalogComboBox(TextToSpeechPanel subject, String fieldName) throws Exception {
        @SuppressWarnings("unchecked")
        JComboBox<TextToSpeechCatalogItem> comboBox = (JComboBox<TextToSpeechCatalogItem>) fieldValue(subject, fieldName);
        return comboBox;
    }

    private List<String> comboItemIds(JComboBox<TextToSpeechCatalogItem> comboBox) {
        return IntStream.range(0, comboBox.getItemCount())
                .mapToObj(comboBox::getItemAt)
                .map(TextToSpeechCatalogItem::id)
                .toList();
    }

    private String ttsCatalogModelsKey(String providerId) {
        return "chat4j.tts.catalog.%s.modelsFile".formatted(providerId);
    }

    private String ttsCatalogVoicesKey(String providerId) {
        return "chat4j.tts.catalog.%s.voicesFile".formatted(providerId);
    }

    private String ttsCatalogUpdatedAtKey(String providerId) {
        return "chat4j.tts.catalog.%s.updatedAt".formatted(providerId);
    }

    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class AvailableSystemProvider implements TextToSpeechProvider {
        @Override
        public String id() {
            return SystemTextToSpeechProvider.ID;
        }

        @Override
        public String displayName() {
            return "System";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("system-model", "System Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("system-voice", "System Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel(), TextToSpeechCatalogItem.of("system-model-alt", "System Model Alt"));
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice(), TextToSpeechCatalogItem.of("system-voice-alt", "System Voice Alt"));
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class CredentialRefreshingProvider implements TextToSpeechProvider {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger modelCalls = new AtomicInteger();

        @Override
        public String id() {
            return "credential-refreshing";
        }

        @Override
        public String displayName() {
            return "Credential Refreshing";
        }

        @Override
        public String requiredEnvVar() {
            return "CHAT4J_TEST_TTS_TOKEN";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("default-model", "Default Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("default-voice", "Default Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            if (modelCalls.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return bundledModels();
                }
                firstFinished.countDown();
                return List.of(TextToSpeechCatalogItem.of("stale-model", "Stale Model"));
            }
            secondStarted.countDown();
            return List.of(TextToSpeechCatalogItem.of("fresh-model", "Fresh Model"));
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            if (modelCalls.get() == 1) {
                return List.of(TextToSpeechCatalogItem.of("stale-voice", "Stale Voice"));
            }
            return List.of(TextToSpeechCatalogItem.of("fresh-voice", "Fresh Voice"));
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class SlowProvider implements TextToSpeechProvider {
        private final CountDownLatch fetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFetch = new CountDownLatch(1);
        private final CountDownLatch modelsReturned = new CountDownLatch(1);
        private final CountDownLatch voiceFetchStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> refreshThread = new AtomicReference<>();

        @Override
        public String id() {
            return "slow";
        }

        @Override
        public String displayName() {
            return "Slow";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("default-model", "Default Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("default-voice", "Default Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            refreshThread.set(Thread.currentThread());
            fetchStarted.countDown();
            try {
                assertThat(releaseFetch.await(5, TimeUnit.SECONDS)).isTrue();
                modelsReturned.countDown();
                return List.of(TextToSpeechCatalogItem.of("fetched-model", "Fetched Model"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return bundledModels();
            }
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            voiceFetchStarted.countDown();
            return List.of(TextToSpeechCatalogItem.of("fetched-voice", "Fetched Voice"));
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class LimitedVoiceProvider implements TextToSpeechProvider {
        @Override
        public String id() {
            return "limited-voice";
        }

        @Override
        public String displayName() {
            return "Limited Voice";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("limited-model", "Limited Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("stale-voice", "Stale Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(TextToSpeechCatalogItem.of("replacement-voice", "Replacement Voice"));
        }

        @Override
        public List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechCatalogItem model, List<TextToSpeechCatalogItem> voices) {
            return bundledVoices();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class PreviewFailureProvider implements TextToSpeechProvider {
        private final CountDownLatch synthesizeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSynthesize = new CountDownLatch(1);
        private final CountDownLatch synthesizeFinished = new CountDownLatch(1);

        @Override
        public String id() {
            return "preview-failure";
        }

        @Override
        public String displayName() {
            return "Preview Failure";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("preview-model", "Preview Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("preview-voice", "Preview Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            synthesizeStarted.countDown();
            try {
                releaseSynthesize.await(5, TimeUnit.SECONDS);
                synthesizeFinished.countDown();
                throw new IllegalStateException("queued failure");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synthesizeFinished.countDown();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    private static final class FailingSettingsRepository extends SettingsRepository {
        private volatile boolean failGet;
        private volatile boolean failPut;
        private volatile boolean failBatch;

        private FailingSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public Optional<String> get(String key) {
            if (failGet) {
                throw new SettingsStorageException("test get failure", new IOException("get failed"));
            }
            return super.get(key);
        }

        @Override
        public void put(String key, String value) {
            if (failPut) {
                throw new SettingsStorageException("test put failure", new IOException("put failed"));
            }
            super.put(key, value);
        }

        @Override
        public void updateBatch(Consumer<BatchUpdate> updates) {
            if (failBatch) {
                throw new SettingsStorageException("test batch failure", new IOException("batch failed"));
            }
            super.updateBatch(updates);
        }
    }

    private static final class FakeProvider implements TextToSpeechProvider {
        @Override
        public String id() {
            return "fake-system";
        }

        @Override
        public String displayName() {
            return "Fake System";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public String unavailableLabel() {
            return "Fake System (unavailable)";
        }

        @Override
        public String unavailableMessage() {
            return "Fake System is unavailable.";
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("model", "Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("voice", "Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }
}
