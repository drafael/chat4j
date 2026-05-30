package com.github.drafael.chat4j.chat.message;

import ca.weblite.webview.ConsoleMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewComponent;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.FlatArcIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatNordIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubIJTheme;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.LookAndFeel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in SwingWebView spike runner. This class lives in the message package so
 * it can reuse Chat4J's package-internal {@link MessageHtmlRenderer} without
 * making renderer APIs public for a development-only experiment.
 */
public final class SwingWebViewSpikeFrame extends JFrame {
    private static final Path HTML_OUTPUT_DIR = Path.of("target", "swingwebview-spike-pages");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Dimension DEFAULT_FRAME_SIZE = new Dimension(1050, 720);
    private static final Dimension DEFAULT_BROWSER_SIZE = new Dimension(820, 560);
    private static final Set<String> SAFE_EXTERNAL_SCHEMES = Set.of("http", "https", "mailto");
    private static final boolean OPEN_EXTERNAL_LINKS = "true".equalsIgnoreCase(System.getenv("CHAT4J_SWINGWEBVIEW_SPIKE_OPEN_LINKS"));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThemeOption[] THEME_OPTIONS = {
            new ThemeOption("Current Look and Feel", null),
            new ThemeOption("FlatLaf Light", FlatLightLaf.class),
            new ThemeOption("FlatLaf Dark", FlatDarkLaf.class),
            new ThemeOption("FlatLaf IntelliJ", FlatIntelliJLaf.class),
            new ThemeOption("FlatLaf Darcula", FlatDarculaLaf.class),
            new ThemeOption("FlatLaf macOS Light", FlatMacLightLaf.class),
            new ThemeOption("FlatLaf macOS Dark", FlatMacDarkLaf.class),
            new ThemeOption("Arc", FlatArcIJTheme.class),
            new ThemeOption("Dracula", FlatDraculaIJTheme.class),
            new ThemeOption("Nord", FlatNordIJTheme.class),
            new ThemeOption("GitHub", FlatMTGitHubIJTheme.class),
            new ThemeOption("GitHub Dark", FlatMTGitHubDarkIJTheme.class),
            new ThemeOption("Solarized Light", FlatSolarizedLightIJTheme.class),
            new ThemeOption("Solarized Dark", FlatSolarizedDarkIJTheme.class)
    };

    private final JTextArea logArea = new JTextArea(12, 110);
    private final JLabel statusLabel = new JLabel("SwingWebView mode: " + WebViewComponent.resolveDefaultMode());
    private final JButton loadStaticButton = new JButton("Load static smoke page");
    private final JButton loadDataUrlButton = new JButton("Load static as data URL");
    private final JButton loadChatButton = new JButton("Load selected sample");
    private final JButton runAllChatSamplesButton = new JButton("Run all samples");
    private final JButton openDevToolsButton = new JButton("Open DevTools");
    private final JButton reloadInteropButton = new JButton("Reload interop page");
    private final JButton evalInteropButton = new JButton("Eval title + scroll");
    private final JButton focusSwingButton = new JButton("Focus Swing field");
    private final JButton runStressButton = new JButton("Run lifecycle stress");
    private final JCheckBox recreateDuringStress = new JCheckBox("Dispose/recreate each iteration");
    private final JSpinner stressIterations = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    private final JSpinner stressDelayMillis = new JSpinner(new SpinnerNumberModel(600, 100, 5_000, 100));
    private final JTextField focusProbeField = new JTextField("Swing focus probe", 28);
    private final JComboBox<ThemeOption> themeCombo = new JComboBox<>(THEME_OPTIONS);
    private final JPanel staticHost = browserPanel();
    private final JPanel chatHost = browserPanel();
    private final JPanel interopHost = browserPanel();
    private final JPanel stressHost = browserPanel();
    private final List<AbstractButton> sampleButtons = new ArrayList<>();
    private final AtomicBoolean stressRunning = new AtomicBoolean(false);
    private final MessageHtmlRenderer renderer = new MessageHtmlRenderer();

    private WebViewComponent staticWebView;
    private WebViewComponent chatWebView;
    private WebViewComponent interopWebView;
    private WebViewComponent stressWebView;
    private ChatSample selectedChatSample = ChatSample.USER_BADGES;

