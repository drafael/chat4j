package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.*;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.intellijthemes.*;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.*;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.ColorFunctions;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.toSet;

public class AppearancePanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private static final String KEY_THEME = ThemeSettings.THEME_NAME_KEY;
    private static final String KEY_ACCENT_COLOR = ThemeSettings.THEME_ACCENT_KEY;
    private static final String KEY_APP_FONT = FontSettings.APP_FONT_FAMILY_KEY;
    private static final String KEY_APP_FONT_SIZE = FontSettings.APP_FONT_SIZE_KEY;
    private static final String KEY_CODE_FONT = FontSettings.CODE_FONT_FAMILY_KEY;

    private static final String APP_FONT_SAVE_TARGET = "app font";
    private static final String DEFAULT_THEME = ThemeSettings.DEFAULT_THEME;
    private static final String DEFAULT_APP_FONT = FontSettings.DEFAULT_APP_FONT;
    private static final String DEFAULT_CODE_FONT = FontSettings.DEFAULT_CODE_FONT;
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

    private static final int WEB_VIEW_HEALTH_ICON_SIZE = 28;
    private static final int WEB_VIEW_ENGINE_SELECTOR_WIDTH = 340;
    private static CompletableFuture<FontCatalog> sharedFontCatalog;
    private static long fontCatalogGeneration;

    private final WebViewRuntimeStatus runtimeStatus;
    private final Runnable exitAction;
    private final RestartPrompt restartPrompt;
    private final JLabel webViewHealthIcon = new JLabel();
    private final JLabel webViewHealthTitle = new JLabel();
    private final JLabel webViewHealthDetails = new JLabel();
    private final JLabel restartHint = new JLabel("Changes apply after restarting Chat4J.");
    private boolean restoringWebViewEngineSelection;
    private String currentWebViewEngineSettingValue;
    private String previousWebViewEngineSettingValue;
    private final SettingsWriteQueue writeQueue = new SettingsWriteQueue("appearance-settings-save-");
    private final Map<String, AppearanceSaveRequest> latestRequests = new LinkedHashMap<>();
    private final Map<String, AppearanceSaveRequest> failedRequests = new LinkedHashMap<>();
    private long fontDiscoveryRequest;
    private boolean disposed;
    private String lastSaveError = "";
    private String appFontFamily;
    private int appFontSize;
    private String codeFontFamily;
    private String durableAppFontFamily;
    private int durableAppFontSize;
    private String durableCodeFontFamily;

    // Theme name -> LaF class name, grouped for display
    private static final Map<String, String> CORE_THEMES = new LinkedHashMap<>();
    private static final Map<String, String> INTELLIJ_THEMES = new LinkedHashMap<>();
    private static final Map<String, String> MATERIAL_THEMES = new LinkedHashMap<>();

    @FunctionalInterface
    interface RestartPrompt {
        RestartRequiredDialog.Choice show(Component parent, String message);
    }

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
    public static void restoreAccentColor(SettingsRepository settings) {
        try {
            String hex = settings.get(KEY_ACCENT_COLOR, null);
            accentColor = StringUtils.isNotEmpty(hex) && isSupportedAccent(hex) ? Color.decode(hex) : null;
        } catch (Exception e) {
            accentColor = null;
        }
    }

    static Color currentAccentColor() {
        return accentColor;
    }

    public static void applySavedFonts(SettingsRepository settings) {
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
        return currentFontCatalog().appFonts().clone();
    }

    private static String[] appFontOptions(String[] availableFonts, String defaultFamily) {
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(DEFAULT_APP_FONT);
        if (StringUtils.isNotBlank(defaultFamily)) {
            ordered.add(defaultFamily);
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
        return currentFontCatalog().codeFonts().clone();
    }

    public static CompletableFuture<Void> prepareFontCatalogAsync() {
        return fontCatalogAsync().handle((ignored, error) -> null);
    }

    public static synchronized long fontCatalogGeneration() {
        return fontCatalogGeneration;
    }

    private static FontCatalog currentFontCatalog() {
        CompletableFuture<FontCatalog> catalog = fontCatalogAsync();
        CompletableFuture<FontCatalog> withFallback = catalog.exceptionally(error -> fallbackFontCatalog());
        if (SwingUtilities.isEventDispatchThread()) {
            return withFallback.getNow(fallbackFontCatalog());
        }
        return withFallback.join();
    }

    private static synchronized CompletableFuture<FontCatalog> fontCatalogAsync() {
        if (sharedFontCatalog != null && !sharedFontCatalog.isCompletedExceptionally()) {
            return sharedFontCatalog;
        }
        Font defaultFont = resolveLookAndFeelDefaultAppFont();
        String defaultFamily = defaultFont == null ? null : defaultFont.getFamily();
        CompletableFuture<FontCatalog> catalog = new CompletableFuture<>();
        sharedFontCatalog = catalog;
        Thread.ofVirtual().name("installed-font-discovery").start(() -> {
            try {
                String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
                FontCatalog discovered = new FontCatalog(
                        appFontOptions(availableFonts, defaultFamily),
                        codeFontOptions(availableFonts)
                );
                if (catalog.complete(discovered)) {
                    incrementFontCatalogGeneration();
                }
            } catch (Throwable t) {
                catalog.completeExceptionally(t);
                if (t instanceof Error error && !(error instanceof LinkageError)) {
                    throw error;
                }
            }
        });
        return catalog;
    }

    private static synchronized void incrementFontCatalogGeneration() {
        fontCatalogGeneration++;
    }

    private static FontCatalog fallbackFontCatalog() {
        return new FontCatalog(new String[]{DEFAULT_APP_FONT}, new String[]{DEFAULT_CODE_FONT});
    }

    private static String[] codeFontOptions(String[] availableFonts) {
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

    public AppearancePanel(SettingsRepository settingsRepo) {
        this(settingsRepo, WebViewRuntimeStatus.jEditorPaneDefault());
    }

    public AppearancePanel(SettingsRepository settingsRepo, WebViewRuntimeStatus runtimeStatus) {
        this(settingsRepo, runtimeStatus, () -> System.exit(0));
    }

    public AppearancePanel(SettingsRepository settingsRepo, WebViewRuntimeStatus runtimeStatus, Runnable exitAction) {
        this(settingsRepo, runtimeStatus, exitAction, RestartRequiredDialog::show);
    }

    AppearancePanel(
            SettingsRepository settingsRepo,
            WebViewRuntimeStatus runtimeStatus,
            Runnable exitAction,
            RestartPrompt restartPrompt
    ) {
        this(settingsRepo, runtimeStatus, exitAction, restartPrompt, AppearancePanel::fontCatalogAsync);
    }

    AppearancePanel(
            SettingsRepository settingsRepo,
            WebViewRuntimeStatus runtimeStatus,
            Runnable exitAction,
            RestartPrompt restartPrompt,
            Supplier<CompletableFuture<FontCatalog>> fontCatalogLoader
    ) {
        super(settingsRepo);
        this.runtimeStatus = runtimeStatus;
        this.exitAction = exitAction == null ? () -> System.exit(0) : exitAction;
        this.restartPrompt = restartPrompt == null ? RestartRequiredDialog::show : restartPrompt;
        appFontFamily = readString(KEY_APP_FONT, DEFAULT_APP_FONT);
        appFontSize = parseAppFontSize(readString(KEY_APP_FONT_SIZE, String.valueOf(defaultAppFontSize())));
        codeFontFamily = readString(KEY_CODE_FONT, DEFAULT_CODE_FONT);
        durableAppFontFamily = appFontFamily;
        durableAppFontSize = appFontSize;
        durableCodeFontFamily = codeFontFamily;

        JPanel form = createFormPanel("Appearance");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JComboBox<Object> themeCombo = withPreferredWidth(createThemeSelector(), 320);
        addRow(form, gbc, row++, "Theme", themeCombo);

        JPanel accentPanel = createAccentPanel();
        addRow(form, gbc, row++, "Accent color", accentPanel);
        row = addSectionHint(form, gbc, row, "Theme changes are applied immediately.");

        row = addSectionHeader(form, gbc, row, "Typography");

        JComboBox<String> appFont = withPreferredWidth(createFontSelector(new String[]{appFontFamily}), 300);
        appFont.setName("appFontComboBox");
        appFont.putClientProperty("fontCatalogLoading", true);
        appFont.setEnabled(false);
        addRow(form, gbc, row++, "App font", appFont);

        String[] availableAppFontSizeOptions = IntStream.of(appFontSizeOptions()).mapToObj(String::valueOf).toArray(String[]::new);
        JComboBox<String> appFontSizeSelector = withPreferredWidth(createFontSelector(availableAppFontSizeOptions), 130);
        appFontSizeSelector.setName("appFontSizeComboBox");
        appFontSizeSelector.setSelectedItem(String.valueOf(appFontSize));
        appFontSizeSelector.setEnabled(false);
        addRow(form, gbc, row++, "App font size", appFontSizeSelector);

        JComboBox<String> codeFont = withPreferredWidth(createFontSelector(new String[]{codeFontFamily}), 300);
        codeFont.setName("codeFontComboBox");
        codeFont.putClientProperty("fontCatalogLoading", true);
        codeFont.setEnabled(false);
        addRow(form, gbc, row++, "Code font", codeFont);

        bindTypographySelectors(appFont, appFontSizeSelector, codeFont);
        discoverFonts(appFont, appFontSizeSelector, codeFont, fontCatalogLoader.get());
        row = addSectionHint(form, gbc, row, "Font changes are applied immediately across all open windows.");

        row = addSectionHeader(form, gbc, row, "Chat WebView");
        row = addChatWebViewSettings(form, gbc, row);

        addVerticalSpacer(form, gbc, row);
    }

    private void bindTypographySelectors(
            JComboBox<String> appFontSelector,
            JComboBox<String> appFontSizeSelector,
            JComboBox<String> codeFontSelector
    ) {
        bindAsyncComboBox(
                appFontSelector,
                APP_FONT_SAVE_TARGET,
                appFontFamily,
                value -> ValidationResult.valid(value),
                value -> {
                    appFontFamily = value;
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, false);
                },
                value -> {
                    int capturedSize = appFontSize;
                    return new AppearanceMutation(
                            () -> new FontSettings(settingsRepo()).persistAppFontSelection(value, capturedSize),
                            () -> {
                                durableAppFontFamily = value;
                                durableAppFontSize = capturedSize;
                            }
                    );
                },
                value -> {
                    applyAppFont(appFontFamily, appFontSize);
                    refreshAllWindows();
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true);
                },
                value -> setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true)
        );
        bindAsyncComboBox(
                appFontSizeSelector,
                APP_FONT_SAVE_TARGET,
                String.valueOf(appFontSize),
                Validators.oneOf(new LinkedHashSet<>(List.of(IntStream.of(appFontSizeOptions())
                        .mapToObj(String::valueOf).toArray(String[]::new))), "Invalid app font size"),
                value -> {
                    appFontSize = parseAppFontSize(value);
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, false);
                },
                value -> {
                    String capturedFamily = appFontFamily;
                    int capturedSize = appFontSize;
                    return new AppearanceMutation(
                            () -> new FontSettings(settingsRepo()).persistAppFontSelection(capturedFamily, capturedSize),
                            () -> {
                                durableAppFontFamily = capturedFamily;
                                durableAppFontSize = capturedSize;
                            }
                    );
                },
                value -> {
                    applyAppFont(appFontFamily, appFontSize);
                    refreshAllWindows();
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true);
                },
                value -> setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true)
        );
        bindAsyncComboBox(
                codeFontSelector,
                "code font",
                codeFontFamily,
                value -> ValidationResult.valid(value),
                value -> {
                    codeFontFamily = value;
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, false);
                },
                value -> new AppearanceMutation(
                        () -> new FontSettings(settingsRepo()).persistCodeFontFamily(value),
                        () -> durableCodeFontFamily = value
                ),
                value -> {
                    applyCodeFont(value);
                    refreshAllWindows();
                    setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true);
                },
                value -> setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true)
        );
    }

    private void discoverFonts(
            JComboBox<String> appFontSelector,
            JComboBox<String> appFontSizeSelector,
            JComboBox<String> codeFontSelector,
            CompletableFuture<FontCatalog> catalogFuture
    ) {
        long request = ++fontDiscoveryRequest;
        catalogFuture.whenComplete((catalog, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed || request != fontDiscoveryRequest) {
                return;
            }
            if (error != null) {
                FontCatalog fallback = fallbackFontCatalog();
                replaceFontOptions(appFontSelector, fallback.appFonts(), appFontFamily);
                replaceFontOptions(codeFontSelector, fallback.codeFonts(), codeFontFamily);
                setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true);
                setStatusError("Failed to load installed fonts");
                return;
            }
            replaceFontOptions(appFontSelector, catalog.appFonts(), appFontFamily);
            replaceFontOptions(codeFontSelector, catalog.codeFonts(), codeFontFamily);
            setTypographyEnabled(appFontSelector, appFontSizeSelector, codeFontSelector, true);
        }));
    }

    private void setTypographyEnabled(
            JComboBox<String> appFontSelector,
            JComboBox<String> appFontSizeSelector,
            JComboBox<String> codeFontSelector,
            boolean enabled
    ) {
        appFontSelector.setEnabled(enabled);
        appFontSizeSelector.setEnabled(enabled);
        codeFontSelector.setEnabled(enabled);
    }

    private void replaceFontOptions(JComboBox<String> selector, String[] options, String selectedValue) {
        selector.putClientProperty("fontCatalogLoading", true);
        selector.setModel(new DefaultComboBoxModel<>(withPreferredFont(selectedValue, options)));
        selector.setSelectedItem(selectedValue);
        selector.putClientProperty("fontCatalogLoading", false);
        selector.setEnabled(true);
    }

    private void bindAsyncComboBox(
            JComboBox<String> comboBox,
            String target,
            String initialValue,
            SettingsValidator<String> validator,
            Consumer<String> onCaptured,
            Function<String, AppearanceMutation> mutationFactory,
            Consumer<String> onApplied,
            Consumer<String> onFailure
    ) {
        comboBox.setSelectedItem(initialValue);
        AtomicBoolean updating = new AtomicBoolean();
        AtomicReference<String> persistedValue = new AtomicReference<>(initialValue);
        comboBox.addActionListener(e -> {
            if (updating.get()
                    || Boolean.TRUE.equals(comboBox.getClientProperty("fontCatalogLoading"))
                    || (WebViewSettings.ENGINE_KEY.equals(target) && restoringWebViewEngineSelection)) {
                return;
            }
            Object selected = comboBox.getSelectedItem();
            if (!(selected instanceof String rawValue)) {
                return;
            }
            ValidationResult<String> validation = validate(validator, rawValue);
            if (!validation.valid()) {
                updating.set(true);
                comboBox.setSelectedItem(persistedValue.get());
                updating.set(false);
                setStatusError(validation.message());
                return;
            }
            String value = validation.normalizedValue();
            onCaptured.accept(value);
            AppearanceMutation mutation = mutationFactory.apply(value);
            enqueueSave(
                    target,
                    mutation.write(),
                    () -> {
                        mutation.onDurableSuccess().run();
                        persistedValue.set(value);
                    },
                    () -> {
                        onApplied.accept(value);
                        setStatusInfo(STATUS_SAVED);
                    },
                    () -> onFailure.accept(value)
            );
        });
    }

    private void enqueueSave(
            String target,
            Runnable mutation,
            Runnable onDurableSuccess,
            Runnable onCurrentSuccess,
            Runnable onCurrentFailure
    ) {
        enqueueSave(target, mutation, onDurableSuccess, onCurrentSuccess, onCurrentFailure, false);
    }

    private void enqueueSave(
            String target,
            Runnable mutation,
            Runnable onDurableSuccess,
            Runnable onCurrentSuccess,
            Runnable onCurrentFailure,
            boolean retry
    ) {
        if (disposed) {
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        AppearanceSaveRequest request = new AppearanceSaveRequest(
                mutation,
                onDurableSuccess,
                onCurrentSuccess,
                onCurrentFailure,
                completion
        );
        latestRequests.put(target, request);
        if (!retry) {
            failedRequests.remove(target);
            refreshLastSaveError();
        }
        writeQueue.submit(mutation).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> finishSave(target, request, error)));
    }

    private void finishSave(String target, AppearanceSaveRequest request, Throwable writeError) {
        Error writeFatalError = SettingsWriteQueue.fatalError(writeError);
        Throwable callbackError = null;
        try {
            if (writeError == null) {
                request.onDurableSuccess().run();
            }
            if (latestRequests.get(target) == request) {
                if (writeError == null) {
                    failedRequests.remove(target);
                    refreshLastSaveError();
                    if (!disposed) {
                        try {
                            request.onCurrentSuccess().run();
                        } catch (Throwable t) {
                            callbackError = t;
                            showFollowUpFailure(target);
                        }
                    }
                } else {
                    failedRequests.put(target, request);
                    refreshLastSaveError();
                    if (!disposed) {
                        request.onCurrentFailure().run();
                    }
                }
                if (!disposed && !failedRequests.isEmpty()) {
                    setStatusError(lastSaveError);
                }
            }
        } catch (Throwable t) {
            callbackError = t;
            if (latestRequests.get(target) == request) {
                if (writeError == null) {
                    failedRequests.remove(target);
                    refreshLastSaveError();
                    if (!disposed) {
                        showFollowUpFailure(target);
                    }
                } else {
                    failedRequests.put(target, request);
                    refreshLastSaveError();
                    if (!disposed) {
                        setStatusError(lastSaveError);
                    }
                }
            }
        } finally {
            request.completion().complete(null);
        }
        if (writeFatalError != null) {
            throw writeFatalError;
        }
        if (callbackError instanceof Error error && !(error instanceof LinkageError)) {
            throw error;
        }
    }

    private void showFollowUpFailure(String target) {
        if (failedRequests.isEmpty()) {
            setStatusError("%s was saved, but the follow-up action failed".formatted(saveTargetLabel(target)));
        }
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        List.copyOf(failedRequests.entrySet()).forEach(entry -> {
            AppearanceSaveRequest request = entry.getValue();
            enqueueSave(
                    entry.getKey(),
                    request.mutation(),
                    request.onDurableSuccess(),
                    request.onCurrentSuccess(),
                    request.onCurrentFailure(),
                    true
            );
        });
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        awaitStableSaves(result);
        return result;
    }

    private void awaitStableSaves(CompletableFuture<Boolean> result) {
        Set<CompletableFuture<Void>> observed = latestRequests.values().stream()
                .map(AppearanceSaveRequest::completion)
                .collect(toSet());
        CompletableFuture.allOf(observed.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    Set<CompletableFuture<Void>> current = latestRequests.values().stream()
                            .map(AppearanceSaveRequest::completion)
                            .collect(toSet());
                    if (!current.equals(observed)) {
                        awaitStableSaves(result);
                    } else {
                        result.complete(failedRequests.isEmpty());
                    }
                }));
    }

    private void refreshLastSaveError() {
        lastSaveError = failedRequests.keySet().stream()
                .findFirst()
                .map(target -> "Failed to save %s setting".formatted(saveTargetLabel(target)))
                .orElse("");
    }

    private String saveTargetLabel(String target) {
        return switch (target) {
            case KEY_THEME -> "theme";
            case KEY_ACCENT_COLOR -> "accent color";
            case WebViewSettings.ENGINE_KEY -> "WebView engine";
            default -> target;
        };
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "Appearance settings";
    }

    void disposePanel() {
        if (disposed) {
            return;
        }
        disposed = true;
        fontDiscoveryRequest++;
        writeQueue.close();
        disposeSettingsPanel();
    }

    private int addChatWebViewSettings(JPanel form, GridBagConstraints gbc, int row) {
        JComboBox<String> engineComboBox = withPreferredWidth(new JComboBox<>(engineSettingValues()), WEB_VIEW_ENGINE_SELECTOR_WIDTH);
        engineComboBox.setName("chatWebViewEngineComboBox");
        engineComboBox.setRenderer(new EngineRenderer());
        addRow(form, gbc, row++, "Engine", engineComboBox);
        currentWebViewEngineSettingValue = WebViewEngine.fromSettingValue(
                readString(WebViewSettings.ENGINE_KEY, runtimeStatus.configuredEngine().settingValue()),
                runtimeStatus.configuredEngine()
        ).settingValue();
        bindAsyncComboBox(
                engineComboBox,
                WebViewSettings.ENGINE_KEY,
                currentWebViewEngineSettingValue,
                engineValidator(),
                value -> {
                    previousWebViewEngineSettingValue = currentWebViewEngineSettingValue;
                    currentWebViewEngineSettingValue = value;
                },
                value -> new AppearanceMutation(
                        () -> settingsRepo().put(WebViewSettings.ENGINE_KEY, value),
                        () -> {
                        }
                ),
                value -> handleWebViewEngineApplied(engineComboBox, value),
                value -> {
                }
        );

        row = addFullWidthRow(form, gbc, row, restartHint);

        row = addSectionHeader(form, gbc, row, "WebView Diagnostics");
        row = addFullWidthRow(form, gbc, row, createWebViewHealthPanel());

        refreshDiagnostics(readString(
                WebViewSettings.ENGINE_KEY,
                runtimeStatus.configuredEngine().settingValue()
        ));
        return row;
    }

    private void handleWebViewEngineApplied(JComboBox<String> engineComboBox, String selectedValue) {
        WebViewEngine selectedEngine = WebViewEngine.fromSettingValue(selectedValue, runtimeStatus.configuredEngine());
        String selectedEngineValue = selectedEngine.settingValue();

        String previousValue = StringUtils.defaultIfBlank(
                previousWebViewEngineSettingValue,
                runtimeStatus.configuredEngine().settingValue()
        );
        refreshRestartHint(selectedEngineValue);

        if (selectedEngine == runtimeStatus.activeEngine()) {
            setStatusInfo(STATUS_SAVED);
            return;
        }

        setStatusInfo("Saved — restart Chat4J to apply");
        RestartRequiredDialog.Choice choice = showWebViewEngineChangePrompt(selectedEngine);
        if (choice == RestartRequiredDialog.Choice.EXIT_NOW) {
            exitAction.run();
            return;
        }
        if (choice == RestartRequiredDialog.Choice.CANCEL) {
            restoreWebViewEngineSelection(engineComboBox, previousValue);
            return;
        }
    }

    private void restoreWebViewEngineSelection(JComboBox<String> engineComboBox, String value) {
        restoringWebViewEngineSelection = true;
        try {
            engineComboBox.setSelectedItem(value);
        } finally {
            restoringWebViewEngineSelection = false;
        }
        currentWebViewEngineSettingValue = value;
        refreshRestartHint(value);
        enqueueSave(
                WebViewSettings.ENGINE_KEY,
                () -> settingsRepo().put(WebViewSettings.ENGINE_KEY, value),
                () -> {
                },
                () -> setStatusInfo(STATUS_SAVED),
                () -> {
                }
        );
    }

    private RestartRequiredDialog.Choice showWebViewEngineChangePrompt(WebViewEngine selectedEngine) {
        return restartPrompt.show(
                this,
                "Chat WebView engine will switch from %s to %s after you reopen Chat4J."
                        .formatted(runtimeStatus.activeEngine().displayName(), selectedEngine.displayName())
        );
    }

    private SettingsValidator<String> engineValidator() {
        Set<String> values = Arrays.stream(WebViewEngine.values())
                .map(WebViewEngine::settingValue)
                .collect(toSet());
        return Validators.oneOf(values, "Invalid chat WebView engine");
    }

    private String[] engineSettingValues() {
        return Arrays.stream(WebViewEngine.values())
                .map(WebViewEngine::settingValue)
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
                    activeEngineIconPath(),
                    null,
                    "%s active (fallback)".formatted(runtimeStatus.activeEngine().displayName()),
                    "Fallback from %s: %s".formatted(
                            runtimeStatus.configuredEngine().displayName(),
                            StringUtils.defaultIfBlank(runtimeStatus.fallbackReason(), "unknown error"))
            );
        }
        if (runtimeStatus.activeEngine() == WebViewEngine.SYSTEM) {
            String mode = StringUtils.defaultIfBlank(runtimeStatus.swingWebViewMode(), "unknown mode");
            return new WebViewHealth(
                    activeEngineIconPath(),
                    null,
                    "%s active".formatted(runtimeStatus.activeEngine().displayName()),
                    "Mode: %s component".formatted(mode)
            );
        }
        if (runtimeStatus.activeEngine() == WebViewEngine.JCEF) {
            String mode = StringUtils.defaultIfBlank(runtimeStatus.jcefMode(), "windowed/native");
            return new WebViewHealth(
                    activeEngineIconPath(),
                    null,
                    "%s active".formatted(runtimeStatus.activeEngine().displayName()),
                    "Mode: %s".formatted(mode)
            );
        }
        return new WebViewHealth(
                activeEngineIconPath(),
                null,
                "%s active".formatted(runtimeStatus.activeEngine().displayName()),
                "Final Swing fallback"
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

    private String activeEngineIconPath() {
        return activeEngineIconPath(runtimeStatus.activeEngine());
    }

    private static String activeEngineIconPath(WebViewEngine engine) {
        return switch (engine) {
            case JEDITOR_PANE -> "/icons/settings/java-original.svg";
            case JCEF -> "/icons/settings/chromium-logo.svg";
            case SYSTEM -> nativeWebViewIconPath();
        };
    }

    private static String selectorEngineIconPath(WebViewEngine engine) {
        return engine == WebViewEngine.SYSTEM
                ? nativeWebViewSelectorIconPath()
                : activeEngineIconPath(engine);
    }

    private static String nativeWebViewIconPath() {
        if (SystemInfo.isMacOS) {
            return "/icons/settings/safari-logo.svg";
        }
        if (SystemInfo.isWindows) {
            return "/icons/settings/microsoft-edge-logo.svg";
        }
        if (SystemInfo.isLinux) {
            return "/icons/settings/webkit-logo.svg";
        }
        return "/icons/settings/cpu.svg";
    }

    private static String nativeWebViewSelectorIconPath() {
        if (SystemInfo.isMacOS) {
            return "/icons/settings/apple-original.svg";
        }
        if (SystemInfo.isWindows) {
            return "/icons/settings/windows11-original.svg";
        }
        if (SystemInfo.isLinux) {
            return "/icons/settings/linux-original.svg";
        }
        return "/icons/settings/cpu.svg";
    }

    private void refreshRestartHint(String configuredValue) {
        WebViewEngine configuredEngine = WebViewEngine.fromSettingValue(configuredValue);
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
        themeCombo.setName("themeComboBox");
        themeCombo.setRenderer(new ThemeListRenderer());

        String savedTheme = readString(KEY_THEME, DEFAULT_THEME);
        if (allThemes.containsKey(savedTheme)) {
            themeCombo.setSelectedItem(savedTheme);
        } else {
            themeCombo.setSelectedItem(DEFAULT_THEME);
            enqueueSave(
                    KEY_THEME,
                    () -> settingsRepo().put(KEY_THEME, DEFAULT_THEME),
                    () -> {
                    },
                    () -> {
                    },
                    () -> {
                    }
            );
            setStatusError("Saved theme was invalid and has been reset to default");
        }

        AtomicBoolean updating = new AtomicBoolean();
        AtomicReference<Object> lastApplied = new AtomicReference<>(themeCombo.getSelectedItem());

        themeCombo.addActionListener(e -> {
            if (updating.get()) {
                return;
            }
            Object selected = themeCombo.getSelectedItem();
            if (!(selected instanceof String name)) {
                return;
            }

            if (name.startsWith("---")) {
                updating.set(true);
                themeCombo.setSelectedItem(lastApplied.get());
                updating.set(false);
                return;
            }

            String className = allThemes.get(name);
            if (className == null || !applyTheme(name, className)) {
                updating.set(true);
                themeCombo.setSelectedItem(lastApplied.get());
                updating.set(false);
                return;
            }
            lastApplied.set(name);
        });

        return themeCombo;
    }

    private JPanel createAccentPanel() {
        JPanel accentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        accentPanel.setOpaque(false);

        ButtonGroup accentGroup = new ButtonGroup();
        String storedAccent = readString(KEY_ACCENT_COLOR, null);
        String savedAccent = isSupportedAccent(storedAccent) ? storedAccent : null;
        if (storedAccent != null && savedAccent == null) {
            enqueueSave(
                    KEY_ACCENT_COLOR,
                    () -> settingsRepo().remove(KEY_ACCENT_COLOR),
                    () -> {
                    },
                    () -> setStatusInfo("Invalid saved accent was reset to default"),
                    () -> {
                    }
            );
        }

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

    private static boolean isSupportedAccent(String savedAccent) {
        return savedAccent == null || Arrays.stream(ACCENT_COLORS)
                .map(option -> option[1])
                .anyMatch(savedAccent::equals);
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
        FontRenderContext context = new FontRenderContext(null, true, true);
        return Arrays.stream(availableFonts)
                .filter(fontFamily -> isMonospacedFontFamily(context, fontFamily))
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

    private static boolean isMonospacedFontFamily(FontRenderContext context, String fontFamily) {
        Font font = new Font(fontFamily, Font.PLAIN, FALLBACK_FONT_SIZE);
        double i = font.getStringBounds("i", context).getWidth();
        double m = font.getStringBounds("m", context).getWidth();
        double w = font.getStringBounds("W", context).getWidth();
        double zero = font.getStringBounds("0", context).getWidth();
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

    void applyAccentSelection(Color color, String hex) {
        Runnable write = hex == null
                ? () -> settingsRepo().remove(KEY_ACCENT_COLOR)
                : () -> settingsRepo().put(KEY_ACCENT_COLOR, hex);
        enqueueSave(
                KEY_ACCENT_COLOR,
                write,
                () -> {
                },
                () -> {
                    Color previousAccent = accentColor;
                    try {
                        accentColor = color;
                        LookAndFeel current = UIManager.getLookAndFeel();
                        FlatLaf.setup(current.getClass().getDeclaredConstructor().newInstance());
                        applyConfiguredFonts();
                        FlatLaf.updateUI();
                        setStatusInfo(STATUS_SAVED);
                    } catch (Exception e) {
                        accentColor = previousAccent;
                        setStatusError("Saved accent color, but failed to apply it");
                    }
                },
                () -> {
                }
        );
    }

    private boolean applyTheme(String name, String className) {
        LookAndFeel previousLookAndFeel = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(className);
            applyConfiguredFonts();
            refreshAllWindows();
        } catch (Exception e) {
            restoreLookAndFeel(previousLookAndFeel);
            setStatusError("Failed to apply theme");
            return false;
        }
        enqueueSave(
                KEY_THEME,
                () -> settingsRepo().put(KEY_THEME, name),
                () -> {
                },
                () -> setStatusInfo(STATUS_SAVED),
                () -> {
                }
        );
        return true;
    }

    private void restoreLookAndFeel(LookAndFeel lookAndFeel) {
        if (lookAndFeel == null) {
            return;
        }
        try {
            UIManager.setLookAndFeel(lookAndFeel);
            applyConfiguredFonts();
            refreshAllWindows();
        } catch (Exception ignored) {
            // The original theme could not be restored; retain the primary apply failure.
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

    private void applyConfiguredFonts() {
        applyAppFont(durableAppFontFamily, durableAppFontSize);
        applyCodeFont(durableCodeFontFamily);
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

    record FontCatalog(String[] appFonts, String[] codeFonts) {
    }

    private record AppearanceMutation(Runnable write, Runnable onDurableSuccess) {
    }

    private record AppearanceSaveRequest(
            Runnable mutation,
            Runnable onDurableSuccess,
            Runnable onCurrentSuccess,
            Runnable onCurrentFailure,
            CompletableFuture<Void> completion
    ) {
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
                WebViewEngine engine = WebViewEngine.fromSettingValue(settingValue);
                label.setText(engine.displayName());
                label.setIcon(loadEngineIcon(engine));
                label.setIconTextGap(8);
            }
            return label;
        }

        private Icon loadEngineIcon(WebViewEngine engine) {
            URL url = AppearancePanel.class.getResource(selectorEngineIconPath(engine));
            return url == null ? null : new FlatSVGIcon(url).derive(18, 18);
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
