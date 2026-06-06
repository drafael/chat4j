package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.message.ChatWebViewEngine;
import com.github.drafael.chat4j.chat.message.ChatWebViewRuntimeStatus;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.intellijthemes.*;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.*;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.ColorFunctions;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toSet;

public class AppearancePanel extends AbstractSettingsPanel {

    private static final String KEY_THEME = SettingsKeys.THEME_NAME;
    private static final String KEY_ACCENT_COLOR = SettingsKeys.THEME_ACCENT;
    public static final String KEY_APP_FONT = SettingsKeys.APP_FONT_FAMILY;
    public static final String KEY_APP_FONT_SIZE = SettingsKeys.APP_FONT_SIZE;
    public static final String KEY_CODE_FONT = SettingsKeys.CODE_FONT_FAMILY;

    private static final String DEFAULT_THEME = ThemeSettingsResolver.DEFAULT_THEME;
    public static final String DEFAULT_APP_FONT = "System Default";
    public static final String DEFAULT_CODE_FONT = "Monospaced";
    private static final int FALLBACK_FONT_SIZE = Fonts.SIZE_BODY;
    private static final int[] APP_FONT_SIZE_OPTIONS = {
            Fonts.SIZE_COMPACT,
            Fonts.SIZE_BODY,
            Fonts.SIZE_BODY_LARGE,
            Fonts.SIZE_SUBTITLE,
            Fonts.SIZE_PANEL_TITLE,
            Fonts.SIZE_DISPLAY
    };
    private static final String[] UI_COMPATIBLE_FONT_CANDIDATES = {
            "Helvetica Neue",
            "Helvetica",
            "Arial",
            "Verdana",
            "Tahoma"
    };

    // Accent colors: name -> hex color (null = default/theme color)
    private static final String[][] ACCENT_COLORS = {
            {"Default", null},
            {"Blue", "#007AFF"},
            {"Purple", "#BF5AF2"},
            {"Red", "#FF3B30"},
            {"Orange", "#FF9500"},
            {"Yellow", "#FFCC00"},
            {"Green", "#28CD41"},
    };

    // Current accent color — shared with the system color getter
    private static Color accentColor;

    private static final int WEB_VIEW_HEALTH_ICON_SIZE = 26;

    private final ChatWebViewRuntimeStatus runtimeStatus;
    private final JLabel webViewHealthIcon = new JLabel();
    private final JLabel webViewHealthTitle = new JLabel();
    private final JLabel webViewHealthDetails = new JLabel();
    private final JLabel restartHint = new JLabel("Changes apply after restarting Chat4J.");

    // Theme name -> LaF class name, grouped for display
    private static final Map<String, String> CORE_THEMES = new LinkedHashMap<>();
    private static final Map<String, String> INTELLIJ_THEMES = new LinkedHashMap<>();
    private static final Map<String, String> MATERIAL_THEMES = new LinkedHashMap<>();

