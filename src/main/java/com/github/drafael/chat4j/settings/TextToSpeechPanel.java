package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.ui.ThemeAwareSvgIcon;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.JavaSoundAudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogStore;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.awt.*;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.Collections.emptyList;

public class TextToSpeechPanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private static final int FIELD_WIDTH = 520;
    private static final int CATALOG_LABEL_MAX_LENGTH = 72;
    private static final int BUTTON_ICON_SIZE = 16;
    private static final String SPEAK_ICON_PATH = "/icons/chat/volume-2.svg";
    private static final String PREVIEW_TEXT = "Chat4J can read assistant messages aloud so you can listen while you work.";

    private final TextToSpeechProviderRegistry providerRegistry;
    private final TextToSpeechSettings textToSpeechSettings;
    private final TextToSpeechCatalogStore catalogStore;
    private final ApiTokenFieldRegistry tokenFieldRegistry;
    private final CredentialResolver credentialResolver;
    private final CredentialMutationService credentialMutationService;
    private final SettingsCredentialChangeListener credentialChangeListener;
    private final JavaSoundAudioPlaybackService previewPlayback = new JavaSoundAudioPlaybackService();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicLong previewCounter = new AtomicLong();

    private JComboBox<ProviderOption> providerComboBox;
    private JComboBox<TextToSpeechCatalogItem> modelComboBox;
    private JComboBox<TextToSpeechCatalogItem> voiceComboBox;
    private JButton previewButton;
    private JButton refreshButton;
    private SettingsFormRow tokenFormRow;
    private JLabel credentialStatusLabel;
    private JPanel tokenRowPanel;
    private ApiTokenFieldPanel tokenField;
    private boolean updating;
    private volatile boolean removed;
    private final AtomicReference<Thread> catalogRefreshThread = new AtomicReference<>();
    private final AtomicReference<Thread> previewThread = new AtomicReference<>();
    private String lastProviderId = TextToSpeechSettings.PROVIDER_OFF;
    private volatile String lastSaveError = "";

    public TextToSpeechPanel(
            SettingsRepository settingsRepo,
            CredentialResolver credentialResolver,
            CredentialMutationService credentialMutationService,
            Map<String, String> subprocessEnvironment,
            ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener
    ) {
        this(
                settingsRepo,
                TextToSpeechProviderRegistry.createDefault(credentialResolver, subprocessEnvironment),
                credentialResolver,
                credentialMutationService,
                tokenFieldRegistry,
                credentialChangeListener,
                true
        );
    }

    TextToSpeechPanel(
            SettingsRepository settingsRepo,
            TextToSpeechProviderRegistry providerRegistry,
            CredentialResolver credentialResolver,
            CredentialMutationService credentialMutationService,
            ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener,
            boolean automaticCatalogRefresh
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.textToSpeechSettings = new TextToSpeechSettings(settingsRepo, providerRegistry);
        this.catalogStore = new TextToSpeechCatalogStore(settingsRepo);
        this.tokenFieldRegistry = tokenFieldRegistry;
        this.credentialResolver = credentialResolver;
        this.credentialMutationService = credentialMutationService;
        this.credentialChangeListener = credentialChangeListener == null
                ? SettingsCredentialChangeListener.NO_OP
                : credentialChangeListener;
        buildUi(automaticCatalogRefresh);
    }

    @Override
    public void addNotify() {
        removed = false;
        super.addNotify();
    }

    @Override
    public void removeNotify() {
        removed = true;
        cancelCatalogRefreshes();
        previewCounter.incrementAndGet();
        cancelPreviewWork();
        super.removeNotify();
    }

    private void buildUi(boolean automaticCatalogRefresh) {
        JPanel form = createFormPanel("Text to Speech");
        GridBagConstraints gbc = createFormConstraints();
        int row = 0;

        providerComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        providerComboBox.setRenderer(new ProviderOptionRenderer());
        providerComboBox.addActionListener(e -> onProviderSelected());
        addRow(form, gbc, row++, "Provider", providerComboBox);

        tokenRowPanel = new JPanel(new BorderLayout(0, 4));
        tokenRowPanel.setOpaque(false);
        credentialStatusLabel = createSuccessStatusLabel();
        credentialStatusLabel.setVisible(false);
        tokenRowPanel.add(credentialStatusLabel, BorderLayout.SOUTH);
        tokenFormRow = addManagedRow(form, gbc, row++, "API token", tokenRowPanel);
        tokenFormRow.setVisible(false);

        modelComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        modelComboBox.setPrototypeDisplayValue(TextToSpeechCatalogItem.of("prototype", "Sample TTS model"));
        modelComboBox.setRenderer(new CatalogItemRenderer());
        modelComboBox.addActionListener(e -> onModelSelected());
        addRow(form, gbc, row++, "Model", modelComboBox);

        voiceComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        voiceComboBox.setPrototypeDisplayValue(TextToSpeechCatalogItem.of("prototype", "Sample TTS voice with a readable label"));
        voiceComboBox.setRenderer(new CatalogItemRenderer());
        voiceComboBox.addActionListener(e -> onVoiceSelected());
        addRow(form, gbc, row++, "Voice", voiceComboBox);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        previewButton = new JButton("Speak", loadIcon(SPEAK_ICON_PATH));
        previewButton.setIconTextGap(6);
        previewButton.addActionListener(e -> previewSelection());
        refreshButton = new JButton("Refresh catalogs");
        refreshButton.addActionListener(e -> refreshCatalogsForSelectedProvider(true));
        buttons.add(previewButton);
        buttons.add(refreshButton);
        row = addFullWidthRow(form, gbc, row, buttons);
        addVerticalSpacer(form, gbc, row);

        reloadProviderOptions();
        refreshControlsFromSettings(automaticCatalogRefresh);
    }

    private void reloadProviderOptions() {
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        String selectedProviderId = selection.providerId();
        DefaultComboBoxModel<ProviderOption> model = new DefaultComboBoxModel<>();
        model.addElement(ProviderOption.off());
        providerRegistry.providers().stream()
                .map(provider -> ProviderOption.of(provider, provider.available() || StringUtils.isNotBlank(provider.requiredEnvVar())))
                .forEach(model::addElement);
        updating = true;
        providerComboBox.setModel(model);
        providerComboBox.setSelectedItem(findProviderOption(selectedProviderId));
        lastProviderId = selectedProviderId;
        updating = false;
    }

    private void refreshControlsFromSettings(boolean refreshCatalogs) {
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        updating = true;
        try {
            if (!selection.enabled()) {
                updateCatalogCombos(emptyList(), emptyList());
            } else {
                TextToSpeechCatalogStore.Catalogs catalogs = catalogStore.catalogs(
                        selection.provider(),
                        selection.model(),
                        selection.voice()
                );
                List<TextToSpeechCatalogItem> models = catalogs.models();
                List<TextToSpeechCatalogItem> voices = voicesForModel(selection, catalogs.voices());
                updateCatalogCombos(models, voices);
                selectCatalogItem(modelComboBox, selection.model());
                saveFirstVoiceWhenSelectionIsUnavailable(selection, voices);
            }
        } finally {
            updating = false;
        }
        rebuildTokenField(selection);
        updateControlAvailability(selection);
        if (refreshCatalogs && selection.available()) {
            refreshCatalogsForSelectedProvider(false);
        }
    }

    private void onProviderSelected() {
        if (updating) {
            return;
        }
        Object selected = providerComboBox.getSelectedItem();
        if (!(selected instanceof ProviderOption option)) {
            return;
        }
        if (!option.selectable()) {
            if (option.providerId().equals(lastProviderId)) {
                refreshControlsFromSettings(false);
                return;
            }
            setStatusError(option.unavailableMessage());
            updating = true;
            providerComboBox.setSelectedItem(findProviderOption(lastProviderId));
            updating = false;
            return;
        }
        if (!saveTextToSpeechSetting("provider selection", () -> textToSpeechSettings.saveProvider(option.providerId()), true)) {
            return;
        }
        lastProviderId = option.providerId();
        reloadProviderOptions();
        setStatusInfo(STATUS_SAVED);
        refreshControlsFromSettings(true);
    }

    private void onModelSelected() {
        if (updating) {
            return;
        }
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        selectedCatalogItem(modelComboBox).ifPresent(item -> {
            if (!persistImplicitSystemProvider(selection)) {
                return;
            }
            if (!saveTextToSpeechSetting("model selection", () -> textToSpeechSettings.saveModel(selection.providerId(), item), true)) {
                return;
            }
            setStatusInfo(STATUS_SAVED);
            refreshControlsFromSettings(false);
        });
    }

    private void onVoiceSelected() {
        if (updating) {
            return;
        }
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        selectedCatalogItem(voiceComboBox).ifPresent(item -> {
            if (!persistImplicitSystemProvider(selection)) {
                return;
            }
            if (!saveTextToSpeechSetting("voice selection", () -> textToSpeechSettings.saveVoice(selection.providerId(), item), true)) {
                return;
            }
            setStatusInfo(STATUS_SAVED);
        });
    }

    private long nextCatalogRefreshId() {
        return refreshCounter.incrementAndGet();
    }

    private void cancelCatalogRefreshes() {
        refreshCounter.incrementAndGet();
        Thread thread = catalogRefreshThread.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean catalogRefreshCurrent(long requestId) {
        return !removed && requestId == refreshCounter.get();
    }

    private boolean saveCatalogsIfCurrent(
            long requestId,
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices
    ) {
        return catalogStore.saveCatalogsIf(selection.providerId(), models, voices, () -> catalogRefreshCurrent(requestId));
    }

    private void refreshCatalogsForSelectedProvider(boolean explicit) {
        if (removed) {
            return;
        }
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        if (!selection.enabled() || !selection.available()) {
            updateControlAvailability(selection);
            return;
        }
        long requestId = nextCatalogRefreshId();
        if (explicit) {
            setStatusInfo("Refreshing Text to Speech catalogs...");
        }
        Thread refreshThread = Thread.ofVirtual().unstarted(() -> {
            try {
                if (!catalogRefreshCurrent(requestId)) {
                    return;
                }
                List<TextToSpeechCatalogItem> models = List.copyOf(selection.provider().fetchModels());
                if (!catalogRefreshCurrent(requestId)) {
                    return;
                }
                List<TextToSpeechCatalogItem> voices = List.copyOf(selection.provider().fetchVoices());
                if (!saveCatalogsIfCurrent(requestId, selection, models, voices)) {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (!removed) {
                        applyCatalogRefreshSafely(requestId, selection, models, voices, explicit);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (catalogRefreshCurrent(requestId)) {
                        setStatusError("Could not refresh %s catalogs.".formatted(selection.provider().displayName()));
                    }
                });
            } finally {
                catalogRefreshThread.compareAndSet(Thread.currentThread(), null);
            }
        });
        Thread previousRefresh = catalogRefreshThread.getAndSet(refreshThread);
        if (previousRefresh != null) {
            previousRefresh.interrupt();
        }
        refreshThread.start();
    }

    private void applyCatalogRefreshSafely(
            long requestId,
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices,
            boolean explicit
    ) {
        try {
            applyCatalogRefresh(requestId, selection, models, voices, explicit);
        } catch (Exception e) {
            if (catalogRefreshCurrent(requestId)) {
                setStatusError("Could not refresh %s catalogs.".formatted(selection.provider().displayName()));
            }
        }
    }

    private void applyCatalogRefresh(
            long requestId,
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices,
            boolean explicit
    ) {
        if (!catalogRefreshCurrent(requestId)) {
            return;
        }
        TextToSpeechSettings.Selection current = textToSpeechSettings.resolve();
        if (!Strings.CS.equals(current.providerId(), selection.providerId())) {
            return;
        }
        if (SystemTextToSpeechProvider.ID.equals(current.providerId())) {
            applyLocalCatalogRefresh(current, models, voices, explicit);
            return;
        }
        List<TextToSpeechCatalogItem> currentModels = catalogStore.authoritativeModels(current.provider(), models);
        TextToSpeechCatalogItem repairedModel = preferredCatalogItem(
                current.model(),
                current.provider().defaultModel(),
                currentModels
        );
        boolean modelRepaired = repairModelSelection(current, currentModels, repairedModel);

        List<TextToSpeechCatalogItem> authoritativeVoices = catalogStore.authoritativeVoices(
                current.provider(),
                voices
        );
        List<TextToSpeechCatalogItem> currentVoices = current.provider().voicesForModel(
                repairedModel,
                authoritativeVoices
        );
        TextToSpeechCatalogItem repairedVoice = preferredCatalogItem(
                current.voice(),
                current.provider().defaultVoice(),
                currentVoices
        );
        boolean voiceRepaired = modelRepaired && repairVoiceSelection(current, currentVoices, repairedVoice);

        updating = true;
        try {
            updateCatalogCombos(currentModels, currentVoices);
            if (!modelRepaired || !selectCatalogItem(modelComboBox, repairedModel)) {
                modelComboBox.setSelectedIndex(-1);
            }
            if (!voiceRepaired || !selectCatalogItem(voiceComboBox, repairedVoice)) {
                voiceComboBox.setSelectedIndex(-1);
            }
        } finally {
            updating = false;
        }
        if (explicit && modelRepaired && voiceRepaired) {
            setStatusInfo("Catalogs refreshed");
        }
    }

    private void applyLocalCatalogRefresh(
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices,
            boolean explicit
    ) {
        List<TextToSpeechCatalogItem> currentModels = catalogStore.mergeWithSelected(
                models,
                selection.provider().bundledModels(),
                selection.model()
        );
        List<TextToSpeechCatalogItem> currentVoices = voicesForModel(
                selection,
                catalogStore.mergeWithSelected(voices, selection.provider().bundledVoices(), selection.voice())
        );
        updating = true;
        try {
            updateCatalogCombos(currentModels, currentVoices);
            selectCatalogItem(modelComboBox, selection.model());
            saveFirstVoiceWhenSelectionIsUnavailable(selection, currentVoices);
        } finally {
            updating = false;
        }
        if (explicit) {
            setStatusInfo("Catalogs refreshed");
        }
    }

    private static TextToSpeechCatalogItem preferredCatalogItem(
            TextToSpeechCatalogItem selected,
            TextToSpeechCatalogItem providerDefault,
            List<TextToSpeechCatalogItem> items
    ) {
        TextToSpeechCatalogItem selectedItem = catalogItem(items, selected);
        if (selectedItem != null) {
            return selectedItem;
        }
        TextToSpeechCatalogItem defaultItem = catalogItem(items, providerDefault);
        if (defaultItem != null) {
            return defaultItem;
        }
        return items.isEmpty() ? null : items.getFirst();
    }

    private boolean repairModelSelection(
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            TextToSpeechCatalogItem repairedModel
    ) {
        if (containsCatalogItem(models, selection.model())) {
            return true;
        }
        return saveTextToSpeechSetting("model selection", () -> {
            if (repairedModel == null) {
                textToSpeechSettings.clearModel(selection.providerId());
            } else {
                textToSpeechSettings.saveModel(selection.providerId(), repairedModel);
            }
        }, false);
    }

    private boolean repairVoiceSelection(
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> voices,
            TextToSpeechCatalogItem repairedVoice
    ) {
        if (containsCatalogItem(voices, selection.voice())) {
            return true;
        }
        return saveTextToSpeechSetting("voice selection", () -> {
            if (repairedVoice == null) {
                textToSpeechSettings.clearVoice(selection.providerId());
            } else {
                textToSpeechSettings.saveVoice(selection.providerId(), repairedVoice);
            }
        }, false);
    }

    private static boolean containsCatalogItem(
            List<TextToSpeechCatalogItem> items,
            TextToSpeechCatalogItem selected
    ) {
        return catalogItem(items, selected) != null;
    }

    private static TextToSpeechCatalogItem catalogItem(
            List<TextToSpeechCatalogItem> items,
            TextToSpeechCatalogItem selected
    ) {
        if (selected == null) {
            return null;
        }
        return items.stream()
                .filter(item -> Strings.CS.equals(item.id(), selected.id()))
                .findFirst()
                .orElse(null);
    }

    private void previewSelection() {
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        if (!selection.enabled() || !selection.available()) {
            updateControlAvailability(selection);
            if (selection.enabled()) {
                setStatusError(selection.provider().unavailableMessage());
            }
            return;
        }
        TextToSpeechCatalogItem selectedModel = selectedCatalogItem(modelComboBox).orElse(selection.model());
        TextToSpeechCatalogItem selectedVoice = selectedCatalogItem(voiceComboBox).orElse(selection.voice());
        long requestId = previewCounter.incrementAndGet();
        cancelPreviewWork();
        setStatusInfo("Preparing preview...");
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            String apiKey = null;
            try {
                if (removed || requestId != previewCounter.get()) {
                    return;
                }
                if (StringUtils.isNotBlank(selection.provider().requiredEnvVar())) {
                    apiKey = selection.provider().apiKey();
                }
                String format = selection.provider().defaultResponseFormat();
                var request = new TextToSpeechRequest(
                        selection.providerId(),
                        selectedModel.id(),
                        selectedVoice.id(),
                        PREVIEW_TEXT,
                        format
                );
                TextToSpeechAudio audio = selection.provider().synthesize(request, apiKey);
                previewPlayback.play(
                        audio,
                        () -> removed || requestId != previewCounter.get()
                );
                if (requestId == previewCounter.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!removed && requestId == previewCounter.get()) {
                            setStatusInfo("Preview complete");
                        }
                    });
                }
            } catch (Exception | LinkageError e) {
                String safeMessage = ProviderExceptionMapper.sanitizeMessage(e, apiKey);
                if (requestId == previewCounter.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!removed && requestId == previewCounter.get()) {
                            setStatusError("Preview failed: %s".formatted(StringUtils.defaultIfBlank(safeMessage, "error")));
                        }
                    });
                }
            } finally {
                previewThread.compareAndSet(Thread.currentThread(), null);
            }
        });
        previewThread.set(thread);
        thread.start();
    }

    private void cancelPreviewWork() {
        Thread thread = previewThread.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
        }
        previewPlayback.stop();
    }

    private List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechSettings.Selection selection, List<TextToSpeechCatalogItem> voices) {
        return selection.provider().voicesForModel(selection.model(), voices);
    }

    private void saveFirstVoiceWhenSelectionIsUnavailable(TextToSpeechSettings.Selection selection, List<TextToSpeechCatalogItem> voices) {
        if (!selectCatalogItem(voiceComboBox, selection.voice()) && !voices.isEmpty()) {
            TextToSpeechCatalogItem firstVoice = voices.getFirst();
            if (saveTextToSpeechSetting("voice selection", () -> textToSpeechSettings.saveVoice(selection.providerId(), firstVoice), false)) {
                voiceComboBox.setSelectedItem(firstVoice);
            } else {
                voiceComboBox.setSelectedIndex(-1);
            }
        }
    }

    private boolean persistImplicitSystemProvider(TextToSpeechSettings.Selection selection) {
        if (!SystemTextToSpeechProvider.ID.equals(selection.providerId())) {
            return true;
        }
        try {
            if (!textToSpeechSettings.isProviderUnsetOrBlank()) {
                return true;
            }
        } catch (Exception e) {
            handleTextToSpeechSaveFailure("provider selection", true);
            return false;
        }
        return saveTextToSpeechSetting("provider selection", () -> textToSpeechSettings.saveProvider(SystemTextToSpeechProvider.ID), true);
    }

    private void handleTextToSpeechSaveFailure(String settingName, boolean revertControlsOnFailure) {
        lastSaveError = "Could not save Text to Speech %s.".formatted(settingName);
        setStatusError(lastSaveError);
        if (revertControlsOnFailure) {
            try {
                reloadProviderOptions();
                refreshControlsFromSettings(false);
            } catch (Exception e) {
                setStatusError(lastSaveError);
            }
        }
    }

    private boolean saveTextToSpeechSetting(String settingName, Runnable saveAction, boolean revertControlsOnFailure) {
        try {
            saveAction.run();
            lastSaveError = "";
            return true;
        } catch (Exception e) {
            handleTextToSpeechSaveFailure(settingName, revertControlsOnFailure);
            return false;
        }
    }

    private void updateCatalogCombos(List<TextToSpeechCatalogItem> models, List<TextToSpeechCatalogItem> voices) {
        modelComboBox.setModel(new DefaultComboBoxModel<>(models.toArray(TextToSpeechCatalogItem[]::new)));
        voiceComboBox.setModel(new DefaultComboBoxModel<>(voices.toArray(TextToSpeechCatalogItem[]::new)));
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        ApiTokenFieldPanel field = tokenField;
        if (field == null || !field.dirty()) {
            lastSaveError = "";
            return CompletableFuture.completedFuture(true);
        }
        String conflict = tokenFieldRegistry.conflictMessage(field);
        if (StringUtils.isNotBlank(conflict)) {
            lastSaveError = conflict;
            return CompletableFuture.completedFuture(false);
        }
        return field.savePendingChangesAsync().thenApply(saved -> {
            lastSaveError = saved ? "" : field.lastSaveError();
            return saved;
        });
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "Text to Speech settings";
    }

    private void rebuildTokenField(TextToSpeechSettings.Selection selection) {
        String requiredEnvVar = selection.enabled() ? selection.provider().requiredEnvVar() : null;
        if (StringUtils.isBlank(requiredEnvVar)) {
            clearTokenField();
            tokenFormRow.setVisible(false);
            tokenRowPanel.revalidate();
            tokenRowPanel.repaint();
            return;
        }
        if (tokenField != null && StringUtils.equals(tokenField.canonicalTokenId(), CredentialResolver.canonicalTokenId(requiredEnvVar))) {
            return;
        }
        clearTokenField();
        tokenField = withPreferredWidth(new ApiTokenFieldPanel(
                requiredEnvVar,
                tokenFieldRegistry,
                credentialResolver,
                credentialMutationService,
                credentialChangeListener,
                this::cancelCatalogRefreshes,
                () -> {
                    reloadProviderOptions();
                    refreshControlsFromSettings(true);
                }
        ), FIELD_WIDTH);
        tokenRowPanel.add(tokenField, BorderLayout.CENTER);
        tokenFormRow.setVisible(true);
        tokenRowPanel.revalidate();
        tokenRowPanel.repaint();
    }

    private void clearTokenField() {
        if (tokenField != null) {
            tokenField.unregisterFromRegistry();
        }
        if (tokenField != null) {
            tokenRowPanel.remove(tokenField);
        }
        tokenField = null;
    }

    private void updateControlAvailability(TextToSpeechSettings.Selection selection) {
        boolean enabled = selection.enabled() && selection.available();
        modelComboBox.setEnabled(enabled);
        voiceComboBox.setEnabled(enabled);
        previewButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        updateCredentialStatus(selection);
    }

    private void updateCredentialStatus(TextToSpeechSettings.Selection selection) {
        String requiredEnvVar = selection.enabled() ? selection.provider().requiredEnvVar() : null;
        String statusText = StringUtils.isBlank(requiredEnvVar)
                ? ""
                : credentialStatusText(credentialResolver.resolveCredentialStatus(requiredEnvVar, null));
        boolean visible = selection.available() && StringUtils.isNotBlank(statusText);
        credentialStatusLabel.setText(visible ? statusText : " ");
        credentialStatusLabel.setVisible(visible);
        tokenRowPanel.revalidate();
        tokenRowPanel.repaint();
    }

    private ProviderOption findProviderOption(String providerId) {
        ComboBoxModel<ProviderOption> model = providerComboBox.getModel();
        return IntStream.range(0, model.getSize())
                .mapToObj(model::getElementAt)
                .filter(option -> option.providerId().equals(providerId))
                .findFirst()
                .orElseGet(ProviderOption::off);
    }

    private static boolean selectCatalogItem(JComboBox<TextToSpeechCatalogItem> comboBox, TextToSpeechCatalogItem selected) {
        if (selected == null) {
            return false;
        }
        ComboBoxModel<TextToSpeechCatalogItem> model = comboBox.getModel();
        return IntStream.range(0, model.getSize())
                .mapToObj(model::getElementAt)
                .filter(item -> item.id().equals(selected.id()))
                .findFirst()
                .map(item -> {
                    comboBox.setSelectedItem(item);
                    return true;
                })
                .orElse(false);
    }

    private static Optional<TextToSpeechCatalogItem> selectedCatalogItem(JComboBox<TextToSpeechCatalogItem> comboBox) {
        Object selected = comboBox.getSelectedItem();
        return selected instanceof TextToSpeechCatalogItem item ? Optional.of(item) : Optional.empty();
    }

    private static Icon loadIcon(String iconPath) {
        URL url = TextToSpeechPanel.class.getResource(iconPath);
        return url == null ? null : new ThemeAwareSvgIcon(url, BUTTON_ICON_SIZE);
    }

    record ProviderOption(String providerId, String label, boolean selectable, String unavailableMessage) {

        static ProviderOption off() {
            return new ProviderOption(TextToSpeechSettings.PROVIDER_OFF, "Off", true, "");
        }

        static ProviderOption of(TextToSpeechProvider provider, boolean selectable) {
            String label = selectable ? provider.displayName() : provider.unavailableLabel();
            return new ProviderOption(provider.id(), label, selectable, provider.unavailableMessage());
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class ProviderOptionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ProviderOption option) {
                label.setText(option.label());
                if (!option.selectable() && !isSelected) {
                    label.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
            }
            return label;
        }
    }

    private static final class CatalogItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof TextToSpeechCatalogItem item) {
                String text = StringUtils.isBlank(item.description()) ? item.label() : "%s — %s".formatted(item.label(), item.description());
                label.setText(StringUtils.abbreviate(text, CATALOG_LABEL_MAX_LENGTH));
                label.setToolTipText(text);
            }
            return label;
        }
    }
}
