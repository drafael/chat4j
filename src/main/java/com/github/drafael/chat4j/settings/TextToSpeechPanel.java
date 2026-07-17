package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.ui.ThemeAwareSvgIcon;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
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
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import javax.swing.*;
import org.apache.commons.lang3.StringUtils;

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
    private final SettingsCredentialChangeListener credentialChangeListener;
    private final JavaSoundAudioPlaybackService previewPlayback = new JavaSoundAudioPlaybackService();
    private final Object previewPlaybackLock = new Object();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicLong previewCounter = new AtomicLong();

    private JComboBox<ProviderOption> providerComboBox;
    private JComboBox<TextToSpeechCatalogItem> modelComboBox;
    private JComboBox<TextToSpeechCatalogItem> voiceComboBox;
    private JButton previewButton;
    private JButton refreshButton;
    private JPanel helperPanel;
    private JTextArea helperLabel;
    private SettingsFormRow tokenFormRow;
    private JPanel tokenRowPanel;
    private ApiTokenFieldPanel tokenField;
    private boolean updating;
    private volatile boolean removed;
    private volatile Thread previewThread;
    private String lastProviderId = TextToSpeechSettings.PROVIDER_OFF;
    private volatile String lastSaveError = "";

    public TextToSpeechPanel(SettingsRepository settingsRepo) {
        this(settingsRepo, TextToSpeechProviderRegistry.createDefault(), new ApiTokenFieldRegistry(), SettingsCredentialChangeListener.NO_OP);
    }

    TextToSpeechPanel(SettingsRepository settingsRepo, TextToSpeechProviderRegistry providerRegistry) {
        this(settingsRepo, providerRegistry, new ApiTokenFieldRegistry(), SettingsCredentialChangeListener.NO_OP);
    }

    TextToSpeechPanel(
            SettingsRepository settingsRepo,
            ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener
    ) {
        this(settingsRepo, TextToSpeechProviderRegistry.createDefault(), tokenFieldRegistry, credentialChangeListener);
    }

    TextToSpeechPanel(
            SettingsRepository settingsRepo,
            TextToSpeechProviderRegistry providerRegistry,
            ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener
    ) {
        this(settingsRepo, providerRegistry, tokenFieldRegistry, credentialChangeListener, true);
    }

    TextToSpeechPanel(
            SettingsRepository settingsRepo,
            TextToSpeechProviderRegistry providerRegistry,
            ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener,
            boolean automaticCatalogRefresh
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.textToSpeechSettings = new TextToSpeechSettings(settingsRepo, providerRegistry);
        this.catalogStore = new TextToSpeechCatalogStore(settingsRepo);
        this.tokenFieldRegistry = tokenFieldRegistry;
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

        tokenRowPanel = withPreferredWidth(new JPanel(new BorderLayout()), FIELD_WIDTH);
        tokenRowPanel.setOpaque(false);
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

        helperPanel = createHelperInfoPanel();
        row = addFullWidthRow(form, gbc, row, withPreferredWidth(helperPanel, FIELD_WIDTH));
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
        Thread.startVirtualThread(() -> {
            try {
                List<TextToSpeechCatalogItem> models = selection.provider().fetchModels();
                if (!catalogRefreshCurrent(requestId)) {
                    return;
                }
                List<TextToSpeechCatalogItem> voices = selection.provider().fetchVoices();
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
            }
        });
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
        if (!Objects.equals(current.providerId(), selection.providerId())) {
            return;
        }
        List<TextToSpeechCatalogItem> currentModels = catalogStore.mergeWithSelected(models, selection.provider().bundledModels(), current.model());
        List<TextToSpeechCatalogItem> currentVoices = voicesForModel(
                current,
                catalogStore.mergeWithSelected(voices, selection.provider().bundledVoices(), current.voice())
        );
        updating = true;
        try {
            updateCatalogCombos(currentModels, currentVoices);
            selectCatalogItem(modelComboBox, current.model());
            saveFirstVoiceWhenSelectionIsUnavailable(current, currentVoices);
        } finally {
            updating = false;
        }
        if (explicit) {
            setStatusInfo("Catalogs refreshed");
        }
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
            try {
                String format = selection.provider().defaultResponseFormat();
                TextToSpeechAudio audio = selection.provider().synthesize(new TextToSpeechRequest(
                        selection.providerId(),
                        selectedModel.id(),
                        selectedVoice.id(),
                        PREVIEW_TEXT,
                        format
                ));
                synchronized (previewPlaybackLock) {
                    if (requestId != previewCounter.get()) {
                        return;
                    }
                    previewPlayback.play(audio);
                }
                if (requestId == previewCounter.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!removed && requestId == previewCounter.get()) {
                            setStatusInfo("Preview complete");
                        }
                    });
                }
            } catch (Exception e) {
                if (requestId == previewCounter.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!removed && requestId == previewCounter.get()) {
                            setStatusError("Preview failed: %s".formatted(StringUtils.defaultIfBlank(e.getMessage(), "error")));
                        }
                    });
                }
            } finally {
                if (previewThread == Thread.currentThread()) {
                    previewThread = null;
                }
            }
        });
        previewThread = thread;
        thread.start();
    }

    private void cancelPreviewWork() {
        Thread thread = previewThread;
        previewThread = null;
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
        tokenRowPanel.removeAll();
        tokenField = null;
    }

    private void updateControlAvailability(TextToSpeechSettings.Selection selection) {
        boolean enabled = selection.enabled() && selection.available();
        modelComboBox.setEnabled(enabled);
        voiceComboBox.setEnabled(enabled);
        previewButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        if (!selection.enabled()) {
            setHelperText("Text to Speech is off.");
        } else if (!selection.available()) {
            setHelperText(selection.provider().unavailableMessage());
        } else if (StringUtils.isNotBlank(selection.provider().requiredEnvVar())) {
            setHelperText("%s Text is sent to %s for speech synthesis.".formatted(
                    selection.provider().availableMessage(),
                    selection.provider().displayName()
            ));
        } else {
            setHelperText(selection.provider().availableMessage());
        }
    }

    private void setHelperText(String message) {
        String text = StringUtils.defaultString(message);
        helperLabel.setText(text);
        int rows = estimatedHelperRows(text);
        helperLabel.setRows(rows);
        int lineHeight = helperLabel.getFontMetrics(helperLabel.getFont()).getHeight();
        helperPanel.setPreferredSize(new Dimension(FIELD_WIDTH, Math.max(44, rows * lineHeight + 22)));
        helperPanel.revalidate();
        helperPanel.repaint();
    }

    private int estimatedHelperRows(String text) {
        FontMetrics metrics = helperLabel.getFontMetrics(helperLabel.getFont());
        int textWidth = metrics.stringWidth(StringUtils.defaultString(text));
        return Math.max(1, (int) Math.ceil(textWidth / (double) Math.max(1, FIELD_WIDTH - 24)));
    }

    private JPanel createHelperInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBorder(infoBoxBorder());
        panel.setBackground(infoBoxBackground());
        helperLabel = createWrappingHelperTextArea();
        panel.add(helperLabel, BorderLayout.CENTER);
        return panel;
    }

    private JTextArea createWrappingHelperTextArea() {
        JTextArea textArea = new JTextArea(" ");
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(null);
        Fonts.apply(textArea, Font.PLAIN, Fonts.SIZE_SMALL);
        textArea.setForeground(messageBoxForeground());
        return textArea;
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
