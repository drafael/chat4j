package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
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
    private static final int FORM_COLUMN_GAP = 18;

    private final SettingsRepo settingsRepo;
    private final JLabel statusLabel = new JLabel(" ");

    protected AbstractSettingsPanel(SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;

        statusLabel.setFont(Fonts.of(Font.PLAIN, 12));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    protected JPanel createFormPanel(String titleText) {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel(titleText);
        title.setFont(Fonts.of(Font.BOLD, 18));
        title.setBorder(new EmptyBorder(0, 0, 16, 0));
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
        gbc.insets = new Insets(8, 0, 8, FORM_COLUMN_GAP);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    protected void addRow(JPanel form, GridBagConstraints gbc, int row, String labelText, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 0;
        form.add(createRowLabel(labelText), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        form.add(field, gbc);
    }

    protected void addVerticalSpacer(JPanel form, GridBagConstraints gbc, int row) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.VERTICAL;
        form.add(Box.createVerticalGlue(), gbc);
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
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
            setStatusError("Failed to read setting: " + key);
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
            setStatusError("Failed to save setting: " + key);
        }
    }

    protected void removeSetting(String key) {
        try {
            settingsRepo.remove(key);
        } catch (Exception e) {
            setStatusError("Failed to remove setting: " + key);
        }
    }

    protected void setStatusInfo(String message) {
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setText(StringUtils.isNotBlank(message) ? message : " ");
    }

    protected void setStatusError(String message) {
        Color error = UIManager.getColor("Component.error.focusedBorderColor");
        if (error == null) {
            error = new Color(200, 50, 50);
        }
        statusLabel.setForeground(error);
        statusLabel.setText(StringUtils.isNotBlank(message) ? message : " ");
    }

    protected JLabel statusLabel() {
        return statusLabel;
    }

    protected ValidationResult<String> validate(SettingsValidator<String> validator, String value) {
        return validator != null ? validator.validate(value) : ValidationResult.valid(value);
    }

    private JComponent createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        return statusPanel;
    }

    private JLabel createRowLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Fonts.of(Font.PLAIN, 14));
        return label;
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
                    if (!hasHttpScheme || uri.getHost() == null || uri.getHost().isBlank()) {
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