    private SwingWebViewSpikeFrame() {
        super("Chat4J SwingWebView Spike");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setMinimumSize(new Dimension(700, 460));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndClose();
            }
        });
        wireActions();
        setSize(DEFAULT_FRAME_SIZE);
        setLocationRelativeTo(null);
        appendLog("Spike ready. Default mode=%s, openExternalLinks=%s".formatted(
                WebViewComponent.resolveDefaultMode(), OPEN_EXTERNAL_LINKS));
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> throwable.printStackTrace());
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        SwingUtilities.invokeLater(() -> new SwingWebViewSpikeFrame().setVisible(true));
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        JLabel title = new JLabel("SwingWebView spike: static smoke, Chat4J HTML, JS interop, lifecycle stress");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.WEST);
        panel.add(buildThemePanel(), BorderLayout.EAST);
        updateStatusLabel();
        return panel;
    }

    private JPanel buildThemePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setOpaque(false);
        themeCombo.setFocusable(false);
        themeCombo.setToolTipText("Switch FlatLaf themes and reload loaded spike pages for manual renderer checks.");
        panel.add(new JLabel("Theme"));
        panel.add(themeCombo);
        panel.add(statusLabel);
        return panel;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Static Smoke", buildStaticTab());
        tabs.addTab("Chat HTML", buildChatTab());
        tabs.addTab("JS / Focus", buildInteropTab());
        tabs.addTab("Lifecycle Stress", buildStressTab());
        return tabs;
    }

    private JPanel buildStaticTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(loadStaticButton);
        controls.add(loadDataUrlButton);
        controls.add(new JLabel("Compare file URL loading with a self-contained data URL."));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(staticHost, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChatTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        panel.add(buildChatSampleControls(), BorderLayout.WEST);
        panel.add(chatHost, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChatSampleControls() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 8));
        controls.add(new JLabel("Sample"));
        controls.add(Box.createVerticalStrut(6));
        ButtonGroup group = new ButtonGroup();
        for (ChatSample sample : ChatSample.values()) {
            JRadioButton button = new JRadioButton(sample.displayName(), sample == selectedChatSample);
            button.setFocusable(false);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.addActionListener(e -> selectedChatSample = sample);
            group.add(button);
            sampleButtons.add(button);
            controls.add(button);
        }
        controls.add(Box.createVerticalStrut(8));
        loadChatButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        runAllChatSamplesButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        openDevToolsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(loadChatButton);
        controls.add(Box.createVerticalStrut(6));
        controls.add(runAllChatSamplesButton);
        controls.add(Box.createVerticalStrut(6));
        controls.add(openDevToolsButton);
        return controls;
    }

    private JPanel buildInteropTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(reloadInteropButton);
        toolbar.add(evalInteropButton);
        toolbar.add(focusSwingButton);
        toolbar.addSeparator();
        toolbar.add(focusProbeField);
        toolbar.addSeparator();
        toolbar.add(new JLabel("Manual checks: typing, Cmd/Ctrl+A/C, Tab traversal, link callback logging."));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(interopHost, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStressTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Iterations"));
        controls.add(stressIterations);
        controls.add(new JLabel("Delay ms"));
        controls.add(stressDelayMillis);
        controls.add(recreateDuringStress);
        controls.add(runStressButton);
        controls.add(new JLabel("Default reloads one WebView; recreate mode probes native cleanup."));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(stressHost, BorderLayout.CENTER);
        return panel;
    }

    private JScrollPane buildLogPanel() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return new JScrollPane(logArea);
    }

    private JPanel browserPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180)));
        panel.setPreferredSize(DEFAULT_BROWSER_SIZE);
        panel.setMinimumSize(new Dimension(320, 220));
        panel.add(new JLabel("WebView not loaded", JLabel.CENTER), BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        loadStaticButton.addActionListener(e -> loadStaticSmoke(false));
        loadDataUrlButton.addActionListener(e -> loadStaticSmoke(true));
        loadChatButton.addActionListener(e -> loadSelectedChatSample());
        runAllChatSamplesButton.addActionListener(e -> runAllChatSamples());
        openDevToolsButton.addActionListener(e -> openDevTools(chatWebView));
        reloadInteropButton.addActionListener(e -> loadInteropPage());
        evalInteropButton.addActionListener(e -> evalInteropState());
        focusSwingButton.addActionListener(e -> focusProbeField.requestFocusInWindow());
        runStressButton.addActionListener(e -> runLifecycleStress());
        themeCombo.addActionListener(e -> applySelectedTheme());
    }

    private void applySelectedTheme() {
        ThemeOption option = (ThemeOption) themeCombo.getSelectedItem();
        if (option == null || option.lookAndFeelClass() == null) {
            updateStatusLabel();
            appendLog("Keeping current Look and Feel: %s".formatted(UIManager.getLookAndFeel().getName()));
            return;
        }

        try {
            LookAndFeel lookAndFeel = option.lookAndFeelClass().getDeclaredConstructor().newInstance();
            UIManager.setLookAndFeel(lookAndFeel);
            SwingUtilities.updateComponentTreeUI(this);
            updateStatusLabel();
            reloadLoadedPagesAfterThemeChange();
            appendLog("Applied theme: %s (dark=%s)".formatted(option.name(), FlatLaf.isLafDark()));
        } catch (Exception e) {
            appendLog("Failed to apply theme %s: %s".formatted(option.name(), e));
        }
    }

    private void updateStatusLabel() {
        statusLabel.setText("SwingWebView mode: %s · dark=%s".formatted(WebViewComponent.resolveDefaultMode(), FlatLaf.isLafDark()));
    }

    private void reloadLoadedPagesAfterThemeChange() {
        if (staticWebView != null) {
            loadStaticSmoke(false);
        }
        if (chatWebView != null) {
            loadChatSample(chatWebView, selectedChatSample, false);
        }
        if (interopWebView != null) {
            loadInteropPage();
        }
    }

    private WebViewComponent ensureWebView(JPanel host, WebViewComponent existing, String name) {
        if (existing != null) {
            return existing;
        }
        appendLog("Creating %s WebView (%s)".formatted(name, WebViewComponent.resolveDefaultMode()));
        WebViewComponent webView = WebViewComponent.create();
        webView.setDebug(true);
        webView.setDialogHandler(null);
        webView.addConsoleListener(this::appendConsoleMessage);
        webView.addJavascriptCallback("chat4jLog", new WebView.JavascriptCallback() {
            @Override
            public void run(String arg) {
                appendLog("JS callback chat4jLog <- " + arg);
            }
        });
        webView.addJavascriptCallback("chat4jLinkClick", new WebView.JavascriptCallback() {
            @Override
            public void run(String arg) {
                handleLinkCallback(arg);
            }
        });
        webView.addOnBeforeLoad(linkInterceptorScript());
        webView.setPreferredSize(DEFAULT_BROWSER_SIZE);
        host.removeAll();
        host.add(webView, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
        return webView;
    }

    private void loadStaticSmoke(boolean dataUrl) {
        staticWebView = ensureWebView(staticHost, staticWebView, "static");
        String html = wrapHtml("Static smoke", """
                <h1>Static smoke</h1>
                <p>If this renders, SwingWebView is embedded in Swing and can display generated HTML.</p>
                <p><a href='https://example.com'>Safe external link</a> · <a href='javascript:alert(1)'>unsafe javascript link</a></p>
                <pre><code>function hello() {\n  console.log('hello from static smoke');\n}</code></pre>
                <p><button onclick="console.log('console log from button'); chat4jLog('button clicked')">Console + Java callback</button></p>
                <p contenteditable='true' style='border:1px solid #bbb;padding:8px'>Editable area: test typing and copy/select shortcuts here.</p>
                """);
        loadHtml(staticWebView, html, "static-smoke", dataUrl);
    }

    private void loadSelectedChatSample() {
        chatWebView = ensureWebView(chatHost, chatWebView, "chat");
        loadChatSample(chatWebView, selectedChatSample, false);
    }

    private void runAllChatSamples() {
        chatWebView = ensureWebView(chatHost, chatWebView, "chat");
        List<ChatSample> samples = List.of(ChatSample.values());
        appendLog("Running %d Chat4J HTML samples".formatted(samples.size()));
        runSampleAt(samples, 0);
    }

    private void runSampleAt(List<ChatSample> samples, int index) {
        if (index >= samples.size()) {
            appendLog("Finished all Chat4J HTML samples");
            return;
        }
        ChatSample sample = samples.get(index);
        loadChatSample(chatWebView, sample, false);
        new javax.swing.Timer(700, e -> {
            ((javax.swing.Timer) e.getSource()).stop();
            runSampleAt(samples, index + 1);
        }).start();
    }

    private void loadChatSample(WebViewComponent webView, ChatSample sample, boolean dataUrl) {
        boolean dark = sample.dark() || FlatLaf.isLafDark();
        String rendered = renderer.render(sample.role(), sample.renderMode(), sample.text(), dark);
        String html = wrapHtml("Chat sample: " + sample.displayName(), """
                <h2>%s</h2>
                <p class='meta'>role=%s · renderMode=%s · dark=%s · theme=%s</p>
                <section class='message-host'>%s</section>
                """.formatted(sample.displayName(), sample.role(), sample.renderMode(), dark, UIManager.getLookAndFeel().getName(), rendered));
        verifyFragment(html, sample.requiredFragment(), sample.displayName());
        loadHtml(webView, html, sample.fileSlug(), dataUrl);
    }

    private void loadInteropPage() {
        interopWebView = ensureWebView(interopHost, interopWebView, "interop");
        String html = wrapHtml("JS / Focus", """
                <h1>JS / Focus checks</h1>
                <p><input id='title' value='SwingWebView spike title'> <button onclick="document.title=document.getElementById('title').value">Set title</button></p>
                <p><button onclick="chat4jLog(JSON.stringify({kind:'button', time:new Date().toISOString()}))">Call Java callback</button></p>
                <p><button onclick="console.warn('console.warn from page')">console.warn</button></p>
                <p><a href='https://example.com/manual'>Safe external link callback</a> · <a href='file:///tmp/nope'>Blocked file callback</a></p>
                <textarea style='width:96%%;height:160px'>Try typing here, then Cmd/Ctrl+A and Cmd/Ctrl+C.</textarea>
                <div style='height:700px'></div><p id='bottom'>Bottom of page.</p>
                """);
        loadHtml(interopWebView, html, "interop", false);
    }

    private void evalInteropState() {
        if (interopWebView == null) {
            appendLog("Interop WebView not loaded yet");
            return;
        }
        interopWebView.evalAsync("return {title: document.title, scrollY: window.scrollY, bodyText: document.body.innerText.length};")
                .thenAccept(json -> appendLog("evalAsync result: " + json))
                .exceptionally(error -> {
                    appendLog("evalAsync failed: " + error);
                    return null;
                });
    }

    private void runLifecycleStress() {
        if (!stressRunning.compareAndSet(false, true)) {
            appendLog("Lifecycle stress already running");
            return;
        }
        runStressButton.setEnabled(false);
        int iterations = ((Number) stressIterations.getValue()).intValue();
        int delay = ((Number) stressDelayMillis.getValue()).intValue();
        boolean recreate = recreateDuringStress.isSelected();
        appendLog("Starting lifecycle stress: iterations=%d, delay=%dms, recreate=%s".formatted(iterations, delay, recreate));
        runStressIteration(1, iterations, delay, recreate);
    }

    private void runStressIteration(int current, int total, int delay, boolean recreate) {
        if (current > total) {
            appendLog("Lifecycle stress complete");
            stressRunning.set(false);
            runStressButton.setEnabled(true);
            return;
        }
        if (recreate && stressWebView != null) {
            replaceWebView(stressHost, stressWebView);
            stressWebView = null;
        }
        stressWebView = ensureWebView(stressHost, stressWebView, "stress");
        String html = wrapHtml("Stress " + current, """
                <h1>Lifecycle stress %d / %d</h1>
                <p>Timestamp: %s</p>
                <p><button onclick="console.log('stress %d')">console</button></p>
                """.formatted(current, total, LocalTime.now(), current));
        loadHtml(stressWebView, html, "stress-%03d".formatted(current), false);
        new javax.swing.Timer(delay, e -> {
            ((javax.swing.Timer) e.getSource()).stop();
            runStressIteration(current + 1, total, delay, recreate);
        }).start();
    }

    private void replaceWebView(JPanel host, WebViewComponent webView) {
        host.remove(webView);
        try {
            webView.dispose();
        } catch (RuntimeException ex) {
            appendLog("dispose failed: " + ex);
        }
        host.add(new JLabel("WebView disposed", JLabel.CENTER), BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
    }

    private void openDevTools(WebViewComponent webView) {
        if (webView == null) {
            appendLog("No WebView loaded for DevTools");
            return;
        }
        appendLog("openDevTools() -> " + webView.openDevTools());
    }

    private void loadHtml(WebViewComponent webView, String html, String slug, boolean dataUrl) {
        try {
            Files.createDirectories(HTML_OUTPUT_DIR);
            Path file = HTML_OUTPUT_DIR.resolve(slug + ".html");
            Files.writeString(file, html, StandardCharsets.UTF_8);
            String url = dataUrl ? dataUrl(html) : file.toUri().toString();
            webView.setUrl(url);
            appendLog("Loaded %s via %s".formatted(file, dataUrl ? "data URL" : "file URL"));
        } catch (IOException ex) {
            appendLog("Failed to write/load HTML: " + ex);
        }
    }

    private String wrapHtml(String title, String body) {
        Color fallbackBackground = FlatLaf.isLafDark() ? new Color(43, 43, 43) : Color.WHITE;
        Color fallbackForeground = FlatLaf.isLafDark() ? new Color(220, 220, 220) : new Color(29, 29, 31);
        Color background = uiColor("Panel.background", fallbackBackground);
        Color contentBackground = uiColor("TextField.background", background);
        Color foreground = uiColor("Label.foreground", fallbackForeground);
        Color mutedForeground = uiColor("Label.disabledForeground", blend(foreground, background, 0.45f));
        Color border = uiColor("Component.borderColor", blend(foreground, background, 0.70f));
        Color link = uiColor("Component.linkColor", uiColor("Component.accentColor", new Color(37, 99, 235)));

        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset='utf-8'>
                  <title>%s</title>
                  <style>
                    :root { color-scheme: %s; }
                    html, body { background: %s; color: %s; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; margin: 0; padding: 20px; line-height: 1.45; }
                    a { color: %s; }
                    pre { overflow-x: auto; }
                    code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
                    .meta { color: %s; font-size: 12px; }
                    .message-host { background: %s; border: 1px solid %s; border-radius: 12px; padding: 14px; overflow-wrap: anywhere; }
                  </style>
                </head>
                <body>%s</body>
                </html>
                """.formatted(
                escapeHtml(title),
                FlatLaf.isLafDark() ? "dark" : "light",
                cssColor(background),
                cssColor(foreground),
                cssColor(link),
                cssColor(mutedForeground),
                cssColor(contentBackground),
                cssColor(border),
                body);
    }

    private Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private Color blend(Color primary, Color secondary, float secondaryWeight) {
        float weight = Math.max(0f, Math.min(1f, secondaryWeight));
        float primaryWeight = 1f - weight;
        int red = Math.round(primary.getRed() * primaryWeight + secondary.getRed() * weight);
        int green = Math.round(primary.getGreen() * primaryWeight + secondary.getGreen() * weight);
        int blue = Math.round(primary.getBlue() * primaryWeight + secondary.getBlue() * weight);
        return new Color(red, green, blue);
    }

    private String cssColor(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private String dataUrl(String html) {
        return "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
    }

    private String linkInterceptorScript() {
        return """
                (function(){
                  if (window.__chat4jLinkInterceptorInstalled) return;
                  window.__chat4jLinkInterceptorInstalled = true;
                  document.addEventListener('click', function(event) {
                    var node = event.target;
                    while (node && node.tagName !== 'A') node = node.parentElement;
                    if (!node || !node.href) return;
                    event.preventDefault();
                    if (window.chat4jLinkClick) window.chat4jLinkClick(node.href);
                    return false;
                  }, true);
                })();
                """;
    }

    private void handleLinkCallback(String arg) {
        String url = unwrapCallbackArg(arg);
        appendLog("link click <- " + url);
        if (!isSafeExternalLink(url)) {
            appendLog("blocked unsafe link: " + url);
            return;
        }
        if (!OPEN_EXTERNAL_LINKS) {
            appendLog("safe link not opened because CHAT4J_SWINGWEBVIEW_SPIKE_OPEN_LINKS is not true");
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            appendLog("failed to open external link: " + ex);
        }
    }

    private boolean isSafeExternalLink(String url) {
        try {
            String scheme = new URI(url).getScheme();
            return scheme != null && SAFE_EXTERNAL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private String unwrapCallbackArg(String raw) {
        if (raw == null) {
            return "";
        }

        String value = raw.trim();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            JsonNode args = root.path("args");
            if (args.isArray() && !args.isEmpty()) {
                return args.get(0).asText("");
            }
            if (root.isArray() && !root.isEmpty()) {
                return root.get(0).asText("");
            }
            if (root.isTextual()) {
                return root.asText();
            }
        } catch (IOException ignored) {
            // Fall through to legacy string unwrapping for older callback payloads.
        }

        if (value.startsWith("[") && value.endsWith("]") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\/", "/").replace("\\\"", "\"");
    }

    private void verifyFragment(String html, String fragment, String sample) {
        if (!html.contains(fragment)) {
            appendLog("Validation warning: sample %s missing expected fragment: %s".formatted(sample, fragment));
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void appendConsoleMessage(ConsoleMessage message) {
        appendLog("console: " + message);
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[%s] %s%n".formatted(LocalTime.now().format(LOG_TIME_FORMAT), message));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void shutdownAndClose() {
        appendLog("Shutting down SwingWebView spike");
        WebViewComponent[] webViews = {staticWebView, chatWebView, interopWebView, stressWebView};
        for (WebViewComponent webView : webViews) {
            if (webView != null) {
                try {
                    webView.dispose();
                } catch (RuntimeException ex) {
                    appendLog("dispose failed during shutdown: " + ex);
                }
            }
        }
        dispose();
    }

    private record ThemeOption(String name, Class<? extends LookAndFeel> lookAndFeelClass) {
        @Override
        public String toString() {
            return name;
        }
    }

    private enum ChatSample {
        USER_BADGES("User badges", Role.USER, RenderMode.PREVIEW, false, """
                [SKILL] java-coder
                [FALLBACK] provider fallback active
                Please explain this snippet and keep the wrapping stable:

                ```java
                System.out.println("hello");
                ```
                """, "badge skill"),
        ASSISTANT_PREVIEW("Assistant preview", Role.ASSISTANT, RenderMode.PREVIEW, false, """
                ## Markdown preview

                - Item one
                - Item two with a [safe link](https://example.com)

                | Col | Value |
                | --- | --- |
                | `code` | **bold** |

                ```java
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("wrapped code block");
                    }
                }
                ```
                """, "md-table"),
        ASSISTANT_MARKDOWN_RAW("Assistant raw markdown", Role.ASSISTANT, RenderMode.MARKDOWN, false, """
                # Raw Markdown mode

                    indentation must survive

                ```text
                fenced content <must> be escaped
                ```
                """, "&lt;must&gt;"),
        DARK_PREVIEW("Dark preview", Role.ASSISTANT, RenderMode.PREVIEW, true, """
                ### Dark palette sample

                This sample validates dark-mode CSS from the Chat4J renderer.

                ```bash
                printf 'dark mode'
                ```
                """, "dark mode"),
        LONG_WRAPPING("Long wrapping", Role.USER, RenderMode.PREVIEW, false, """
                Please wrap this long token without breaking the layout:

                aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

                And preserve code-ish content: `some.really.long.package.Name.withManySegments.andNoNaturalBreakpoints()`.
                """, "aaaaaaaaaaaaaaaa");

        private final String displayName;
        private final Role role;
        private final RenderMode renderMode;
        private final boolean dark;
        private final String text;
        private final String requiredFragment;

        ChatSample(String displayName, Role role, RenderMode renderMode, boolean dark, String text, String requiredFragment) {
            this.displayName = displayName;
            this.role = role;
            this.renderMode = renderMode;
            this.dark = dark;
            this.text = text;
            this.requiredFragment = requiredFragment;
        }

        String displayName() {
            return displayName;
        }

        Role role() {
            return role;
        }

        RenderMode renderMode() {
            return renderMode;
        }

        boolean dark() {
            return dark;
        }

        String text() {
            return text;
        }

        String requiredFragment() {
            return requiredFragment;
        }

        String fileSlug() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