    static {
        // Core themes
        CORE_THEMES.put("FlatLaf Light", FlatLightLaf.class.getName());
        CORE_THEMES.put("FlatLaf Dark", FlatDarkLaf.class.getName());
        CORE_THEMES.put("FlatLaf IntelliJ", FlatIntelliJLaf.class.getName());
        CORE_THEMES.put("FlatLaf Darcula", FlatDarculaLaf.class.getName());
        CORE_THEMES.put("FlatLaf macOS Light", FlatMacLightLaf.class.getName());
        CORE_THEMES.put("FlatLaf macOS Dark", FlatMacDarkLaf.class.getName());

        // IntelliJ themes
        INTELLIJ_THEMES.put("Arc", FlatArcIJTheme.class.getName());
        INTELLIJ_THEMES.put("Arc - Orange", FlatArcOrangeIJTheme.class.getName());
        INTELLIJ_THEMES.put("Arc Dark", FlatArcDarkIJTheme.class.getName());
        INTELLIJ_THEMES.put("Arc Dark - Orange", FlatArcDarkOrangeIJTheme.class.getName());
        INTELLIJ_THEMES.put("Carbon", FlatCarbonIJTheme.class.getName());
        INTELLIJ_THEMES.put("Cobalt 2", FlatCobalt2IJTheme.class.getName());
        INTELLIJ_THEMES.put("Cyan Light", FlatCyanLightIJTheme.class.getName());
        INTELLIJ_THEMES.put("Dark Flat", FlatDarkFlatIJTheme.class.getName());
        INTELLIJ_THEMES.put("Dark Purple", FlatDarkPurpleIJTheme.class.getName());
        INTELLIJ_THEMES.put("Dracula", FlatDraculaIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gradianto Dark Fuchsia", FlatGradiantoDarkFuchsiaIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gradianto Deep Ocean", FlatGradiantoDeepOceanIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gradianto Midnight Blue", FlatGradiantoMidnightBlueIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gradianto Nature Green", FlatGradiantoNatureGreenIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gray", FlatGrayIJTheme.class.getName());
        INTELLIJ_THEMES.put("Gruvbox Dark Hard", FlatGruvboxDarkHardIJTheme.class.getName());
        INTELLIJ_THEMES.put("Hiberbee Dark", FlatHiberbeeDarkIJTheme.class.getName());
        INTELLIJ_THEMES.put("High Contrast", FlatHighContrastIJTheme.class.getName());
        INTELLIJ_THEMES.put("Light Flat", FlatLightFlatIJTheme.class.getName());
        INTELLIJ_THEMES.put("Material Design Dark", FlatMaterialDesignDarkIJTheme.class.getName());
        INTELLIJ_THEMES.put("Monocai", FlatMonocaiIJTheme.class.getName());
        INTELLIJ_THEMES.put("Monokai Pro", FlatMonokaiProIJTheme.class.getName());
        INTELLIJ_THEMES.put("Nord", FlatNordIJTheme.class.getName());
        INTELLIJ_THEMES.put("One Dark", FlatOneDarkIJTheme.class.getName());
        INTELLIJ_THEMES.put("Solarized Dark", FlatSolarizedDarkIJTheme.class.getName());
        INTELLIJ_THEMES.put("Solarized Light", FlatSolarizedLightIJTheme.class.getName());
        INTELLIJ_THEMES.put("Spacegray", FlatSpacegrayIJTheme.class.getName());
        INTELLIJ_THEMES.put("Vuesion", FlatVuesionIJTheme.class.getName());
        INTELLIJ_THEMES.put("Xcode Dark", FlatXcodeDarkIJTheme.class.getName());

        // Material themes
        MATERIAL_THEMES.put("Arc Dark (Material)", FlatMTArcDarkIJTheme.class.getName());
        MATERIAL_THEMES.put("Atom One Dark", FlatMTAtomOneDarkIJTheme.class.getName());
        MATERIAL_THEMES.put("Atom One Light", FlatMTAtomOneLightIJTheme.class.getName());
        MATERIAL_THEMES.put("Dracula (Material)", FlatMTDraculaIJTheme.class.getName());
        MATERIAL_THEMES.put("GitHub", FlatMTGitHubIJTheme.class.getName());
        MATERIAL_THEMES.put("GitHub Dark", FlatMTGitHubDarkIJTheme.class.getName());
        MATERIAL_THEMES.put("Light Owl", FlatMTLightOwlIJTheme.class.getName());
        MATERIAL_THEMES.put("Material Darker", FlatMTMaterialDarkerIJTheme.class.getName());
        MATERIAL_THEMES.put("Material Deep Ocean", FlatMTMaterialDeepOceanIJTheme.class.getName());
        MATERIAL_THEMES.put("Material Lighter", FlatMTMaterialLighterIJTheme.class.getName());
        MATERIAL_THEMES.put("Material Oceanic", FlatMTMaterialOceanicIJTheme.class.getName());
        MATERIAL_THEMES.put("Material Palenight", FlatMTMaterialPalenightIJTheme.class.getName());
        MATERIAL_THEMES.put("Monokai Pro (Material)", FlatMTMonokaiProIJTheme.class.getName());
        MATERIAL_THEMES.put("Moonlight", FlatMTMoonlightIJTheme.class.getName());
        MATERIAL_THEMES.put("Night Owl", FlatMTNightOwlIJTheme.class.getName());
        MATERIAL_THEMES.put("Solarized Dark (Material)", FlatMTSolarizedDarkIJTheme.class.getName());
        MATERIAL_THEMES.put("Solarized Light (Material)", FlatMTSolarizedLightIJTheme.class.getName());
    }

    /** Install the system color getter once at startup. */
    public static void installAccentColorGetter() {
        FlatLaf.setSystemColorGetter(name -> "accent".equals(name) ? accentColor : null);
    }

    /** Restore saved accent color (call before LaF setup). */
    public static void restoreAccentColor(SettingsRepo settings) {
        try {
            String hex = settings.get(KEY_ACCENT_COLOR, null);
            accentColor = StringUtils.isNotEmpty(hex) ? Color.decode(hex) : null;
        } catch (Exception e) {
            accentColor = null;
        }
    }

    public static void applySavedFonts(SettingsRepo settings) {
        try {
            String savedAppFont = settings.get(KEY_APP_FONT, DEFAULT_APP_FONT);
            int savedAppFontSize = parseAppFontSize(
                    settings.get(KEY_APP_FONT_SIZE, String.valueOf(defaultAppFontSize())));
            String savedCodeFont = settings.get(KEY_CODE_FONT, DEFAULT_CODE_FONT);

            applyAppFont(savedAppFont, savedAppFontSize);
            applyCodeFont(savedCodeFont);
        } catch (Exception e) {
            applyAppFont(DEFAULT_APP_FONT, defaultAppFontSize());
            applyCodeFont(DEFAULT_CODE_FONT);
        }
    }

    public static String[] appFontOptions() {
        String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(DEFAULT_APP_FONT);

        Font defaultFont = resolveLookAndFeelDefaultAppFont();
        if (defaultFont != null && defaultFont.getFamily() != null && !defaultFont.getFamily().isBlank()) {
            ordered.add(defaultFont.getFamily());
        }

        Arrays.stream(UI_COMPATIBLE_FONT_CANDIDATES)
                .map(candidate -> findFontFamilyIgnoreCase(availableFonts, candidate))
                .filter(StringUtils::isNotBlank)
                .forEach(ordered::add);

        Arrays.stream(availableFonts)
                .filter(AppearancePanel::isHelveticaFamily)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(ordered::add);

        return ordered.toArray(String[]::new);
    }

    public static String[] codeFontOptions() {
        String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        return withPreferredFont(DEFAULT_CODE_FONT, monospacedFontFamilies(availableFonts));
    }

    public static int[] appFontSizeOptions() {
        return APP_FONT_SIZE_OPTIONS.clone();
    }

    public static int defaultAppFontSize() {
        Font defaultFont = resolveLookAndFeelDefaultAppFont();
        return defaultFont != null ? defaultFont.getSize() : FALLBACK_FONT_SIZE;
    }

    public static int normalizeAppFontSize(int requestedSize) {
        if (requestedSize <= 0) {
            return normalizeAppFontSize(defaultAppFontSize());
        }

        int bestMatch = APP_FONT_SIZE_OPTIONS[0];
        int bestDistance = Math.abs(requestedSize - bestMatch);
        for (int candidate : APP_FONT_SIZE_OPTIONS) {
            int distance = Math.abs(requestedSize - candidate);
            if (distance < bestDistance) {
                bestMatch = candidate;
                bestDistance = distance;
            }
        }

        return bestMatch;
    }

    public static void applyAppFont(String fontFamily, int fontSize) {
        Font defaultFont = resolveLookAndFeelDefaultAppFont();
        int normalizedSize = normalizeAppFontSize(fontSize);

        int style = defaultFont != null ? defaultFont.getStyle() : Font.PLAIN;
        String resolvedFamily;
        if (StringUtils.isBlank(fontFamily) || DEFAULT_APP_FONT.equals(fontFamily)) {
            resolvedFamily = defaultFont != null ? defaultFont.getFamily() : Font.SANS_SERIF;
        } else {
            resolvedFamily = fontFamily;
        }

        Font appFont = new Font(resolvedFamily, style, normalizedSize);
        UIManager.put("defaultFont", new FontUIResource(appFont));
    }

    public static void applyCodeFont(String fontFamily) {
        Font base = resolveLookAndFeelDefaultCodeFont();
        Font appFont = resolveLookAndFeelDefaultAppFont();
        int size = appFont != null ? appFont.getSize() : (base != null ? base.getSize() : FALLBACK_FONT_SIZE);

        String defaultFamily = base != null ? base.getFamily() : Font.MONOSPACED;
        String family = StringUtils.isBlank(fontFamily) || DEFAULT_CODE_FONT.equals(fontFamily)
                ? defaultFamily
                : fontFamily;

        Font codeFont = new Font(family, Font.PLAIN, size);
        UIManager.put("monospaced.font", new FontUIResource(codeFont));
    }

    /** Look up the LaF class name for a saved theme display name, or null if not found. */
    public static String classNameForTheme(String displayName) {
        return ObjectUtils.firstNonNull(
                CORE_THEMES.get(displayName),
                INTELLIJ_THEMES.get(displayName),
                MATERIAL_THEMES.get(displayName)
        );
    }

    public static Map<String, Map<String, String>> groupedThemes() {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        grouped.put("Core Themes", Collections.unmodifiableMap(new LinkedHashMap<>(CORE_THEMES)));
        grouped.put("IntelliJ Themes", Collections.unmodifiableMap(new LinkedHashMap<>(INTELLIJ_THEMES)));
        grouped.put("Material Themes", Collections.unmodifiableMap(new LinkedHashMap<>(MATERIAL_THEMES)));
        return Collections.unmodifiableMap(grouped);
    }

    public AppearancePanel(SettingsRepo settingsRepo) {
        this(settingsRepo, ChatWebViewRuntimeStatus.jEditorPaneDefault());
    }

    public AppearancePanel(SettingsRepo settingsRepo, ChatWebViewRuntimeStatus runtimeStatus) {
        super(settingsRepo);
        this.runtimeStatus = runtimeStatus;

        JPanel form = createFormPanel("Appearance");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JComboBox<Object> themeCombo = withPreferredWidth(createThemeSelector(), 320);
        addRow(form, gbc, row++, "Theme", themeCombo);

        JPanel accentPanel = createAccentPanel();
        addRow(form, gbc, row++, "Accent color", accentPanel);
        row = addSectionHint(form, gbc, row, "Theme changes are applied immediately.");

        row = addSectionHeader(form, gbc, row, "Typography");

        String[] availableAppFontOptions = appFontOptions();
        JComboBox<String> appFont = withPreferredWidth(createFontSelector(availableAppFontOptions), 300);
        addRow(form, gbc, row++, "App font", appFont);
        bindComboBox(
                appFont,
                KEY_APP_FONT,
                DEFAULT_APP_FONT,
                Validators.oneOf(
                        new LinkedHashSet<>(List.of(availableAppFontOptions)),
                        "Invalid app font option"
                ),
                value -> {
                    int appFontSize = parseAppFontSize(readString(
                            KEY_APP_FONT_SIZE,
                            String.valueOf(defaultAppFontSize())));
                    applyAppFont(value, appFontSize);
                    refreshAllWindows();
                }
        );

        String[] availableAppFontSizeOptions = IntStream.of(appFontSizeOptions())
                .mapToObj(String::valueOf)
                .toArray(String[]::new);
        JComboBox<String> appFontSize = withPreferredWidth(createFontSelector(availableAppFontSizeOptions), 130);
        addRow(form, gbc, row++, "App font size", appFontSize);
        bindComboBox(
                appFontSize,
                KEY_APP_FONT_SIZE,
                String.valueOf(normalizeAppFontSize(defaultAppFontSize())),
                Validators.oneOf(
                        new LinkedHashSet<>(List.of(availableAppFontSizeOptions)),
                        "Invalid app font size"
                ),
                value -> {
                    int size = parseAppFontSize(value);
                    String family = readString(KEY_APP_FONT, DEFAULT_APP_FONT);
                    applyAppFont(family, size);
                    refreshAllWindows();
                }
        );

        String[] availableCodeFontOptions = codeFontOptions();
        JComboBox<String> codeFont = withPreferredWidth(createFontSelector(availableCodeFontOptions), 300);
        addRow(form, gbc, row++, "Code font", codeFont);
        bindComboBox(
                codeFont,
                KEY_CODE_FONT,
                DEFAULT_CODE_FONT,
                Validators.oneOf(
                        new LinkedHashSet<>(List.of(availableCodeFontOptions)),
                        "Invalid code font option"
                ),
                value -> {
                    applyCodeFont(value);
                    refreshAllWindows();
                }
        );
        row = addSectionHint(form, gbc, row, "Font changes are applied immediately across all open windows.");

        row = addSectionHeader(form, gbc, row, "Chat WebView");
        row = addChatWebViewSettings(form, gbc, row);

        addVerticalSpacer(form, gbc, row);
    }

    private int addChatWebViewSettings(JPanel form, GridBagConstraints gbc, int row) {
        JComboBox<String> engineComboBox = withPreferredWidth(new JComboBox<>(engineSettingValues()), 220);
        engineComboBox.setName("chatWebViewEngineComboBox");
        engineComboBox.setRenderer(new EngineRenderer());
        addRow(form, gbc, row++, "Engine", engineComboBox);
        bindComboBox(
                engineComboBox,
                SettingsKeys.CHAT_WEB_VIEW_ENGINE,
                runtimeStatus.configuredEngine().settingValue(),
                engineValidator(),
                value -> {
                    refreshRestartHint(value);
                    setStatusInfo("Saved — restart Chat4J to apply");
                }
        );

        row = addFullWidthRow(form, gbc, row, restartHint);

        row = addSectionHeader(form, gbc, row, "WebView Diagnostics");
        row = addFullWidthRow(form, gbc, row, createWebViewHealthPanel());

        refreshDiagnostics(readString(
                SettingsKeys.CHAT_WEB_VIEW_ENGINE,
                runtimeStatus.configuredEngine().settingValue()
        ));
        return row;
    }

    private SettingsValidator<String> engineValidator() {
        Set<String> values = Arrays.stream(ChatWebViewEngine.values())
                .map(ChatWebViewEngine::settingValue)
                .collect(toSet());
        return Validators.oneOf(values, "Invalid chat WebView engine");
    }

    private String[] engineSettingValues() {
        return Arrays.stream(ChatWebViewEngine.values())
                .map(ChatWebViewEngine::settingValue)
                .toArray(String[]::new);
    }

    private JPanel createWebViewHealthPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);

