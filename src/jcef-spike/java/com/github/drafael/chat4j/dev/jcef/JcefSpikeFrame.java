package com.github.drafael.chat4j.dev.jcef;

import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.chat.message.MessageHtmlRenderer;
import com.github.drafael.chat4j.provider.api.Role;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JcefSpikeFrame extends JFrame {

    private static final Path JCEF_INSTALL_DIR = Path.of("target", "jcef-spike-bundle");
    private static final Path HTML_OUTPUT_DIR = Path.of("target", "jcef-spike-pages");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final boolean USE_OSR = "true".equalsIgnoreCase(System.getenv("CHAT4J_JCEF_SPIKE_OSR"));
    private static final boolean ENABLE_DEVTOOLS = "true".equalsIgnoreCase(System.getenv("CHAT4J_JCEF_SPIKE_DEVTOOLS"));
    private static final int DEVTOOLS_PORT = 9222;
    private static final Dimension DEFAULT_FRAME_SIZE = new Dimension(1000, 700);
    private static final Dimension DEFAULT_BROWSER_SIZE = new Dimension(800, 600);
    private static final String DIRECT_RESOURCE_ORIGIN = "https://chat4j-spike.local";
    private static final Set<String> ALLOWED_EXTERNAL_LINK_SCHEMES = Set.of("http", "https", "mailto");
    private static final Set<String> BLOCKED_LINK_SCHEMES = Set.of("javascript", "file", "data");

    private final JTextArea logArea = new JTextArea(12, 110);
    private final JScrollPane logScrollPane = buildLogPanel();
    private final JLabel statusLabel = new JLabel("JCEF not initialized");
    private final JButton showLogButton = new JButton("Show log");
    private final JButton openDevToolsButton = new JButton("Open DevTools");
    private final JButton loadStaticButton = new JButton("Load static smoke page");
    private final JButton loadDirectResourceButton = new JButton("Load direct resource page");
    private final JButton loadChatButton = new JButton("Load selected sample");
    private final JButton runAllChatSamplesButton = new JButton("Run all samples");
    private final JButton runStressButton = new JButton("Run lifecycle stress");
    private final JButton loadFocusButton = new JButton("Load focus/shortcut page");
    private final JButton focusBrowserButton = new JButton("Focus browser");
    private final JButton focusSwingButton = new JButton("Focus Swing field");
    private final JButton reloadFocusButton = new JButton("Reload focus page");
    private final List<AbstractButton> chatSampleButtons = new ArrayList<>();
    private final JSpinner stressIterations = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    private final JSpinner stressDelayMillis = new JSpinner(new SpinnerNumberModel(750, 100, 5_000, 100));
    private final JCheckBox recreateBrowsersDuringStress = new JCheckBox("Dispose/recreate each iteration");
    private final JPanel staticBrowserPanel = browserPanel();
    private final JPanel chatBrowserPanel = browserPanel();
    private final JPanel stressBrowserPanel = browserPanel();
    private final JPanel focusBrowserPanel = browserPanel();
    private final JTextField focusProbeField = new JTextField("Swing focus probe: click here, then reload/focus the browser.", 42);
    private final Map<String, String> directResourceHtmlByUrl = new ConcurrentHashMap<>();
    private final List<CefBrowser> openBrowsers = new ArrayList<>();
    private final AtomicBoolean stressRunning = new AtomicBoolean(false);

    private CefApp cefApp;
    private CefClient cefClient;
    private CefMessageRouter messageRouter;
    private CefBrowser staticBrowser;
    private CefBrowser chatBrowser;
    private CefBrowser stressBrowser;
    private CefBrowser focusBrowser;
    private JFrame logFrame;
    private ChatSample selectedChatSample = ChatSample.USER_BADGES;

    private JcefSpikeFrame() {
        super("Chat4J JCEF Spike");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setMinimumSize(new Dimension(600, 400));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdownAndClose();
            }
        });

        setSpikeControlsEnabled(false);
        wireActions();
        installBrowserResizeWorkarounds();
        setSize(DEFAULT_FRAME_SIZE);
        setLocationRelativeTo(null);
        initializeJcefAsync();
    }

    public static void main(String[] args) {
        installUncaughtExceptionLogging();
        installMacOsAbruptTerminationHandlers();
        configureHeavyweightPopups();
        SwingUtilities.invokeLater(() -> new JcefSpikeFrame().setVisible(true));
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        JLabel title = new JLabel("JCEF spike: static smoke, Chat4J HTML, lifecycle stress (%s rendering)".formatted(
                USE_OSR ? "OSR" : "windowed"
        ));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        statusPanel.setOpaque(false);
        statusPanel.add(showLogButton);
        statusPanel.add(openDevToolsButton);
        statusPanel.add(statusLabel);
        panel.add(title, BorderLayout.WEST);
        panel.add(statusPanel, BorderLayout.EAST);
        return panel;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Static Smoke", buildStaticSmokeTab());
        tabs.addTab("Chat HTML", buildChatHtmlTab());
        tabs.addTab("Lifecycle Stress", buildLifecycleStressTab());
        tabs.addTab("Focus/Shortcuts", buildFocusShortcutTab());
        return tabs;
    }

    private JPanel buildStaticSmokeTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(loadStaticButton);
        controls.add(loadDirectResourceButton);
        controls.add(new JLabel("Compares file URL loading with an intercepted in-memory resource."));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(staticBrowserPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChatHtmlTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        panel.add(buildChatSampleControls(), BorderLayout.WEST);
        panel.add(chatBrowserPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildChatSampleControls() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 8));
        controls.add(new JLabel("Sample"));
        controls.add(Box.createVerticalStrut(6));
        addChatSampleButtons(controls);
        controls.add(Box.createVerticalStrut(8));
        loadChatButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        runAllChatSamplesButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(loadChatButton);
        controls.add(Box.createVerticalStrut(6));
        controls.add(runAllChatSamplesButton);
        return controls;
    }

    private void addChatSampleButtons(JPanel controls) {
        ButtonGroup sampleGroup = new ButtonGroup();
        for (ChatSample sample : ChatSample.values()) {
            JRadioButton sampleButton = new JRadioButton(sample.displayName(), sample == selectedChatSample);
            sampleButton.setFocusable(false);
            sampleButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            sampleButton.addActionListener(e -> selectedChatSample = sample);
            sampleGroup.add(sampleButton);
            chatSampleButtons.add(sampleButton);
            controls.add(sampleButton);
        }
    }

    private JPanel buildLifecycleStressTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        recreateBrowsersDuringStress.setToolTipText("Native crash probe: repeatedly closes and recreates JCEF browsers instead of reusing one browser.");
        controls.add(new JLabel("Iterations"));
        controls.add(stressIterations);
        controls.add(new JLabel("Delay ms"));
        controls.add(stressDelayMillis);
        controls.add(recreateBrowsersDuringStress);
        controls.add(runStressButton);
        controls.add(new JLabel("Default mode reloads one browser; recreate mode probes native disposal stability."));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(stressBrowserPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildFocusShortcutTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(loadFocusButton);
        controls.add(reloadFocusButton);
        controls.add(focusBrowserButton);
        controls.add(focusSwingButton);
        controls.add(focusProbeField);
        controls.add(new JLabel("Manual checks: Cmd/Ctrl+A, Cmd/Ctrl+C, Tab traversal, reload focus retention."));

        panel.add(controls, BorderLayout.NORTH);
        panel.add(focusBrowserPanel, BorderLayout.CENTER);
        return panel;
    }

    private JScrollPane buildLogPanel() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        return new JScrollPane(logArea);
    }

    private JPanel browserPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180)));
        panel.setPreferredSize(DEFAULT_BROWSER_SIZE);
        panel.setMinimumSize(new Dimension(320, 220));
        panel.add(new JLabel("Browser not loaded", SwingConstants.CENTER), BorderLayout.CENTER);
        return panel;
    }

    private void wireActions() {
        showLogButton.addActionListener(e -> showLogWindow());
        openDevToolsButton.addActionListener(e -> openDevToolsForVisibleBrowser());
        loadStaticButton.addActionListener(e -> loadStaticSmoke());
        loadDirectResourceButton.addActionListener(e -> loadDirectResourceSmoke());
        loadChatButton.addActionListener(e -> loadSelectedChatSample());
        runAllChatSamplesButton.addActionListener(e -> runAllChatSamplesAsync());
        runStressButton.addActionListener(e -> runLifecycleStressAsync());
        loadFocusButton.addActionListener(e -> loadFocusShortcutPage());
        reloadFocusButton.addActionListener(e -> reloadFocusShortcutPage());
        focusBrowserButton.addActionListener(e -> focusBrowserContent());
        focusSwingButton.addActionListener(e -> focusProbeField.requestFocusInWindow());
    }

    private void installBrowserResizeWorkarounds() {
        installBrowserResizeWorkaround(staticBrowserPanel, () -> staticBrowser);
        installBrowserResizeWorkaround(chatBrowserPanel, () -> chatBrowser);
        installBrowserResizeWorkaround(stressBrowserPanel, () -> stressBrowser);
        installBrowserResizeWorkaround(focusBrowserPanel, () -> focusBrowser);
    }

    private void installBrowserResizeWorkaround(JPanel panel, BrowserSupplier browserSupplier) {
        Timer repairTimer = new Timer(180, e -> repairBrowserLayout(panel, browserSupplier.get()));
        repairTimer.setRepeats(false);
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                repairTimer.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                repairTimer.restart();
            }
        });
    }

    private void showLogWindow() {
        if (logFrame == null) {
            logFrame = new JFrame("Chat4J JCEF Spike Log");
            logFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            logFrame.add(logScrollPane, BorderLayout.CENTER);
            logFrame.setSize(960, 320);
            logFrame.setLocationRelativeTo(this);
        }
        logFrame.setVisible(true);
        logFrame.toFront();
    }

    private void initializeJcefAsync() {
        log("Initializing JCEF. First run may download native artifacts into %s".formatted(JCEF_INSTALL_DIR));
        Thread.startVirtualThread(() -> {
            try {
                Files.createDirectories(JCEF_INSTALL_DIR);
                Files.createDirectories(HTML_OUTPUT_DIR);

                CefAppBuilder builder = new CefAppBuilder();
                builder.setInstallDir(JCEF_INSTALL_DIR.toFile());
                builder.getCefSettings().windowless_rendering_enabled = USE_OSR;
                if (ENABLE_DEVTOOLS) {
                    builder.getCefSettings().remote_debugging_port = DEVTOOLS_PORT;
                }
                addSpikeJcefArgs(builder);
                builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                    @Override
                    public void stateHasChanged(CefApp.CefAppState state) {
                        log("CefApp state changed: %s".formatted(state));
                    }
                });

                CefApp initializedApp = builder.build();
                installMacOsSignalHandlers();
                CefClient initializedClient = initializedApp.createClient();
                CefMessageRouter initializedMessageRouter = createSpikeMessageRouter();
                initializedClient.addMessageRouter(initializedMessageRouter);
                initializedClient.addRequestHandler(new SpikeRequestHandler());
                SwingUtilities.invokeLater(() -> {
                    cefApp = initializedApp;
                    cefClient = initializedClient;
                    messageRouter = initializedMessageRouter;
                    log("JCEF initialized. Rendering mode: %s. CEF version: %s".formatted(
                            USE_OSR ? "OSR/windowless" : "windowed/native",
                            initializedApp.getVersion()
                    ));
                    if (ENABLE_DEVTOOLS) {
                        log("DevTools enabled. Remote debugging port: %d".formatted(DEVTOOLS_PORT));
                    } else {
                        log("DevTools disabled. Set CHAT4J_JCEF_SPIKE_DEVTOOLS=true to enable the Open DevTools button and remote debugging port.");
                    }
                    if (!hasRequiredAwtModuleAccess()) {
                        statusLabel.setText("JCEF initialized; JVM module access is missing");
                        log("JCEF initialized, but this JVM is missing macOS AWT module access required to attach browser components.");
                        log("Run the spike with: mvn -Pjcef-spike compile exec:exec");
                        return;
                    }

                    statusLabel.setText("JCEF initialized");
                    setSpikeControlsEnabled(true);
                    openDevToolsButton.setEnabled(ENABLE_DEVTOOLS);
                    loadStaticSmoke();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("JCEF initialization failed");
                    log("JCEF initialization failed: %s".formatted(e));
                    log(stackTrace(e));
                });
            }
        });
    }

    private void loadStaticSmoke() {
        runWithInitializedJcef(() -> {
            try {
                Path htmlPath = writeHtmlFile("static-smoke.html", enhanceHtmlForSpikeNavigation(staticSmokeHtml()));
                staticBrowser = loadInBrowser(staticBrowserPanel, staticBrowser, htmlPath.toUri().toString());
                log("Loaded static smoke page: %s".formatted(htmlPath));
            } catch (IOException e) {
                log("Failed to load static smoke page: %s".formatted(e));
            }
        });
    }

    private void loadDirectResourceSmoke() {
        runWithInitializedJcef(() -> {
            String url = registerDirectResource("direct-smoke.html", enhanceHtmlForSpikeNavigation(directResourceSmokeHtml()));
            staticBrowser = loadInBrowser(staticBrowserPanel, staticBrowser, url);
            log("Loaded direct in-memory resource smoke page: %s".formatted(url));
        });
    }

    private void loadSelectedChatSample() {
        runWithInitializedJcef(() -> {
            ChatSample sample = selectedChatSample;

            try {
                Path htmlPath = writeHtmlFile(sample.fileName(), enhanceHtmlForSpikeNavigation(sample.html()));
                chatBrowser = loadInBrowser(chatBrowserPanel, chatBrowser, htmlPath.toUri().toString());
                log("Loaded Chat4J HTML sample %s from %s".formatted(sample.displayName(), htmlPath));
            } catch (IOException e) {
                log("Failed to load Chat4J HTML sample: %s".formatted(e));
            }
        });
    }

    private void runAllChatSamplesAsync() {
        runWithInitializedJcef(() -> {
            loadChatButton.setEnabled(false);
            runAllChatSamplesButton.setEnabled(false);
            log("Running %d Chat4J renderer samples".formatted(ChatSample.values().length));
            Thread.startVirtualThread(() -> {
                for (ChatSample sample : ChatSample.values()) {
                    try {
                        String html = sample.html();
                        List<String> missingFragments = sample.missingExpectedFragments(html);
                        if (!missingFragments.isEmpty()) {
                            log("Sample %s missing expected fragments: %s".formatted(sample.displayName(), missingFragments));
                        }
                        Path htmlPath = writeHtmlFile(sample.fileName(), enhanceHtmlForSpikeNavigation(html));
                        SwingUtilities.invokeAndWait(() -> {
                            if (cefClient == null) {
                                return;
                            }
                            chatBrowser = loadInBrowser(chatBrowserPanel, chatBrowser, htmlPath.toUri().toString());
                            selectedChatSample = sample;
                            selectChatSampleButton(sample);
                            log("Rendered Chat4J sample %s from %s".formatted(sample.displayName(), htmlPath));
                        });
                        Thread.sleep(650);
                    } catch (Exception e) {
                        log("Chat4J sample %s failed: %s".formatted(sample.displayName(), e));
                        break;
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    loadChatButton.setEnabled(cefClient != null);
                    runAllChatSamplesButton.setEnabled(cefClient != null);
                    log("Finished Chat4J renderer samples");
                });
            });
        });
    }

    private void selectChatSampleButton(ChatSample sample) {
        chatSampleButtons.stream()
                .filter(button -> sample.displayName().equals(button.getText()))
                .findFirst()
                .ifPresent(button -> button.setSelected(true));
    }

    private void loadFocusShortcutPage() {
        runWithInitializedJcef(() -> {
            try {
                Path htmlPath = writeHtmlFile("focus-shortcuts.html", enhanceHtmlForSpikeNavigation(focusShortcutHtml()));
                focusBrowser = loadInBrowser(focusBrowserPanel, focusBrowser, htmlPath.toUri().toString());
                log("Loaded focus/shortcut validation page: %s".formatted(htmlPath));
                log("Manual focus checks: focus Swing field, reload page, verify browser does not steal focus; click browser and test Cmd/Ctrl+A/C.");
            } catch (IOException e) {
                log("Failed to load focus/shortcut page: %s".formatted(e));
            }
        });
    }

    private void reloadFocusShortcutPage() {
        loadFocusShortcutPage();
        SwingUtilities.invokeLater(() -> log("Focus owner after focus page reload: %s".formatted(describeFocusOwner())));
    }

    private void focusBrowserContent() {
        if (focusBrowser == null) {
            log("Focus browser is not loaded yet.");
            return;
        }

        focusBrowser.getUIComponent().requestFocusInWindow();
        focusBrowser.setFocus(true);
        log("Requested browser focus. Focus owner now: %s".formatted(describeFocusOwner()));
    }

    private void runLifecycleStressAsync() {
        if (!stressRunning.compareAndSet(false, true)) {
            log("Lifecycle stress is already running.");
            return;
        }

        runStressButton.setEnabled(false);
        int iterations = ((Number) stressIterations.getValue()).intValue();
        int delayMillis = ((Number) stressDelayMillis.getValue()).intValue();
        boolean recreateBrowsers = recreateBrowsersDuringStress.isSelected();
        log("Starting lifecycle stress with %d iterations, %dms delay, mode=%s".formatted(
                iterations,
                delayMillis,
                recreateBrowsers ? "dispose/recreate" : "reuse-browser"
        ));
        if (recreateBrowsers) {
            log("Dispose/recreate mode intentionally probes native cleanup and may crash JCEF on some platforms.");
        }
        Thread.startVirtualThread(() -> {
            for (int i = 1; i <= iterations && stressRunning.get(); i++) {
                int iteration = i;
                try {
                    Path htmlPath = writeHtmlFile("stress-%03d.html".formatted(iteration), stressHtml(iteration, iterations));
                    SwingUtilities.invokeAndWait(() -> {
                        if (!stressRunning.get() || cefClient == null) {
                            return;
                        }
                        String url = htmlPath.toUri().toString();
                        stressBrowser = recreateBrowsers
                                ? replaceBrowser(stressBrowserPanel, stressBrowser, url)
                                : loadInBrowser(stressBrowserPanel, stressBrowser, url);
                        log("Stress iteration %d/%d loaded".formatted(iteration, iterations));
                    });
                    Thread.sleep(delayMillis);
                } catch (Exception e) {
                    log("Stress iteration %d failed: %s".formatted(iteration, e));
                    break;
                }
            }

            SwingUtilities.invokeLater(() -> {
                if (cefClient == null) {
                    stressRunning.set(false);
                    return;
                }
                stressRunning.set(false);
                runStressButton.setEnabled(true);
                log("Lifecycle stress finished");
            });
        });
    }

    private CefBrowser loadInBrowser(JPanel targetPanel, CefBrowser currentBrowser, String url) {
        if (currentBrowser != null) {
            currentBrowser.loadURL(url);
            return currentBrowser;
        }

        return attachNewBrowser(targetPanel, url);
    }

    private CefBrowser replaceBrowser(JPanel targetPanel, CefBrowser previousBrowser, String url) {
        closeBrowser(previousBrowser);
        return attachNewBrowser(targetPanel, url);
    }

    private CefBrowser attachNewBrowser(JPanel targetPanel, String url) {
        CefBrowser browser = cefClient.createBrowser(url, USE_OSR, false);
        openBrowsers.add(browser);
        try {
            Component browserComponent = browser.getUIComponent();
            browserComponent.setPreferredSize(DEFAULT_BROWSER_SIZE);
            browserComponent.setMinimumSize(new Dimension(320, 220));
            targetPanel.removeAll();
            targetPanel.add(browserComponent, BorderLayout.CENTER);
            targetPanel.revalidate();
            targetPanel.repaint();
            repairBrowserLayout(targetPanel, browser);
            return browser;
        } catch (Throwable t) {
            openBrowsers.remove(browser);
            closeBrowser(browser);
            targetPanel.removeAll();
            targetPanel.add(new JLabel("Failed to attach browser component. See log output.", SwingConstants.CENTER), BorderLayout.CENTER);
            targetPanel.revalidate();
            targetPanel.repaint();
            log("Failed to attach JCEF browser component: %s".formatted(t));
            log(stackTrace(t));
            return null;
        }
    }

    private void repairBrowserLayout(JPanel panel, CefBrowser browser) {
        if (browser == null || !panel.isShowing()) {
            return;
        }

        Component uiComponent = browser.getUIComponent();
        Dimension size = panel.getSize();
        uiComponent.setBounds(0, 0, Math.max(0, size.width), Math.max(0, size.height));
        uiComponent.invalidate();
        panel.revalidate();
        panel.repaint();

        if (!USE_OSR && isMacOS()) {
            // Windowed JCEF is a native heavyweight on macOS. Toggling visibility after resize
            // nudges the native child window back to the Swing component bounds.
            uiComponent.setVisible(false);
            SwingUtilities.invokeLater(() -> {
                uiComponent.setVisible(true);
                uiComponent.setBounds(0, 0, Math.max(0, panel.getWidth()), Math.max(0, panel.getHeight()));
                panel.revalidate();
                panel.repaint();
            });
        }
    }

    private void closeBrowser(CefBrowser browser) {
        if (browser == null) {
            return;
        }

        openBrowsers.remove(browser);
        try {
            browser.stopLoad();
            browser.setCloseAllowed();
            Component uiComponent = browser.getUIComponent();
            Container parent = uiComponent.getParent();
            if (parent != null) {
                parent.remove(uiComponent);
                parent.revalidate();
                parent.repaint();
            }
            browser.close(true);
        } catch (Exception e) {
            log("Browser close failed: %s".formatted(e));
        }
    }

    private void shutdownAndClose() {
        stressRunning.set(false);
        setSpikeControlsEnabled(false);
        log("Shutting down JCEF spike");

        if (isMacOS()) {
            log("macOS spike shutdown: skipping explicit CefApp.dispose() because jcefmaven 143.0.14 traps during native shutdown in local unsigned launches.");
            log("Terminating the forked spike JVM abruptly instead; production integration must revisit macOS CEF shutdown semantics.");
            if (logFrame != null) {
                logFrame.dispose();
            }
            terminateMacOsSpikeJvm("window close");
            return;
        }

        new ArrayList<>(openBrowsers).forEach(this::closeBrowser);
        openBrowsers.clear();

        if (cefClient != null) {
            try {
                if (messageRouter != null) {
                    cefClient.removeMessageRouter(messageRouter);
                    messageRouter.dispose();
                    messageRouter = null;
                }
                cefClient.dispose();
            } catch (Exception e) {
                log("CefClient dispose failed: %s".formatted(e));
            }
            cefClient = null;
        }

        if (cefApp != null) {
            try {
                cefApp.dispose();
            } catch (Exception e) {
                log("CefApp dispose failed: %s".formatted(e));
            }
            cefApp = null;
        }

        if (logFrame != null) {
            logFrame.dispose();
        }
        dispose();
    }

    private void setSpikeControlsEnabled(boolean enabled) {
        loadStaticButton.setEnabled(enabled);
        loadDirectResourceButton.setEnabled(enabled);
        loadChatButton.setEnabled(enabled);
        runAllChatSamplesButton.setEnabled(enabled);
        chatSampleButtons.forEach(button -> button.setEnabled(enabled));
        stressIterations.setEnabled(enabled);
        stressDelayMillis.setEnabled(enabled);
        recreateBrowsersDuringStress.setEnabled(enabled);
        runStressButton.setEnabled(enabled && !stressRunning.get());
        loadFocusButton.setEnabled(enabled);
        reloadFocusButton.setEnabled(enabled);
        focusBrowserButton.setEnabled(enabled);
        focusSwingButton.setEnabled(enabled);
        focusProbeField.setEnabled(enabled);
        openDevToolsButton.setEnabled(enabled && ENABLE_DEVTOOLS);
    }

    private CefMessageRouter createSpikeMessageRouter() {
        CefMessageRouter.CefMessageRouterConfig config = new CefMessageRouter.CefMessageRouterConfig(
                "chat4jSpikeQuery",
                "chat4jSpikeQueryCancel"
        );
        CefMessageRouter router = CefMessageRouter.create(config);
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request, boolean persistent, CefQueryCallback callback) {
                log("JS bridge query received: %s".formatted(request));
                if (request != null && request.startsWith("open-link:")) {
                    String url = request.substring("open-link:".length());
                    handleNavigation(url, true, "js-bridge");
                    callback.success("Handled link: %s".formatted(url));
                    return true;
                }

                callback.success("Chat4J spike received: %s".formatted(request));
                return true;
            }
        }, true);
        return router;
    }

    private void runWithInitializedJcef(Runnable action) {
        if (cefClient == null) {
            log("JCEF is not initialized yet.");
            return;
        }
        if (!hasRequiredAwtModuleAccess()) {
            log("Cannot attach JCEF browser components because required AWT module access is missing.");
            log("Run the spike with: mvn -Pjcef-spike compile exec:exec");
            return;
        }
        action.run();
    }

    private void openDevToolsForVisibleBrowser() {
        if (!ENABLE_DEVTOOLS) {
            log("DevTools are disabled. Relaunch with CHAT4J_JCEF_SPIKE_DEVTOOLS=true.");
            return;
        }

        CefBrowser browser = visibleBrowser();
        if (browser == null) {
            log("No visible browser is available for DevTools.");
            return;
        }

        browser.openDevTools();
        log("Opened DevTools for visible browser. Remote debugging port: %d".formatted(DEVTOOLS_PORT));
    }

    private CefBrowser visibleBrowser() {
        if (focusBrowserPanel.isShowing()) {
            return focusBrowser;
        }
        if (stressBrowserPanel.isShowing()) {
            return stressBrowser;
        }
        if (chatBrowserPanel.isShowing()) {
            return chatBrowser;
        }
        if (staticBrowserPanel.isShowing()) {
            return staticBrowser;
        }
        return null;
    }

    private String registerDirectResource(String filename, String html) {
        String url = "%s/%s?id=%s".formatted(DIRECT_RESOURCE_ORIGIN, filename, UUID.randomUUID());
        directResourceHtmlByUrl.put(url, html);
        return url;
    }

    private String describeFocusOwner() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focusOwner == null ? "<none>" : "%s[%s]".formatted(focusOwner.getClass().getSimpleName(), focusOwner.getName());
    }

    private boolean handleNavigation(String url, boolean userGesture, String source) {
        if (!userGesture) {
            return false;
        }
        if (url == null || url.isBlank()) {
            log("Blocked blank user navigation from %s".formatted(source));
            return true;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            log("Blocked malformed user navigation from %s: %s".formatted(source, url));
            return true;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (isDirectResourceUrl(uri)) {
            log("Blocked user navigation to internal direct-resource URL: %s".formatted(url));
            return true;
        }
        if (BLOCKED_LINK_SCHEMES.contains(scheme)) {
            log("Blocked unsafe user navigation scheme '%s': %s".formatted(scheme, url));
            return true;
        }
        if (ALLOWED_EXTERNAL_LINK_SCHEMES.contains(scheme)) {
            openExternalLink(uri);
            return true;
        }

        log("Blocked unsupported user navigation scheme '%s': %s".formatted(scheme, url));
        return true;
    }

    private static boolean isDirectResourceUrl(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "chat4j-spike.local".equalsIgnoreCase(uri.getHost());
    }

    private void openExternalLink(URI uri) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log("External browse unsupported; blocked link: %s".formatted(uri));
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                Desktop.getDesktop().browse(uri);
                log("Opened external link and blocked embedded navigation: %s".formatted(uri));
            } catch (IOException e) {
                log("Failed to open external link %s: %s".formatted(uri, e));
            }
        });
    }

    private static boolean hasRequiredAwtModuleAccess() {
        if (!isMacOS()) {
            return true;
        }

        Module desktopModule = ModuleLayer.boot().findModule("java.desktop").orElse(null);
        if (desktopModule == null) {
            return false;
        }

        Module spikeModule = JcefSpikeFrame.class.getModule();
        return hasPackageAccess(desktopModule, "sun.awt", spikeModule)
                && hasPackageAccess(desktopModule, "sun.lwawt", spikeModule)
                && hasPackageAccess(desktopModule, "sun.lwawt.macosx", spikeModule);
    }

    private static boolean hasPackageAccess(Module sourceModule, String packageName, Module targetModule) {
        return sourceModule.isExported(packageName, targetModule) || sourceModule.isOpen(packageName, targetModule);
    }

    private static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private Path writeHtmlFile(String filename, String html) throws IOException {
        Files.createDirectories(HTML_OUTPUT_DIR);
        Path htmlPath = HTML_OUTPUT_DIR.resolve(filename);
        Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
        return htmlPath;
    }

    private static void addSpikeJcefArgs(CefAppBuilder builder) {
        builder.addJcefArgs("--disable-gpu");
        builder.addJcefArgs("--disable-background-networking");
        builder.addJcefArgs("--disable-component-update");
        builder.addJcefArgs("--disable-sync");
        builder.addJcefArgs("--disable-extensions");
        builder.addJcefArgs("--disable-notifications");
        builder.addJcefArgs("--disable-default-apps");
        builder.addJcefArgs("--disable-in-process-stack-traces");
        builder.addJcefArgs("--use-mock-keychain");
        builder.addJcefArgs("--no-first-run");
        builder.addJcefArgs("--no-default-browser-check");
        builder.addJcefArgs("--disable-features=AutofillServerCommunication,MediaRouter,OptimizationHints,PushMessaging,SpareRendererForSitePerProcess");
    }

    private static void installUncaughtExceptionLogging() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.printf("Uncaught exception on %s: %s%n", thread.getName(), throwable);
            throwable.printStackTrace(System.err);
        });
    }

    private static void configureHeavyweightPopups() {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
    }

    private static void installMacOsAbruptTerminationHandlers() {
        if (!isMacOS()) {
            return;
        }

        installMacOsSignalHandlers();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> terminateMacOsSpikeJvm("JVM shutdown hook"),
                "jcef-spike-macos-shutdown"
        ));
    }

    private static void installMacOsSignalHandlers() {
        if (!isMacOS()) {
            return;
        }

        installMacOsSignalHandler("INT");
        installMacOsSignalHandler("TERM");
        installMacOsSignalHandler("HUP");
    }

    private static void installMacOsSignalHandler(String signalName) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance(signalName);
            Object handler = Proxy.newProxyInstance(
                    signalHandlerClass.getClassLoader(),
                    new Class<?>[]{signalHandlerClass},
                    (proxy, method, args) -> {
                        terminateMacOsSpikeJvm(String.valueOf(args[0]));
                        return null;
                    }
            );
            signalClass.getMethod("handle", signalClass, signalHandlerClass).invoke(null, signal, handler);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            System.err.printf("JCEF spike could not install %s handler: %s%n", signalName, e.getMessage());
        }
    }

    private static void terminateMacOsSpikeJvm(String reason) {
        System.err.printf("JCEF spike macOS abrupt termination after %s; skipping native CEF shutdown.%n", reason);
        System.err.flush();
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static String staticSmokeHtml() {
        return wrapSpikePage(
                "Static Smoke",
                """
                        <h1>JCEF Static Smoke</h1>
                        <p>This page verifies that Chromium renders inside the Chat4J Swing runtime.</p>
                        <p><a href="https://example.com">External link sample</a> · <a href="https://example.com" target="_blank">target=_blank sample</a></p>
                        <p>Navigation-policy probes: <a href="mailto:security@example.com">mailto</a> · <a href="javascript:alert('blocked')">javascript</a> · <a href="file:///etc/hosts">file</a> · <a href="data:text/html,blocked">data</a> · <a href="relative-page.html">relative</a></p>
                        <div class="card">
                          <h2>Styled content</h2>
                          <p>Rounded cards, colors, and code blocks should render with normal browser fidelity.</p>
                          <pre><code>public final class Smoke {
                            public String status() { return "ok"; }
                        }</code></pre>
                        </div>
                        """
        );
    }

    private static String directResourceSmokeHtml() {
        return wrapSpikePage(
                "Direct Resource Smoke",
                """
                        <h1>Direct Resource Smoke</h1>
                        <p>This page is served from an in-memory CEF resource handler instead of a temporary file URL.</p>
                        <div class="card">
                          <h2>Why this matters</h2>
                          <p>IntelliJ avoids plain data URLs for richer HTML loading and serves controlled resources through handlers.</p>
                          <p><a href="https://example.com">External link should open outside JCEF</a></p>
                        </div>
                        """
        );
    }

    private static String stressHtml(int iteration, int total) {
        return wrapSpikePage(
                "Lifecycle Stress %d".formatted(iteration),
                """
                        <h1>Lifecycle Stress</h1>
                        <p>Iteration <strong>%d</strong> of <strong>%d</strong>.</p>
                        <p>If this keeps updating without crashes or hangs, browser replacement is basically working.</p>
                        """.formatted(iteration, total)
        );
    }

    private static String focusShortcutHtml() {
        return wrapSpikePage(
                "Focus and Shortcuts",
                """
                        <h1>Focus and Shortcut Validation</h1>
                        <p id="selectable">Click this browser page, then test Cmd/Ctrl+A and Cmd/Ctrl+C. The text should be selectable/copyable without breaking Swing focus behavior.</p>
                        <label for="browser-input">Browser input:</label>
                        <input id="browser-input" value="Browser input focus probe" style="width: 420px; padding: 8px;">
                        <p><button onclick="sendBridgeQuery()">Send JS bridge query</button> <span id="bridge-result">not sent</span></p>
                        <div class="card">
                          <h2>Manual checks</h2>
                          <ol>
                            <li>Focus the Swing text field above the browser, then reload this page. The browser should not steal focus unexpectedly.</li>
                            <li>Click inside the browser input and type text.</li>
                            <li>Use Tab traversal between browser content and Swing controls.</li>
                            <li>Use Cmd/Ctrl+A and Cmd/Ctrl+C in browser content.</li>
                            <li>Click the JS bridge button and verify the result text updates and the Java log receives the query.</li>
                          </ol>
                        </div>
                        <script>
                          function sendBridgeQuery() {
                            window.chat4jSpikeQuery({
                              request: 'focus-page:' + new Date().toISOString(),
                              persistent: false,
                              onSuccess: function(response) { document.getElementById('bridge-result').textContent = response; },
                              onFailure: function(code, message) { document.getElementById('bridge-result').textContent = code + ': ' + message; }
                            });
                          }
                        </script>
                        """
        );
    }

    private static String wrapSpikePage(String title, String body) {
        return """
                <!doctype html>
                <html>
                <head>
                  <meta charset="UTF-8">
                  <title>%s</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', sans-serif; margin: 28px; line-height: 1.5; color: #1d1d1f; background: #f7f7f8; }
                    h1 { margin-top: 0; }
                    a { color: #0a66c2; cursor: pointer; }
                    .card { background: white; border: 1px solid #dadadd; border-radius: 14px; padding: 16px 18px; box-shadow: 0 8px 24px rgba(0,0,0,0.06); }
                    pre { background: #20242a; color: #f6f8fa; padding: 12px; border-radius: 10px; overflow-x: auto; }
                    code { font-family: Menlo, Monaco, Consolas, monospace; }
                  </style>
                </head>
                <body>%s</body>
                </html>
                """.formatted(escapeHtml(title), body);
    }

    private static String enhanceHtmlForSpikeNavigation(String html) {
        String script = """
                <script>
                  (function() {
                    if (window.__chat4jSpikeLinksInstalled) {
                      return;
                    }
                    window.__chat4jSpikeLinksInstalled = true;
                    document.addEventListener('click', function(event) {
                      const link = event.target.closest && event.target.closest('a[href]');
                      if (!link) {
                        return;
                      }
                      event.preventDefault();
                      event.stopPropagation();
                      if (!window.chat4jSpikeQuery) {
                        console.warn('chat4jSpikeQuery is not available for', link.href);
                        return;
                      }
                      window.chat4jSpikeQuery({
                        request: 'open-link:' + link.href,
                        persistent: false,
                        onSuccess: function(response) { console.log(response); },
                        onFailure: function(code, message) { console.warn('link query failed', code, message); }
                      });
                    }, true);
                  })();
                </script>
                """;
        int bodyEnd = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        return bodyEnd >= 0
                ? html.substring(0, bodyEnd) + script + html.substring(bodyEnd)
                : html + script;
    }

    private void log(String message) {
        Runnable append = () -> {
            String line = "[%s] %s%n".formatted(LocalTime.now().format(LOG_TIME_FORMAT), message);
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
            System.out.print(line);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    private static String stackTrace(Throwable exception) {
        StringBuilder builder = new StringBuilder();
        builder.append(exception).append('\n');
        for (StackTraceElement element : exception.getStackTrace()) {
            builder.append("    at ").append(element).append('\n');
        }
        return builder.toString();
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private final class SpikeRequestHandler extends CefRequestHandlerAdapter {
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean user_gesture, boolean is_redirect) {
            return handleNavigation(request.getURL(), user_gesture, "onBeforeBrowse");
        }

        @Override
        public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String target_url, boolean user_gesture) {
            return handleNavigation(target_url, user_gesture, "onOpenURLFromTab");
        }

        @Override
        public CefResourceRequestHandler getResourceRequestHandler(
                CefBrowser browser,
                CefFrame frame,
                CefRequest request,
                boolean isNavigation,
                boolean isDownload,
                String requestInitiator,
                BoolRef disableDefaultHandling
        ) {
            String html = directResourceHtmlByUrl.get(request.getURL());
            return html == null ? null : new DirectHtmlResourceRequestHandler(html);
        }
    }

    private static final class DirectHtmlResourceRequestHandler extends CefResourceRequestHandlerAdapter {
        private final String html;

        private DirectHtmlResourceRequestHandler(String html) {
            this.html = html;
        }

        @Override
        public DirectHtmlResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new DirectHtmlResourceHandler(html);
        }
    }

    private static final class DirectHtmlResourceHandler extends CefResourceHandlerAdapter {
        private final byte[] bytes;
        private int offset;

        private DirectHtmlResourceHandler(String html) {
            bytes = html.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean open(CefRequest request, BoolRef handleRequest, org.cef.callback.CefCallback callback) {
            handleRequest.set(true);
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(200);
            response.setStatusText("OK");
            response.setMimeType("text/html");
            response.setHeaderByName("Cache-Control", "no-store", true);
            response.setHeaderByName("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;", true);
            responseLength.set(bytes.length);
        }

        @Override
        public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            int remaining = bytes.length - offset;
            if (remaining <= 0) {
                bytesRead.set(0);
                return false;
            }

            int count = Math.min(bytesToRead, remaining);
            System.arraycopy(bytes, offset, dataOut, 0, count);
            offset += count;
            bytesRead.set(count);
            return true;
        }
    }

    private interface BrowserSupplier {
        CefBrowser get();
    }

    private enum ChatSample {
        USER_BADGES("User badges", List.of("SKILL", "FALLBACK", "brainstorm")) {
            @Override
            String html() {
                return render(
                        Role.USER,
                        RenderMode.PREVIEW,
                        "[SKILL] brainstorm\n[FALLBACK] Files use text references\nBuild me a concise JCEF rollout plan.",
                        false
                );
            }
        },
        USER_ESCAPING("User escaping", List.of("&lt;b&gt;not bold&lt;/b&gt;", "&amp;", "&lt;script&gt;")) {
            @Override
            String html() {
                return render(
                        Role.USER,
                        RenderMode.PREVIEW,
                        "Please render this literally: <b>not bold</b> & <script>alert('x')</script>",
                        false
                );
            }
        },
        ASSISTANT_PREVIEW("Assistant preview", List.of("<table>", "language-java", "External link")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        """
                                ## JCEF rendering sample

                                - Markdown list item
                                - [External link](https://example.com)

                                | Engine | Status |
                                | --- | --- |
                                | Swing | Current |
                                | JCEF | Spike |

                                ```java
                                String engine = "jcef";
                                ```
                                """,
                        false
                );
            }
        },
        ASSISTANT_PREVIEW_DARK("Assistant preview dark", List.of("JCEF dark mode", "<blockquote>", "language-json")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        """
                                # JCEF dark mode

                                > This sample uses the renderer's dark palette.

                                ```json
                                { "engine": "jcef", "theme": "dark" }
                                ```
                                """,
                        true
                );
            }
        },
        ASSISTANT_MARKDOWN("Assistant markdown/raw", List.of("&lt;script&gt;", "raw **markdown** text")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.MARKDOWN,
                        "<script>alert('escaped')</script>\nraw **markdown** text",
                        false
                );
            }
        },
        ASSISTANT_RICH_MARKDOWN("Assistant rich markdown", List.of("Architecture checklist", "<ol>", "<table>", "✓")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        """
                                ## Architecture checklist

                                1. Keep Swing fallback available.
                                2. Reuse browser instances for content updates.
                                3. Validate shutdown on signed macOS builds.

                                | Check | Result |
                                | --- | --- |
                                | Unicode | ✓ café 漢字 🚀 |
                                | HTML escaping | `<div>` |

                                ```bash
                                mvn -Pjcef-spike compile exec:exec
                                ```
                                """,
                        false
                );
            }
        },
        ASSISTANT_LONG_RESPONSE("Assistant long response", List.of("Long response sample", "section-25", "section-40")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        longMarkdownSample(),
                        false
                );
            }
        },
        ASSISTANT_EDGE_CASES("Assistant edge cases", List.of("Mixed content", "mailto:", "javascript:")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        """
                                ## Mixed content

                                Safe link: [email](mailto:security@example.com)

                                Unsafe-looking link text should be visible for navigation-policy review: [do not click](javascript:alert('blocked'))

                                Inline HTML should stay controlled by the markdown renderer: <span>inline span</span>
                                """,
                        false
                );
            }
        },
        NAVIGATION_POLICY("Navigation policy", List.of("Navigation policy", "target", "data:text/html")) {
            @Override
            String html() {
                return render(
                        Role.ASSISTANT,
                        RenderMode.PREVIEW,
                        """
                                ## Navigation policy

                                These links validate that JCEF message content does not navigate in-place:

                                - [https external](https://example.com)
                                - [https target blank](https://example.com){target="_blank"}
                                - [mailto safe](mailto:security@example.com)
                                - [javascript blocked](javascript:alert('blocked'))
                                - [file blocked](file:///etc/hosts)
                                - [data blocked](data:text/html,blocked)
                                - [relative blocked](relative-page.html)
                                """,
                        false
                );
            }
        };

        private final String displayName;
        private final List<String> expectedFragments;

        ChatSample(String displayName, List<String> expectedFragments) {
            this.displayName = displayName;
            this.expectedFragments = expectedFragments;
        }

        abstract String html();

        String displayName() {
            return displayName;
        }

        String fileName() {
            return "%s.html".formatted(name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }

        List<String> missingExpectedFragments(String html) {
            return expectedFragments.stream()
                    .filter(fragment -> !html.contains(fragment))
                    .toList();
        }

        @Override
        public String toString() {
            return displayName;
        }

        private static String render(Role role, RenderMode renderMode, String text, boolean isDark) {
            return new MessageHtmlRenderer().render(role, renderMode, text, isDark);
        }

        private static String longMarkdownSample() {
            StringBuilder builder = new StringBuilder("# Long response sample\n\n");
            for (int i = 1; i <= 40; i++) {
                builder.append("## section-").append(i).append("\n\n")
                        .append("This paragraph checks sustained layout, scrolling, wrapping, and repaint behavior in JCEF. ")
                        .append("It intentionally repeats text to create a taller rendered document.\n\n");
            }
            return builder.toString();
        }
    }
}
