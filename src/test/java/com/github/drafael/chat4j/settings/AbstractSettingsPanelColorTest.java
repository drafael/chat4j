package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.HSLColor;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ColorUIResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AbstractSettingsPanelColorTest {

    private static final String SUCCESS_FOREGROUND = "Component.success.foreground";
    private static final String SUCCESS_FOCUSED_BORDER = "Component.success.focusedBorderColor";
    private static final String ACTIONS_GREEN = "Actions.Green";
    private static final String ACTIONS_BLUE = "Actions.Blue";
    private static final String ACTIONS_YELLOW = "Actions.Yellow";
    private static final String LABEL_FOREGROUND = "Label.foreground";
    private static final String PANEL_BACKGROUND = "Panel.background";
    private static final String TEXT_AREA_FOREGROUND = "TextArea.foreground";
    private static final String BUTTON_FOREGROUND = "Button.foreground";
    private static final String BUTTON_BACKGROUND = "Button.background";
    private static final String ACCENT_COLOR = "Component.accentColor";

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Success green remains saturated and readable on a light background")
    void successForeground_whenThemeIsLight_returnsDistinctReadableGreen() throws Exception {
        Color semanticGreen = new Color(20, 220, 146);
        Color normalForeground = new Color(29, 29, 29);
        Color background = new Color(228, 230, 235);

        Color result = resolveSuccessColor(uiColors(semanticGreen, normalForeground, background));

        var resultHsl = new HSLColor(result);
        var semanticHsl = new HSLColor(semanticGreen);
        Color closerToThemeLuminance = HSLColor.toRGB(
                semanticHsl.getHue(),
                Math.max(semanticHsl.getSaturation(), 55f),
                Math.round(resultHsl.getLuminance()) + 1
        );

        assertThat(result).isNotEqualTo(semanticGreen);
        assertThat(resultHsl.getHue()).isCloseTo(semanticHsl.getHue(), within(1f));
        assertThat(resultHsl.getSaturation()).isGreaterThanOrEqualTo(55f);
        assertThat(contrastRatio(result, background)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio(closerToThemeLuminance, background)).isLessThan(4.5);
        assertThat(result.getGreen()).isGreaterThan(result.getRed()).isGreaterThan(result.getBlue());
    }

    @Test
    @DisplayName("Success green becomes more saturated and readable on a dark background")
    void successForeground_whenThemeIsDark_returnsDistinctReadableGreen() throws Exception {
        Color semanticGreen = new Color(100, 139, 60);
        Color normalForeground = new Color(220, 220, 220);
        Color background = new Color(45, 48, 50);

        Color result = resolveSuccessColor(uiColors(semanticGreen, normalForeground, background));

        assertThat(result).isNotEqualTo(semanticGreen);
        assertThat(new HSLColor(result).getHue()).isCloseTo(new HSLColor(semanticGreen).getHue(), within(1f));
        assertThat(new HSLColor(result).getSaturation()).isGreaterThanOrEqualTo(55f);
        assertThat(contrastRatio(result, background)).isGreaterThanOrEqualTo(4.5);
        assertThat(result.getGreen()).isGreaterThan(result.getRed()).isGreaterThan(result.getBlue());
    }

    @Test
    @DisplayName("Application accent does not replace the semantic success green")
    void successForeground_whenAccentChanges_returnsSameGreen() throws Exception {
        Map<String, Object> redAccentColors = uiColors(
                new Color(40, 180, 90),
                new Color(35, 35, 35),
                new Color(245, 245, 245)
        );
        redAccentColors.put(ACCENT_COLOR, Color.RED);
        Map<String, Object> blueAccentColors = new HashMap<>(redAccentColors);
        blueAccentColors.put(ACCENT_COLOR, Color.BLUE);

        Color redAccentResult = resolveSuccessColor(redAccentColors);
        Color blueAccentResult = resolveSuccessColor(blueAccentColors);

        assertThat(redAccentResult).isEqualTo(blueAccentResult);
        assertThat(redAccentResult.getGreen()).isGreaterThan(redAccentResult.getRed()).isGreaterThan(redAccentResult.getBlue());
    }

    @Test
    @DisplayName("Credential status identifies its source without repeating the provider name")
    void credentialStatusText_whenCredentialComesFromShell_returnsSourceOnly() throws Exception {
        var status = new ApiCredentialStatus(ApiCredentialSource.SHELL_ENV, "GROQ_API_KEY", null);

        String result = callOnEdt(() -> {
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
            return subject.credentialText(status);
        });

        assertThat(result).isEqualTo("Using shell environment variable GROQ_API_KEY.");
    }

    @Test
    @DisplayName("Success status labels refresh their semantic color after a theme update")
    void successStatusLabel_whenThemeChanges_refreshesSemanticColor() throws Exception {
        StatusLabelThemeChange result = withUiDefaults(uiColors(
                new Color(35, 175, 90),
                new ColorUIResource(35, 35, 35),
                new Color(245, 245, 245)
        ), () -> {
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
            JLabel label = subject.createStatusLabel();
            label.setText("Using Cloud.");
            Color initialForeground = label.getForeground();

            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            defaults.put(SUCCESS_FOREGROUND, new Color(100, 139, 60));
            defaults.put(PANEL_BACKGROUND, new Color(45, 48, 50));
            defaults.put(LABEL_FOREGROUND, new ColorUIResource(225, 225, 225));
            label.updateUI();

            return new StatusLabelThemeChange(
                    label.getText(),
                    initialForeground,
                    label.getForeground(),
                    subject.successColor()
            );
        });

        assertThat(result.text()).isEqualTo("Using Cloud.");
        assertThat(result.updatedForeground()).isNotEqualTo(result.initialForeground());
        assertThat(result.updatedForeground()).isEqualTo(result.expectedForeground());
        assertThat(contrastRatio(result.updatedForeground(), new Color(45, 48, 50))).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @DisplayName("Information and warning boxes use distinct semantic tints")
    void messageBoxColors_whenThemeColorsAreAvailable_returnsDistinctTints() throws Exception {
        Color panelBackground = new Color(245, 245, 245);
        Color foreground = new Color(35, 35, 35);
        MessageBoxColors result = resolveMessageBoxColors(boxUiColors(
                panelBackground,
                new Color(30, 120, 220),
                new Color(240, 170, 20),
                foreground
        ));

        var infoHsl = new HSLColor(result.infoBackground());
        var infoBorderHsl = new HSLColor(result.infoBorder());
        var warningHsl = new HSLColor(result.warningBackground());
        var warningBorderHsl = new HSLColor(result.warningBorder());
        assertThat(result.infoBackground()).isNotEqualTo(panelBackground);
        assertThat(infoHsl.getHue()).isBetween(218f, 230f);
        assertThat(infoHsl.getSaturation()).isGreaterThanOrEqualTo(80f);
        assertThat(infoHsl.getLuminance()).isBetween(82.5f, 88f);
        assertThat(infoBorderHsl.getHue()).isBetween(218f, 230f);
        assertThat(infoBorderHsl.getSaturation()).isGreaterThanOrEqualTo(80f);
        assertThat(infoBorderHsl.getLuminance()).isBetween(76f, 83f);
        assertThat(colorDistance(result.infoBackground(), new Color(183, 202, 252))).isLessThanOrEqualTo(35.0);
        assertThat(result.warningBackground()).isNotEqualTo(panelBackground);
        assertThat(warningHsl.getHue()).isBetween(50f, 58f);
        assertThat(warningHsl.getSaturation()).isGreaterThanOrEqualTo(90f);
        assertThat(warningHsl.getLuminance()).isBetween(80f, 86f);
        assertThat(warningBorderHsl.getHue()).isBetween(50f, 58f);
        assertThat(warningBorderHsl.getSaturation()).isGreaterThanOrEqualTo(90f);
        assertThat(warningBorderHsl.getLuminance()).isBetween(64f, 72f);
        assertThat(colorDistance(result.warningBackground(), new Color(254, 244, 165))).isLessThanOrEqualTo(35.0);
        assertThat(contrastRatio(result.infoForeground(), result.infoBackground())).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio(result.warningForeground(), result.warningBackground())).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @DisplayName("Information and warning boxes retain recognizable semantic hues")
    void messageBoxColors_whenThemeTintsHaveUnexpectedHues_normalizesSemanticHues() throws Exception {
        Color panelBackground = new Color(70, 70, 70);
        Color foreground = new Color(238, 238, 238);
        MessageBoxColors result = resolveMessageBoxColors(boxUiColors(
                panelBackground,
                new Color(224, 160, 0),
                new Color(0, 160, 192),
                foreground
        ));

        var infoHsl = new HSLColor(result.infoBackground());
        var warningHsl = new HSLColor(result.warningBackground());
        assertThat(infoHsl.getHue()).isBetween(218f, 230f);
        assertThat(infoHsl.getSaturation()).isGreaterThanOrEqualTo(80f);
        assertThat(infoHsl.getLuminance()).isBetween(82.5f, 88f);
        assertThat(warningHsl.getHue()).isBetween(50f, 58f);
        assertThat(warningHsl.getSaturation()).isGreaterThanOrEqualTo(90f);
        assertThat(warningHsl.getLuminance()).isBetween(80f, 86f);
        assertThat(result.infoForeground()).isNotEqualTo(foreground);
        assertThat(contrastRatio(result.infoForeground(), result.infoBackground())).isGreaterThanOrEqualTo(4.5);
        assertThat(result.warningForeground()).isNotEqualTo(foreground);
        assertThat(contrastRatio(result.warningForeground(), result.warningBackground())).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @DisplayName("Information and warning boxes refresh their semantic backgrounds after a theme update")
    void messageBoxPanels_whenThemeChanges_refreshSemanticBackgrounds() throws Exception {
        MessageBoxThemeChange result = withUiDefaults(boxUiColors(
                new Color(245, 245, 245),
                new Color(30, 120, 220),
                new Color(240, 170, 20),
                new Color(35, 35, 35)
        ), () -> {
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
            JPanel infoPanel = subject.createInfoPanel();
            JPanel warningPanel = subject.createWarningPanel();
            var infoLabel = new JLabel("Information");
            var infoButton = new JButton("Action");
            var warningText = new JTextArea("Warning");
            var warningButton = new JButton("Action");
            infoPanel.add(infoLabel, BorderLayout.CENTER);
            infoPanel.add(infoButton, BorderLayout.SOUTH);
            warningPanel.add(warningText, BorderLayout.CENTER);
            warningPanel.add(warningButton, BorderLayout.SOUTH);

            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            Color updatedForeground = new ColorUIResource(225, 225, 225);
            Color updatedButtonForeground = new ColorUIResource(17, 36, 58);
            Color updatedButtonBackground = new ColorUIResource(175, 205, 235);
            defaults.put(PANEL_BACKGROUND, new Color(40, 42, 46));
            defaults.put(ACTIONS_BLUE, new Color(90, 160, 240));
            defaults.put(ACTIONS_YELLOW, new Color(240, 190, 70));
            defaults.put(LABEL_FOREGROUND, updatedForeground);
            defaults.put(TEXT_AREA_FOREGROUND, updatedForeground);
            defaults.put(BUTTON_FOREGROUND, updatedButtonForeground);
            defaults.put(BUTTON_BACKGROUND, updatedButtonBackground);
            SwingUtilities.updateComponentTreeUI(infoPanel);
            SwingUtilities.updateComponentTreeUI(warningPanel);

            return new MessageBoxThemeChange(
                    infoPanel.getBackground(),
                    warningPanel.getBackground(),
                    subject.infoBackground(),
                    subject.warningBackground(),
                    infoLabel.getForeground(),
                    warningText.getForeground(),
                    infoButton.getForeground(),
                    infoButton.getBackground(),
                    warningButton.getForeground(),
                    warningButton.getBackground(),
                    subject.infoMessageForeground(),
                    subject.warningMessageForeground(),
                    updatedButtonForeground,
                    updatedButtonBackground
            );
        });

        assertThat(result.updatedInfo()).isEqualTo(result.expectedInfo());
        assertThat(result.updatedWarning()).isEqualTo(result.expectedWarning());
        assertThat(result.updatedInfoForeground()).isEqualTo(result.expectedInfoForeground());
        assertThat(result.updatedWarningForeground()).isEqualTo(result.expectedWarningForeground());
        assertThat(result.updatedInfoButtonForeground()).isEqualTo(result.expectedButtonForeground());
        assertThat(result.updatedInfoButtonBackground()).isEqualTo(result.expectedButtonBackground());
        assertThat(result.updatedWarningButtonForeground()).isEqualTo(result.expectedButtonForeground());
        assertThat(result.updatedWarningButtonBackground()).isEqualTo(result.expectedButtonBackground());
        assertThat(contrastRatio(result.updatedInfoForeground(), result.updatedInfo())).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio(result.updatedWarningForeground(), result.updatedWarning())).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @DisplayName("Normal label text is used when a theme has no semantic success colors")
    void successForeground_whenSemanticColorsAreMissing_returnsLabelForeground() throws Exception {
        Color normalForeground = new Color(35, 35, 35);
        Map<String, Object> colors = uiColors(Color.GREEN, normalForeground, new Color(245, 245, 245));
        colors.put(SUCCESS_FOREGROUND, "missing");
        colors.put(SUCCESS_FOCUSED_BORDER, "missing");
        colors.put(ACTIONS_GREEN, "missing");

        Color result = resolveSuccessColor(colors);

        assertThat(result).isEqualTo(normalForeground);
    }

    @Test
    @DisplayName("Readable text replaces a theme label color that fails the contrast target")
    void successForeground_whenLabelForegroundHasLowContrast_returnsReadableForeground() throws Exception {
        Color lowContrastForeground = new Color(120, 120, 120);
        Color background = new Color(130, 130, 130);
        Map<String, Object> colors = uiColors(Color.GREEN, lowContrastForeground, background);
        colors.put(SUCCESS_FOREGROUND, "missing");
        colors.put(SUCCESS_FOCUSED_BORDER, "missing");
        colors.put(ACTIONS_GREEN, "missing");

        Color result = resolveSuccessColor(colors);

        assertThat(result).isNotEqualTo(lowContrastForeground);
        assertThat(contrastRatio(result, background)).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @DisplayName("Actions green is used when more specific success colors are unavailable")
    void successForeground_whenOnlyActionsGreenIsAvailable_usesActionsGreen() throws Exception {
        Color actionsGreen = new Color(35, 175, 90);
        Color normalForeground = new Color(35, 35, 35);
        Color background = new Color(245, 245, 245);
        Color expected = resolveSuccessColor(uiColors(actionsGreen, normalForeground, background));
        Map<String, Object> colors = uiColors(Color.GREEN, normalForeground, background);
        colors.put(SUCCESS_FOREGROUND, "missing");
        colors.put(SUCCESS_FOCUSED_BORDER, "missing");
        colors.put(ACTIONS_GREEN, actionsGreen);

        Color result = resolveSuccessColor(colors);

        assertThat(result).isEqualTo(expected);
    }

    private Map<String, Object> uiColors(Color success, Color foreground, Color background) {
        Map<String, Object> colors = new HashMap<>();
        colors.put(SUCCESS_FOREGROUND, success);
        colors.put(SUCCESS_FOCUSED_BORDER, "missing");
        colors.put(ACTIONS_GREEN, "missing");
        colors.put(LABEL_FOREGROUND, foreground);
        colors.put(PANEL_BACKGROUND, background);
        return colors;
    }

    private Map<String, Object> boxUiColors(Color background, Color info, Color warning, Color foreground) {
        Map<String, Object> colors = new HashMap<>();
        colors.put(PANEL_BACKGROUND, background);
        colors.put(ACTIONS_BLUE, info);
        colors.put(ACTIONS_YELLOW, warning);
        colors.put(LABEL_FOREGROUND, new ColorUIResource(foreground));
        colors.put(TEXT_AREA_FOREGROUND, new ColorUIResource(foreground));
        colors.put(BUTTON_FOREGROUND, new ColorUIResource(foreground));
        colors.put(BUTTON_BACKGROUND, new ColorUIResource(220, 220, 220));
        return colors;
    }

    private Color resolveSuccessColor(Map<String, Object> overrides) throws Exception {
        return withUiDefaults(overrides, () -> {
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
            return subject.successColor();
        });
    }

    private MessageBoxColors resolveMessageBoxColors(Map<String, Object> overrides) throws Exception {
        return withUiDefaults(overrides, () -> {
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
            return new MessageBoxColors(
                    subject.infoBackground(),
                    subject.warningBackground(),
                    subject.infoMessageForeground(),
                    subject.warningMessageForeground(),
                    subject.infoBorderColor(),
                    subject.warningBorderColor()
            );
        });
    }

    private <T> T withUiDefaults(Map<String, Object> overrides, Callable<T> action) throws Exception {
        return callOnEdt(() -> {
            UIDefaults lookAndFeelDefaults = UIManager.getLookAndFeelDefaults();
            Map<String, Object> previousValues = new HashMap<>();
            Set<String> previouslyPresentKeys = new HashSet<>();
            overrides.keySet().forEach(key -> {
                if (lookAndFeelDefaults.containsKey(key)) {
                    previouslyPresentKeys.add(key);
                }
                previousValues.put(key, lookAndFeelDefaults.get(key));
            });
            try {
                overrides.forEach(lookAndFeelDefaults::put);
                return action.call();
            } finally {
                previousValues.forEach((key, value) -> restoreDefault(
                        lookAndFeelDefaults,
                        key,
                        value,
                        previouslyPresentKeys.contains(key)
                ));
            }
        });
    }

    private void restoreDefault(UIDefaults defaults, String key, Object previousValue, boolean previouslyPresent) {
        if (previouslyPresent) {
            defaults.put(key, previousValue);
        } else {
            defaults.remove(key);
        }
    }

    private double colorDistance(Color first, Color second) {
        int red = first.getRed() - second.getRed();
        int green = first.getGreen() - second.getGreen();
        int blue = first.getBlue() - second.getBlue();
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private double contrastRatio(Color first, Color second) {
        double lighter = Math.max(relativeLuminance(first), relativeLuminance(second));
        double darker = Math.min(relativeLuminance(first), relativeLuminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double relativeLuminance(Color color) {
        return 0.2126 * linearColor(color.getRed())
                + 0.7152 * linearColor(color.getGreen())
                + 0.0722 * linearColor(color.getBlue());
    }

    private double linearColor(int component) {
        double normalized = component / 255.0;
        return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
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

    private record MessageBoxColors(
            Color infoBackground,
            Color warningBackground,
            Color infoForeground,
            Color warningForeground,
            Color infoBorder,
            Color warningBorder
    ) {
    }

    private record StatusLabelThemeChange(
            String text,
            Color initialForeground,
            Color updatedForeground,
            Color expectedForeground
    ) {
    }

    private record MessageBoxThemeChange(
            Color updatedInfo,
            Color updatedWarning,
            Color expectedInfo,
            Color expectedWarning,
            Color updatedInfoForeground,
            Color updatedWarningForeground,
            Color updatedInfoButtonForeground,
            Color updatedInfoButtonBackground,
            Color updatedWarningButtonForeground,
            Color updatedWarningButtonBackground,
            Color expectedInfoForeground,
            Color expectedWarningForeground,
            Color expectedButtonForeground,
            Color expectedButtonBackground
    ) {
    }

    private static final class TestSettingsPanel extends AbstractSettingsPanel {
        private TestSettingsPanel(SettingsRepository settingsRepo) {
            super(settingsRepo);
        }

        private Color successColor() {
            return successForeground();
        }

        private Color infoBackground() {
            return infoBoxBackground();
        }

        private Color warningBackground() {
            return warningBoxBackground();
        }

        private Color infoMessageForeground() {
            return infoBoxForeground();
        }

        private Color warningMessageForeground() {
            return warningBoxForeground();
        }

        private Color infoBorderColor() {
            var border = (CompoundBorder) infoBoxBorder();
            return ((LineBorder) border.getOutsideBorder()).getLineColor();
        }

        private Color warningBorderColor() {
            var border = (CompoundBorder) warningBoxBorder();
            return ((LineBorder) border.getOutsideBorder()).getLineColor();
        }

        private JPanel createInfoPanel() {
            return createInfoBoxPanel(new BorderLayout());
        }

        private JPanel createWarningPanel() {
            return createWarningBoxPanel(new BorderLayout());
        }

        private JLabel createStatusLabel() {
            return createSuccessStatusLabel();
        }

        private String credentialText(ApiCredentialStatus status) {
            return credentialStatusText(status);
        }
    }
}