        webViewHealthIcon.setPreferredSize(new Dimension(WEB_VIEW_HEALTH_ICON_SIZE, WEB_VIEW_HEALTH_ICON_SIZE));
        panel.add(webViewHealthIcon, BorderLayout.WEST);

        JPanel textPanel = new JPanel(new GridLayout(0, 1, 0, 2));
        textPanel.setOpaque(false);
        Fonts.apply(webViewHealthTitle, Font.BOLD, Fonts.SIZE_BODY);
        Fonts.apply(webViewHealthDetails, Font.PLAIN, Fonts.SIZE_SMALL);
        webViewHealthDetails.setForeground(UIManager.getColor("Label.disabledForeground"));
        textPanel.add(webViewHealthTitle);
        textPanel.add(webViewHealthDetails);
        panel.add(textPanel, BorderLayout.CENTER);

        return panel;
    }

    private void refreshDiagnostics(String configuredValue) {
        WebViewHealth health = webViewHealth();
        webViewHealthIcon.setText(null);
        webViewHealthIcon.setIcon(loadHealthIcon(health.iconPath(), health.color()));
        webViewHealthTitle.setText(health.title());
        webViewHealthDetails.setText(health.details());
        refreshRestartHint(configuredValue);
    }

    private WebViewHealth webViewHealth() {
        if (runtimeStatus.hasFallback()) {
            return new WebViewHealth(
                    "/icons/settings/triangle-alert.svg",
                    warningColor(),
                    "%s fallback active".formatted(runtimeStatus.activeEngine().displayName()),
                    "%s unavailable: %s".formatted(
                            runtimeStatus.configuredEngine().displayName(),
                            StringUtils.defaultIfBlank(runtimeStatus.fallbackReason(), "unknown error"))
            );
        }
        if (runtimeStatus.activeEngine() == ChatWebViewEngine.SWING_WEBVIEW) {
            String mode = StringUtils.defaultIfBlank(runtimeStatus.swingWebViewMode(), "unknown mode");
            return new WebViewHealth(
                    "/icons/settings/circle-check.svg",
                    successColor(),
                    "%s active".formatted(runtimeStatus.activeEngine().displayName()),
                    "Native WebView · %s component".formatted(mode)
            );
        }
        return new WebViewHealth(
                "/icons/settings/java-original.svg",
                null,
                "%s active".formatted(runtimeStatus.activeEngine().displayName()),
                "Swing HTML renderer · Native WebView disabled"
        );
    }

    private Icon loadHealthIcon(String iconPath, Color color) {
        URL url = AppearancePanel.class.getResource(iconPath);
        if (url == null) {
            return null;
        }
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(WEB_VIEW_HEALTH_ICON_SIZE, WEB_VIEW_HEALTH_ICON_SIZE);
        if (color != null) {
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) -> color));
        }
        return icon;
    }

    private Color successColor() {
        Color color = UIManager.getColor("Actions.Green");
        return color != null ? color : new Color(34, 139, 34);
    }

    private Color warningColor() {
        Color color = UIManager.getColor("Actions.Yellow");
        return color != null ? color : new Color(180, 120, 0);
    }


    private void refreshRestartHint(String configuredValue) {
        ChatWebViewEngine configuredEngine = ChatWebViewEngine.fromSettingValue(configuredValue);
        if (runtimeStatus.hasFallback()) {
            restartHint.setText("Chat4J is using %s for this session. See diagnostics below."
                    .formatted(runtimeStatus.activeEngine().displayName()));
            return;
        }
        if (configuredEngine != runtimeStatus.activeEngine()) {
            restartHint.setText("Restart required: currently using %s.".formatted(runtimeStatus.activeEngine().displayName()));
            return;
        }
        restartHint.setText("Changes apply after restarting Chat4J.");
    }

    private JComboBox<Object> createThemeSelector() {
        DefaultComboBoxModel<Object> themeModel = new DefaultComboBoxModel<>();
        Map<String, String> allThemes = new LinkedHashMap<>();

        themeModel.addElement("--- Core Themes ---");
        CORE_THEMES.forEach((name, className) -> {
            themeModel.addElement(name);
            allThemes.put(name, className);
        });

        themeModel.addElement("--- IntelliJ Themes ---");
        INTELLIJ_THEMES.forEach((name, className) -> {
            themeModel.addElement(name);
            allThemes.put(name, className);
        });

        themeModel.addElement("--- Material Themes ---");
        MATERIAL_THEMES.forEach((name, className) -> {
            themeModel.addElement(name);
            allThemes.put(name, className);
        });

        JComboBox<Object> themeCombo = new JComboBox<>(themeModel);
        themeCombo.setRenderer(new ThemeListRenderer());

        String savedTheme = readString(KEY_THEME, DEFAULT_THEME);
        if (allThemes.containsKey(savedTheme)) {
            themeCombo.setSelectedItem(savedTheme);
        } else {
            themeCombo.setSelectedItem(DEFAULT_THEME);
            writeSetting(KEY_THEME, DEFAULT_THEME);
            setStatusError("Saved theme was invalid and has been reset to default");
        }

        final Object[] lastValid = {themeCombo.getSelectedItem()};

        themeCombo.addActionListener(e -> {
            Object selected = themeCombo.getSelectedItem();
            if (!(selected instanceof String name)) {
                return;
            }

            if (name.startsWith("---")) {
                themeCombo.setSelectedItem(lastValid[0]);
                return;
            }

            lastValid[0] = name;
            String className = allThemes.get(name);
            if (className != null) {
                applyTheme(name, className);
            }
        });

        return themeCombo;
    }

    private JPanel createAccentPanel() {
        JPanel accentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        accentPanel.setOpaque(false);

        ButtonGroup accentGroup = new ButtonGroup();
        String savedAccent = readString(KEY_ACCENT_COLOR, null);

        for (String[] option : ACCENT_COLORS) {
            String name = option[0];
            String hex = option[1];
            Color color = hex != null ? Color.decode(hex) : null;

            JToggleButton button = new JToggleButton(new AccentColorIcon(color));
            button.setToolTipText(name);
            button.putClientProperty("JButton.buttonType", "borderless");
            button.setFocusable(false);
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setPreferredSize(new Dimension(28, 28));

            accentGroup.add(button);
            accentPanel.add(button);

            if ((hex == null && savedAccent == null) || (hex != null && hex.equals(savedAccent))) {
                button.setSelected(true);
            }

            button.addActionListener(e -> applyAccentSelection(color, hex));
        }

        return accentPanel;
    }

    private JComboBox<String> createFontSelector(String[] options) {
        return new JComboBox<>(options);
    }

    private static String[] withPreferredFont(String preferred, String[] availableFonts) {
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(preferred);
        ordered.addAll(List.of(availableFonts));
        return ordered.toArray(String[]::new);
    }

    private static String[] monospacedFontFamilies(String[] availableFonts) {
        JLabel probe = new JLabel();
        return Arrays.stream(availableFonts)
                .filter(fontFamily -> isMonospacedFontFamily(probe, fontFamily))
                .toArray(String[]::new);
    }

    private static String findFontFamilyIgnoreCase(String[] availableFonts, String candidate) {
        return Arrays.stream(availableFonts)
                .filter(fontFamily -> fontFamily.equalsIgnoreCase(candidate))
                .findFirst()
                .orElse(null);
    }

    private static boolean isHelveticaFamily(String fontFamily) {
        return fontFamily != null && fontFamily.toLowerCase(Locale.ROOT).contains("helvetica");
    }

    private static boolean isMonospacedFontFamily(JLabel probe, String fontFamily) {
        Font font = new Font(fontFamily, Font.PLAIN, FALLBACK_FONT_SIZE);
        FontMetrics metrics = probe.getFontMetrics(font);

        int i = metrics.charWidth('i');
        int m = metrics.charWidth('m');
        int w = metrics.charWidth('W');
        int zero = metrics.charWidth('0');
        return i > 0 && i == m && m == w && w == zero;
    }

    private static Font resolveLookAndFeelDefaultAppFont() {
        Font defaultFont = UIManager.getLookAndFeelDefaults().getFont("defaultFont");
        if (defaultFont != null) {
            return defaultFont;
        }

        return UIManager.getFont("defaultFont");
    }

    private static Font resolveLookAndFeelDefaultCodeFont() {
        Font monoFont = UIManager.getLookAndFeelDefaults().getFont("monospaced.font");
        if (monoFont != null) {
            return monoFont;
        }

        monoFont = UIManager.getLookAndFeelDefaults().getFont("TextArea.font");
        if (monoFont != null) {
            return monoFont;
        }

        monoFont = UIManager.getFont("monospaced.font");
        if (monoFont != null) {
            return monoFont;
        }

        return UIManager.getFont("TextArea.font");
    }

    private static int parseAppFontSize(String value) {
        try {
            return normalizeAppFontSize(Integer.parseInt(value));
        } catch (Exception e) {
            return normalizeAppFontSize(defaultAppFontSize());
        }
    }

    private void applyAccentSelection(Color color, String hex) {
        accentColor = color;

        if (hex != null) {
            writeSetting(KEY_ACCENT_COLOR, hex);
        } else {
            removeSetting(KEY_ACCENT_COLOR);
        }

        try {
            LookAndFeel current = UIManager.getLookAndFeel();
            FlatLaf.setup(current.getClass().getDeclaredConstructor().newInstance());
            applyConfiguredFontsFromSettings();
            FlatLaf.updateUI();
            setStatusInfo(STATUS_SAVED);
        } catch (Exception e) {
            setStatusError("Failed to apply accent color");
        }
    }

    private void applyTheme(String name, String className) {
        try {
            UIManager.setLookAndFeel(className);
            applyConfiguredFontsFromSettings();
            refreshAllWindows();
            writeSetting(KEY_THEME, name);
            setStatusInfo(STATUS_SAVED);
        } catch (Exception e) {
            applyFallbackTheme();
        }
    }

    private void applyFallbackTheme() {
        try {
            FlatMTMaterialLighterIJTheme.setup();
            refreshAllWindows();
            setStatusError("Failed to apply theme, reverted to Material Lighter");
        } catch (Exception e) {
            setStatusError("Failed to apply theme");
        }
    }

    private void refreshAllWindows() {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            Fonts.refreshComponentTreeFonts(window);
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private void applyConfiguredFontsFromSettings() {
        String savedAppFont = readString(KEY_APP_FONT, DEFAULT_APP_FONT);
        int savedAppFontSize = parseAppFontSize(readString(
                KEY_APP_FONT_SIZE,
                String.valueOf(defaultAppFontSize())));
        String savedCodeFont = readString(KEY_CODE_FONT, DEFAULT_CODE_FONT);

        applyAppFont(savedAppFont, savedAppFontSize);
        applyCodeFont(savedCodeFont);
    }

    /** Colored circle icon for accent color buttons */
    private static class AccentColorIcon implements Icon {
        private static final int SIZE = 16;
        private final Color color;

        AccentColorIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color fill = color;
            if (fill == null) {
                fill = UIManager.getColor("Label.disabledForeground");
                if (fill == null) {
                    fill = Color.GRAY;
                }
            }

            if (!c.isEnabled()) {
                fill = FlatLaf.isLafDark()
                        ? ColorFunctions.shade(fill, 0.5f)
                        : ColorFunctions.tint(fill, 0.6f);
            }

            g2.setColor(fill);
            g2.fillRoundRect(x + 1, y + 1, SIZE - 2, SIZE - 2, 5, 5);

            if (color == null) {
                g2.setColor(UIManager.getColor("Panel.background"));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x + 3, y + SIZE - 3, x + SIZE - 3, y + 3);
            }

            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    private record WebViewHealth(String iconPath, Color color, String title, String details) {
    }

    private static final class EngineRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof String settingValue) {
                label.setText(ChatWebViewEngine.fromSettingValue(settingValue).displayName());
            }
            return label;
        }
    }

    /** Renderer that shows separator items as disabled, non-selectable headers */
    private static class ThemeListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                list,
                value,
                index,
                isSelected,
                cellHasFocus
            );

            if (value instanceof String s && s.startsWith("---")) {
                String header = s.replace("---", "").trim();
                label.setText(header);
                Fonts.apply(label, Font.BOLD, Fonts.SIZE_SMALL);
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
                label.setEnabled(false);
                label.setBorder(new EmptyBorder(4, 6, 2, 6));
            } else {
                Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
                label.setBorder(new EmptyBorder(2, 12, 2, 6));
            }
            return label;
        }
    }
}
