package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.ui.ThemeAwareSvgIcon;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.JavaSoundAudioPlaybackService;
import com.github.drafael.chat4j.tts.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.TextToSpeechCatalogStore;
import com.github.drafael.chat4j.tts.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import javax.swing.*;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class TextToSpeechPanel extends AbstractSettingsPanel {

    private static final int FIELD_WIDTH = 520;
    private static final int CATALOG_LABEL_MAX_LENGTH = 72;
    private static final int BUTTON_ICON_SIZE = 16;
    private static final String SPEAK_ICON_PATH = "/icons/chat/volume-2.svg";
    private static final String PREVIEW_TEXT = "Chat4J can read assistant messages aloud so you can listen while you work.";

    private final TextToSpeechProviderRegistry providerRegistry;
    private final TextToSpeechSettings textToSpeechSettings;
    private final TextToSpeechCatalogStore catalogStore;
    private final JavaSoundAudioPlaybackService previewPlayback = new JavaSoundAudioPlaybackService();
    private final Object previewPlaybackLock = new Object();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicLong previewCounter = new AtomicLong();

    private JComboBox<ProviderOption> providerComboBox;
    private JComboBox<TextToSpeechCatalogItem> modelComboBox;
    private JComboBox<TextToSpeechCatalogItem> voiceComboBox;
    private JButton previewButton;
    private JButton refreshButton;
    private JLabel helperLabel;
    private boolean updating;
    private volatile Thread previewThread;
    private String lastProviderId = SettingsKeys.TTS_PROVIDER_OFF;

    public TextToSpeechPanel(SettingsRepository settingsRepo) {
        this(settingsRepo, TextToSpeechProviderRegistry.createDefault());
    }

    TextToSpeechPanel(SettingsRepository settingsRepo, TextToSpeechProviderRegistry providerRegistry) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.textToSpeechSettings = new TextToSpeechSettings(settingsRepo, providerRegistry);
        this.catalogStore = new TextToSpeechCatalogStore(settingsRepo);
        buildUi();
    }

    @Override
    public void removeNotify() {
        refreshCounter.incrementAndGet();
        previewCounter.incrementAndGet();
        cancelPreviewWork();
        super.removeNotify();
    }

    private void buildUi() {
        JPanel form = createFormPanel("Text to Speech");
        GridBagConstraints gbc = createFormConstraints();
        int row = 0;

        providerComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        providerComboBox.setRenderer(new ProviderOptionRenderer());
        providerComboBox.addActionListener(e -> onProviderSelected());
        addRow(form, gbc, row++, "Provider", providerComboBox);

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

        helperLabel = new JLabel(" ");
        Fonts.apply(helperLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        helperLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        row = addFullWidthRow(form, gbc, row, helperLabel);
        addVerticalSpacer(form, gbc, row);

        reloadProviderOptions();
        refreshControlsFromSettings(true);
    }

    private void reloadProviderOptions() {
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        String selectedProviderId = selection.providerId();
        DefaultComboBoxModel<ProviderOption> model = new DefaultComboBoxModel<>();
        model.addElement(ProviderOption.off());
        providerRegistry.providers().stream()
                .map(provider -> ProviderOption.of(provider, provider.available()))
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
                List<TextToSpeechCatalogItem> models = catalogStore.models(selection.provider(), selection.model());
                List<TextToSpeechCatalogItem> voices = voicesForModel(selection, catalogStore.voices(selection.provider(), selection.voice()));
                updateCatalogCombos(models, voices);
                selectCatalogItem(modelComboBox, selection.model());
                saveFirstVoiceWhenSelectionIsUnavailable(selection, voices);
            }
        } finally {
            updating = false;
        }
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
        lastProviderId = option.providerId();
        textToSpeechSettings.saveProvider(option.providerId());
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
            persistImplicitSystemProvider(selection);
            textToSpeechSettings.saveModel(selection.providerId(), item);
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
            persistImplicitSystemProvider(selection);
            textToSpeechSettings.saveVoice(selection.providerId(), item);
            setStatusInfo(STATUS_SAVED);
        });
    }

    private void refreshCatalogsForSelectedProvider(boolean explicit) {
        TextToSpeechSettings.Selection selection = textToSpeechSettings.resolve();
        if (!selection.enabled() || !selection.available()) {
            updateControlAvailability(selection);
            return;
        }
        long requestId = refreshCounter.incrementAndGet();
        if (explicit) {
            setStatusInfo("Refreshing Text to Speech catalogs...");
        }
        Thread.startVirtualThread(() -> {
            try {
                List<TextToSpeechCatalogItem> models = selection.provider().fetchModels();
                List<TextToSpeechCatalogItem> voices = selection.provider().fetchVoices();
                catalogStore.saveModels(selection.providerId(), models);
                catalogStore.saveVoices(selection.providerId(), voices);
                SwingUtilities.invokeLater(() -> applyCatalogRefresh(requestId, selection, models, voices, explicit));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (requestId == refreshCounter.get()) {
                        setStatusError("Could not refresh %s catalogs.".formatted(selection.provider().displayName()));
                    }
                });
            }
        });
    }

    private void applyCatalogRefresh(
            long requestId,
            TextToSpeechSettings.Selection selection,
            List<TextToSpeechCatalogItem> models,
            List<TextToSpeechCatalogItem> voices,
            boolean explicit
    ) {
        if (requestId != refreshCounter.get()) {
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
                    SwingUtilities.invokeLater(() -> setStatusInfo("Preview complete"));
                }
            } catch (Exception e) {
                if (requestId == previewCounter.get()) {
                    SwingUtilities.invokeLater(() -> setStatusError("Preview failed: %s".formatted(StringUtils.defaultIfBlank(e.getMessage(), "error"))));
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
            textToSpeechSettings.saveVoice(selection.providerId(), firstVoice);
            voiceComboBox.setSelectedItem(firstVoice);
        }
    }

    private void persistImplicitSystemProvider(TextToSpeechSettings.Selection selection) {
        if (SettingsKeys.TTS_PROVIDER_SYSTEM.equals(selection.providerId()) && textToSpeechSettings.isProviderUnsetOrBlank()) {
            textToSpeechSettings.saveProvider(SettingsKeys.TTS_PROVIDER_SYSTEM);
        }
    }

    private void updateCatalogCombos(List<TextToSpeechCatalogItem> models, List<TextToSpeechCatalogItem> voices) {
        modelComboBox.setModel(new DefaultComboBoxModel<>(models.toArray(TextToSpeechCatalogItem[]::new)));
        voiceComboBox.setModel(new DefaultComboBoxModel<>(voices.toArray(TextToSpeechCatalogItem[]::new)));
    }

    private void updateControlAvailability(TextToSpeechSettings.Selection selection) {
        boolean enabled = selection.enabled() && selection.available();
        modelComboBox.setEnabled(enabled);
        voiceComboBox.setEnabled(enabled);
        previewButton.setEnabled(enabled);
        refreshButton.setEnabled(enabled);
        if (!selection.enabled()) {
            helperLabel.setText("Text to Speech is off.");
        } else if (!selection.available()) {
            helperLabel.setText(selection.provider().unavailableMessage());
        } else {
            helperLabel.setText(selection.provider().availableMessage());
        }
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
            return new ProviderOption(SettingsKeys.TTS_PROVIDER_OFF, "Off", true, "");
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
