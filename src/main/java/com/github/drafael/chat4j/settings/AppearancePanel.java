package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.*;
import com.formdev.flatlaf.intellijthemes.*;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.*;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.ColorFunctions;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class AppearancePanel extends AbstractSettingsPanel {

    private static final String KEY_THEME = "theme";
    private static final String KEY_ACCENT_COLOR = "accentColor";
    private static final String KEY_APP_FONT = "app.font";
    private static final String KEY_CODE_FONT = "code.font";

    private static final String DEFAULT_THEME = "GitHub";
    private static final String DEFAULT_APP_FONT = "System Default";
    private static final String DEFAULT_CODE_FONT = "Monospaced";

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
            accentColor = (hex != null && !hex.isEmpty()) ? Color.decode(hex) : null;
        } catch (Exception ignored) {
            accentColor = null;
        }
    }

    public static void applySavedFonts(SettingsRepo settings) {
        try {
            String savedCodeFont = settings.get(KEY_CODE_FONT, DEFAULT_CODE_FONT);
            applyCodeFont(savedCodeFont);
        } catch (Exception ignored) {
            applyCodeFont(DEFAULT_CODE_FONT);
        }
    }

    /** Look up the LaF class name for a saved theme display name, or null if not found. */
    public static String classNameForTheme(String displayName) {
        String cls = CORE_THEMES.get(displayName);
        if (cls != null) {
            return cls;
        }
        cls = INTELLIJ_THEMES.get(displayName);
        if (cls != null) {
            return cls;
        }
        return MATERIAL_THEMES.get(displayName);
    }

    public static Map<String, Map<String, String>> groupedThemes() {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        grouped.put("Core Themes", Collections.unmodifiableMap(new LinkedHashMap<>(CORE_THEMES)));
        grouped.put("IntelliJ Themes", Collections.unmodifiableMap(new LinkedHashMap<>(INTELLIJ_THEMES)));
        grouped.put("Material Themes", Collections.unmodifiableMap(new LinkedHashMap<>(MATERIAL_THEMES)));
        return Collections.unmodifiableMap(grouped);
    }

    public AppearancePanel(SettingsRepo settingsRepo) {
        super(settingsRepo);

        JPanel form = createFormPanel("Appearance");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JComboBox<Object> themeCombo = createThemeSelector();
        addRow(form, gbc, row++, "Theme", themeCombo);

        JPanel accentPanel = createAccentPanel();
        addRow(form, gbc, row++, "Accent color", accentPanel);

        String[] fontFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();

        JComboBox<String> appFont = createFontSelector(withPreferredFont(DEFAULT_APP_FONT, fontFamilies));
        addRow(form, gbc, row++, "App font", appFont);
        bindComboBox(
            appFont,
            KEY_APP_FONT,
            DEFAULT_APP_FONT,
            Validators.oneOf(
                new LinkedHashSet<>(Arrays.asList(withPreferredFont(DEFAULT_APP_FONT, fontFamilies))),
                "Invalid app font option"
            ),
            null
        );

        JComboBox<String> codeFont = createFontSelector(withPreferredFont(DEFAULT_CODE_FONT, fontFamilies));
        addRow(form, gbc, row++, "Code font", codeFont);
        bindComboBox(
            codeFont,
            KEY_CODE_FONT,
            DEFAULT_CODE_FONT,
            Validators.oneOf(
                new LinkedHashSet<>(Arrays.asList(withPreferredFont(DEFAULT_CODE_FONT, fontFamilies))),
                "Invalid code font option"
            ),
            value -> {
                    applyCodeFont(value);
                    refreshAllWindows();
                });

        addVerticalSpacer(form, gbc, row);
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

    private String[] withPreferredFont(String preferred, String[] availableFonts) {
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(preferred);
        ordered.addAll(Arrays.asList(availableFonts));
        return ordered.toArray(String[]::new);
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
        } catch (Exception ex) {
            try {
                FlatMTGitHubIJTheme.setup();
                refreshAllWindows();
                setStatusError("Failed to apply theme, reverted to GitHub");
            } catch (Exception ignored) {
                setStatusError("Failed to apply theme");
            }
        }
    }

    private void refreshAllWindows() {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private void applyConfiguredFontsFromSettings() {
        String savedCodeFont = readString(KEY_CODE_FONT, DEFAULT_CODE_FONT);
        applyCodeFont(savedCodeFont);
    }

    private static void applyCodeFont(String fontFamily) {
        Font base = UIManager.getFont("monospaced.font");
        if (base == null) {
            base = UIManager.getFont("TextArea.font");
        }

        int size = base != null ? base.getSize() : 13;
        Font codeFont = new Font(fontFamily, Font.PLAIN, size);
        UIManager.put("monospaced.font", new FontUIResource(codeFont));
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
                label.setFont(Fonts.of(Font.BOLD, 11));
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
                label.setEnabled(false);
                label.setBorder(new EmptyBorder(4, 6, 2, 6));
            } else {
                label.setFont(Fonts.of(Font.PLAIN, 13));
                label.setBorder(new EmptyBorder(2, 12, 2, 6));
            }
            return label;
        }
    }
}
