package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.extras.components.FlatSeparator;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class AbstractSettingsPanel extends JPanel {

    protected static final String STATUS_SAVED = "Saved";

    private static final int PAGE_PADDING_VERTICAL = 16;
    private static final int PAGE_PADDING_HORIZONTAL = 18;
    private static final int FORM_ROW_GAP = 6;
    private static final int FORM_COLUMN_GAP = 12;
    private static final int LABEL_COLUMN_WIDTH = 160;
    private static final int SECTION_TOP_GAP = 10;
    private static final int SECTION_BOTTOM_GAP = 2;
    private static final int STATUS_CLEAR_DELAY_MILLIS = 1400;

    private final SettingsRepo settingsRepo;
    private final JLabel statusLabel = new JLabel(" ");
    private final Timer statusClearTimer;

    protected AbstractSettingsPanel(SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;

        Fonts.apply(statusLabel, Font.PLAIN, Fonts.SIZE_COMPACT);
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setVisible(false);

        statusClearTimer = new Timer(STATUS_CLEAR_DELAY_MILLIS, e -> clearStatus());
        statusClearTimer.setRepeats(false);
    }

    protected JPanel createFormPanel(String titleText) {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(PAGE_PADDING_VERTICAL, PAGE_PADDING_HORIZONTAL, PAGE_PADDING_VERTICAL, PAGE_PADDING_HORIZONTAL));

        JLabel title = new JLabel(titleText);
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_SUBTITLE);
        title.setBorder(new EmptyBorder(0, 0, 10, 0));
        add(title, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        content.add(form, BorderLayout.NORTH);
        content.add(createStatusBar(), BorderLayout.SOUTH);

        add(content, BorderLayout.CENTER);
        return form;
    }

    protected GridBagConstraints createFormConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    protected int addSectionHeader(JPanel form, GridBagConstraints gbc, int row, String sectionTitle) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(row == 0 ? 0 : SECTION_TOP_GAP, 0, SECTION_BOTTOM_GAP, 0);
        form.add(createSectionSeparator(sectionTitle), gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        return row + 1;
    }

    protected int addSectionHint(JPanel form, GridBagConstraints gbc, int row, String hint) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(0, 0, FORM_ROW_GAP, 0);

        JLabel hintLabel = new JLabel(hint);
        Fonts.apply(hintLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        form.add(hintLabel, gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        return row + 1;
    }

    protected void addRow(JPanel form, GridBagConstraints gbc, int row, String labelText, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        form.add(createRowLabel(labelText), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(wrapField(field), gbc);
    }

    protected int addFullWidthRow(JPanel form, GridBagConstraints gbc, int row, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, 0);
        form.add(wrapFieldAtValueColumn(field), gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        return row + 1;
    }

    protected int addCheckBoxRow(JPanel form, GridBagConstraints gbc, int row, JCheckBox checkBox, String text) {
        checkBox.setText(text);
        Fonts.apply(checkBox, Font.PLAIN, Fonts.SIZE_BODY);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, 0);
        form.add(checkBox, gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
        return row + 1;
    }

    protected void addVerticalSpacer(JPanel form, GridBagConstraints gbc, int row) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.VERTICAL;
        gbc.insets = new Insets(0, 0, 0, 0);
        form.add(Box.createVerticalGlue(), gbc);
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(FORM_ROW_GAP, 0, FORM_ROW_GAP, FORM_COLUMN_GAP);
    }

    protected <T extends JComponent> T withPreferredWidth(T field, int width) {
        Dimension preferred = field.getPreferredSize();
        int height = Math.max(preferred.height, 28);
        field.setPreferredSize(new Dimension(width, height));
        return field;
    }

    protected void bindCheckBox(
            JCheckBox checkBox,
            String key,
            boolean defaultValue,
            Consumer<Boolean> onApplied
    ) {
        boolean initialValue = readBoolean(key, defaultValue);
        checkBox.setSelected(initialValue);

        checkBox.addActionListener(e -> {
            boolean selected = checkBox.isSelected();
            writeSetting(key, String.valueOf(selected));
            setStatusInfo(STATUS_SAVED);
            if (onApplied != null) {
                onApplied.accept(selected);
            }
        });
    }

    protected void bindComboBox(
            JComboBox<String> comboBox,
            String key,
            String defaultValue,
            SettingsValidator<String> validator,
            Consumer<String> onApplied
    ) {
        String storedValue = readString(key, defaultValue);
        ValidationResult<String> initialResult = validate(validator, storedValue);
        String initialValue = initialResult.valid() ? initialResult.normalizedValue() : defaultValue;

        if (!initialResult.valid()) {
            setStatusError(initialResult.message());
            writeSetting(key, initialValue);
        }

        comboBox.setSelectedItem(initialValue);

        AtomicBoolean updating = new AtomicBoolean(false);
        AtomicReference<String> lastValidValue = new AtomicReference<>(initialValue);

        comboBox.addActionListener(e -> {
            if (updating.get()) {
                return;
            }

            Object selected = comboBox.getSelectedItem();
            if (!(selected instanceof String rawValue)) {
                return;
            }

            ValidationResult<String> result = validate(validator, rawValue);
            if (!result.valid()) {
                updating.set(true);
                comboBox.setSelectedItem(lastValidValue.get());
                updating.set(false);
                setStatusError(result.message());
                return;
            }

            String normalizedValue = result.normalizedValue();
            lastValidValue.set(normalizedValue);
            writeSetting(key, normalizedValue);
            setStatusInfo(STATUS_SAVED);

            if (!normalizedValue.equals(rawValue)) {
                updating.set(true);
                comboBox.setSelectedItem(normalizedValue);
                updating.set(false);
            }

            if (onApplied != null) {
                onApplied.accept(normalizedValue);
            }
        });
    }

    protected void bindTextField(
            JTextField textField,
            String key,
            String defaultValue,
            SettingsValidator<String> validator,
            Consumer<String> onApplied
    ) {
        String storedValue = readString(key, defaultValue);
        ValidationResult<String> initialResult = validate(validator, storedValue);
        String initialValue = initialResult.valid() ? initialResult.normalizedValue() : defaultValue;

        textField.setText(initialValue);
        AtomicReference<String> lastValidValue = new AtomicReference<>(initialValue);
        AtomicBoolean updating = new AtomicBoolean(false);

        Runnable persist = () -> {
            if (updating.get()) {
                return;
            }

            String rawValue = textField.getText();
            ValidationResult<String> result = validate(validator, rawValue);
            if (!result.valid()) {
                updating.set(true);
                textField.setText(lastValidValue.get());
                updating.set(false);
                setStatusError(result.message());
                return;
            }

            String normalizedValue = result.normalizedValue();
            lastValidValue.set(normalizedValue);
            updating.set(true);
            textField.setText(normalizedValue);
            updating.set(false);

            writeSetting(key, normalizedValue);
            setStatusInfo(STATUS_SAVED);
            if (onApplied != null) {
                onApplied.accept(normalizedValue);
            }
        };

        textField.addActionListener(e -> persist.run());
        textField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                persist.run();
            }
        });
    }

    protected String readString(String key, String defaultValue) {
        try {
            String value = settingsRepo.get(key, defaultValue);
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            setStatusError("Failed to read setting: %s".formatted(key));
            return defaultValue;
        }
    }

    protected boolean readBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(readString(key, String.valueOf(defaultValue)));
    }

    protected void writeSetting(String key, String value) {
        try {
            settingsRepo.put(key, value);
        } catch (Exception e) {
            setStatusError("Failed to save setting: %s".formatted(key));
        }
    }

    protected void removeSetting(String key) {
        try {
            settingsRepo.remove(key);
        } catch (Exception e) {
            setStatusError("Failed to remove setting: %s".formatted(key));
        }
    }

    protected void setStatusInfo(String message) {
        statusClearTimer.stop();
        if (StringUtils.isBlank(message)) {
            clearStatus();
            return;
        }

        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setText(message);
        statusLabel.setVisible(true);

        if (STATUS_SAVED.equals(message)) {
            statusClearTimer.restart();
        }
    }

    protected void setStatusError(String message) {
        statusClearTimer.stop();
        Color error = ObjectUtils.firstNonNull(
                UIManager.getColor("Component.error.focusedBorderColor"),
                new Color(200, 50, 50)
        );
        statusLabel.setForeground(error);
        statusLabel.setText(StringUtils.defaultIfBlank(message, "Error"));
        statusLabel.setVisible(true);
    }

    protected JLabel statusLabel() {
        return statusLabel;
    }

    protected ValidationResult<String> validate(SettingsValidator<String> validator, String value) {
        return validator != null ? validator.validate(value) : ValidationResult.valid(value);
    }

    private JComponent createSectionSeparator(String titleText) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setOpaque(false);

        GridBagConstraints separatorGbc = new GridBagConstraints();
        separatorGbc.gridx = 0;
        separatorGbc.gridy = 0;
        separatorGbc.weightx = 0;
        separatorGbc.anchor = GridBagConstraints.WEST;
        separatorGbc.fill = GridBagConstraints.NONE;

        JLabel title = new JLabel(titleText);
        Fonts.apply(title, Font.PLAIN, Fonts.SIZE_BODY);
        section.add(title, separatorGbc);

        separatorGbc.gridx = 1;
        separatorGbc.weightx = 1;
        separatorGbc.insets = new Insets(0, 8, 0, 0);
        separatorGbc.fill = GridBagConstraints.HORIZONTAL;
        separatorGbc.anchor = GridBagConstraints.CENTER;

        FlatSeparator separator = new FlatSeparator();
        separator.setOrientation(SwingConstants.HORIZONTAL);
        section.add(separator, separatorGbc);

        return section;
    }

    private JComponent createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusPanel.setBorder(new EmptyBorder(6, 0, 0, 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        return statusPanel;
    }

    private JLabel createRowLabel(String text) {
        JLabel label = new JLabel(text);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);

        Dimension preferred = label.getPreferredSize();
        Dimension alignedSize = new Dimension(LABEL_COLUMN_WIDTH, preferred.height);
        label.setPreferredSize(alignedSize);
        label.setMinimumSize(alignedSize);
        return label;
    }

    private JComponent wrapField(JComponent field) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(field, BorderLayout.WEST);
        return wrapper;
    }

    private JComponent wrapFieldAtValueColumn(JComponent field) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(0, LABEL_COLUMN_WIDTH + FORM_COLUMN_GAP, 0, 0));
        wrapper.add(field, BorderLayout.WEST);
        return wrapper;
    }

    private void clearStatus() {
        statusLabel.setText(" ");
        statusLabel.setVisible(false);
    }

    @FunctionalInterface
    public interface SettingsValidator<T> {
        ValidationResult<T> validate(T value);
    }

    public record ValidationResult<T>(boolean valid, T normalizedValue, String message) {

        public static <T> ValidationResult<T> valid(T normalizedValue) {
            return new ValidationResult<>(true, normalizedValue, null);
        }

        public static <T> ValidationResult<T> invalid(String message, T fallbackValue) {
            return new ValidationResult<>(false, fallbackValue, message);
        }
    }

    public static final class Validators {

        private Validators() {
        }

        public static SettingsValidator<String> trimNonBlank(String message) {
            return value -> {
                String normalized = value != null ? value.trim() : "";
                if (normalized.isEmpty()) {
                    return ValidationResult.invalid(message, value);
                }
                return ValidationResult.valid(normalized);
            };
        }

        public static SettingsValidator<String> oneOf(Set<String> allowedValues, String message) {
            return value -> {
                if (allowedValues.contains(value)) {
                    return ValidationResult.valid(value);
                }
                return ValidationResult.invalid(message, value);
            };
        }

        public static SettingsValidator<String> httpUrl(String message) {
            return value -> {
                String normalized = value != null ? value.trim() : "";
                if (normalized.isEmpty()) {
                    return ValidationResult.invalid(message, value);
                }

                try {
                    URI uri = URI.create(normalized);
                    String scheme = uri.getScheme();
                    boolean hasHttpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                    if (!hasHttpScheme || StringUtils.isBlank(uri.getHost())) {
                        return ValidationResult.invalid(message, value);
                    }
                    return ValidationResult.valid(normalized);
                } catch (Exception e) {
                    return ValidationResult.invalid(message, value);
                }
            };
        }
    }
}
