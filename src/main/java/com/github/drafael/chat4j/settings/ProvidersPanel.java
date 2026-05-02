package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.chat.impl.CodexCliChatCompletionClient;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class ProvidersPanel extends AbstractSettingsPanel {

    private static final CopilotAuthResolver COPILOT_AUTH_RESOLVER = new CopilotAuthResolver();
    private static final CodexAuthResolver CODEX_AUTH_RESOLVER = new CodexAuthResolver();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final NumberFormat USD_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DateTimeFormatter DIAGNOSTICS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String CHAT4J_OAUTH_SOURCE = "Chat4J OAuth";
    private static final int DETAIL_LABEL_COLUMN_WIDTH = 156;
    private static final int DETAIL_COLUMN_GAP = 12;

    private final CardLayout detailCardLayout = new CardLayout();
    private final JPanel detailPanel = new JPanel(detailCardLayout);

    private static final Map<String, ProviderInfo> PROVIDERS = new LinkedHashMap<>();
    private static final Map<String, String> API_KEY_PORTAL_URLS = Map.ofEntries(
            Map.entry("Anthropic", "https://console.anthropic.com/settings/keys"),
            Map.entry("OpenAI", "https://platform.openai.com/api-keys"),
            Map.entry("Perplexity", "https://www.perplexity.ai/settings/api"),
            Map.entry("Google AI", "https://aistudio.google.com/app/apikey"),
            Map.entry("OpenRouter", "https://openrouter.ai/keys"),
            Map.entry("Groq", "https://console.groq.com/keys"),
            Map.entry("DeepSeek", "https://platform.deepseek.com/api_keys"),
            Map.entry("Mistral", "https://console.mistral.ai/api-keys"),
            Map.entry("xAI", "https://docs.x.ai/docs/overview")
    );
    private static final Map<String, LocalProviderHelp> LOCAL_PROVIDER_HELP = Map.ofEntries(
            Map.entry("LM Studio", new LocalProviderHelp(
                    "Open LM Studio, load a model, and start the local server.",
                    "https://lmstudio.ai"
            )),
            Map.entry("Ollama", new LocalProviderHelp(
                    "Start Ollama locally (for example: ollama serve).",
                    "https://ollama.com/download"
            ))
    );
    private static final Map<String, String> PROVIDER_ICON_PATHS = Map.ofEntries(
            Map.entry("Anthropic", "/icons/providers/anthropic.svg"),
            Map.entry("OpenAI", "/icons/providers/openai.svg"),
            Map.entry("Perplexity", "/icons/providers/perplexity.svg"),
            Map.entry("OpenAI Codex", "/icons/providers/codex.svg"),
            Map.entry("GitHub Copilot", "/icons/providers/githubcopilot.svg"),
            Map.entry("Google AI", "/icons/providers/google.svg"),
            Map.entry("OpenRouter", "/icons/providers/openrouter.svg"),
            Map.entry("Groq", "/icons/providers/groq.svg"),
            Map.entry("DeepSeek", "/icons/providers/deepseek.svg"),
            Map.entry("Mistral", "/icons/providers/mistral.svg"),
            Map.entry("xAI", "/icons/providers/xai.svg"),
            Map.entry("LM Studio", "/icons/providers/lmstudio.svg"),
            Map.entry("Ollama", "/icons/providers/ollama.svg")
    );
    private static final int PROVIDER_ICON_GLYPH_SIZE = 20;
    private static final int PROVIDER_ICON_PADDING = 2;
    private static final int PROVIDER_ICON_SIZE = PROVIDER_ICON_GLYPH_SIZE + PROVIDER_ICON_PADDING * 2;
    private static final int STATUS_DOT_SIZE = 5;
    private static final Duration AUTH_AVAILABILITY_TTL = Duration.ofSeconds(15);
    private static final Map<String, Icon> PROVIDER_BASE_ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<ProviderStatusIconKey, Icon> PROVIDER_STATUS_ICON_CACHE = new ConcurrentHashMap<>();

    private final Map<String, CopilotAuthAvailabilitySnapshot> copilotAuthAvailabilityByProvider = new ConcurrentHashMap<>();
    private final Map<String, CodexAuthAvailabilitySnapshot> codexAuthAvailabilityByProvider = new ConcurrentHashMap<>();
    private final JList<String> providerList;

    static {
        PROVIDERS.put("Anthropic", ProviderInfo.envVar("ANTHROPIC_API_KEY", "https://api.anthropic.com"));
        PROVIDERS.put("OpenAI", ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"));
        PROVIDERS.put("Perplexity", ProviderInfo.envVar("PERPLEXITY_API_KEY", "https://api.perplexity.ai"));
        PROVIDERS.put("OpenAI Codex", ProviderInfo.codexOAuth("https://api.openai.com/v1"));
        PROVIDERS.put("GitHub Copilot", ProviderInfo.copilotOAuth("https://api.githubcopilot.com"));
        PROVIDERS.put("Google AI", ProviderInfo.envVar("GEMINI_API_KEY|GOOGLEAI_API_KEY|GOOGLE_AI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai"));
        PROVIDERS.put("OpenRouter", ProviderInfo.envVar("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"));
        PROVIDERS.put("Groq", ProviderInfo.envVar("GROQ_API_KEY", "https://api.groq.com/openai/v1"));
        PROVIDERS.put("DeepSeek", ProviderInfo.envVar("DEEPSEEK_API_KEY", "https://api.deepseek.com/v1"));
        PROVIDERS.put("Mistral", ProviderInfo.envVar("MISTRAL_API_KEY", "https://api.mistral.ai/v1"));
        PROVIDERS.put("xAI", ProviderInfo.envVar("XAI_API_KEY", "https://api.x.ai/v1"));
        PROVIDERS.put("LM Studio", ProviderInfo.local("http://localhost:1234/v1"));
        PROVIDERS.put("Ollama", ProviderInfo.local("http://localhost:11434/v1"));
    }

    public ProvidersPanel(SettingsRepo settingsRepo) {
        super(settingsRepo);

        PROVIDER_BASE_ICON_CACHE.clear();
        PROVIDER_STATUS_ICON_CACHE.clear();
        FlatSVGIcon.clearSVGDocumentCache();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(16, 18, 16, 18));

        JLabel title = new JLabel("Providers");
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_SUBTITLE);
        title.setBorder(new EmptyBorder(0, 0, 10, 0));
        add(title, BorderLayout.NORTH);

        DefaultListModel<String> providerModel = new DefaultListModel<>();
        PROVIDERS.keySet().forEach(providerModel::addElement);

        providerList = new JList<>(providerModel);
        providerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerList.setFixedCellHeight(34);
        providerList.setCellRenderer(new ProviderCellRenderer());
        providerList.setBorder(new EmptyBorder(6, 6, 6, 6));
        Color selectionBackground = UIManager.getColor("List.selectionBackground");
        Color selectionForeground = UIManager.getColor("List.selectionForeground");
        if (selectionBackground != null) {
            providerList.setSelectionBackground(selectionBackground);
        }
        if (selectionForeground != null) {
            providerList.setSelectionForeground(selectionForeground);
        }

        JScrollPane listScroll = new JScrollPane(providerList);
        listScroll.setPreferredSize(new Dimension(220, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(
            1,
            1,
            1,
            1,
            UIManager.getColor("Separator.foreground")
        ));

        for (Map.Entry<String, ProviderInfo> entry : PROVIDERS.entrySet()) {
            detailPanel.add(createDetailPanel(entry.getKey(), entry.getValue()), entry.getKey());
        }

        providerList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            String selected = providerList.getSelectedValue();
            if (selected != null) {
                detailCardLayout.show(detailPanel, selected);
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailPanel);
        split.setDividerLocation(220);
        split.setDividerSize(1);
        split.setContinuousLayout(true);
        split.setBorder(null);

        add(split, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        providerList.setSelectedIndex(0);
    }

    private JPanel createDetailPanel(String name, ProviderInfo info) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(6, 14, 6, 6));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, DETAIL_COLUMN_GAP);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        JLabel header = new JLabel(name);
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_SUBTITLE);
        panel.add(header, gbc);
        gbc.gridwidth = 1;

        JCheckBox enabled = new JCheckBox();
        row = addCheckBoxRow(panel, gbc, row + 1, enabled, "Enable this provider");
        bindCheckBox(enabled, providerEnabledKey(name), true, null);

        String configuredBaseUrl = readString(providerBaseUrlKey(name), info.defaultBaseUrl());
        boolean localReachable = info.authType() == AuthType.ENV_VAR
                && info.envVar() == null
                && LocalServiceHealth.isReachableNonBlocking(configuredBaseUrl);

        row = addSectionHeader(panel, gbc, row, "Connection");

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(label("Status"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JLabel statusLabel = createStatusLabel(name, info, configuredBaseUrl, localReachable);
        panel.add(statusLabel, gbc);

        if (shouldShowMissingApiKeyInfo(info)) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            panel.add(createMissingApiKeyInfoPanel(name, info), gbc);
            gbc.gridwidth = 1;
        }

        if (shouldShowLocalProviderInfo(name, info, localReachable)) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            panel.add(createLocalProviderInfoPanel(name, configuredBaseUrl), gbc);
            gbc.gridwidth = 1;
        }

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(label("Base URL"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JTextField baseUrl = withPreferredWidth(new JTextField(), 420);
        bindTextField(
            baseUrl,
            providerBaseUrlKey(name),
            info.defaultBaseUrl(),
            Validators.httpUrl("Invalid base URL. Use http(s)://host"),
            updatedBaseUrl -> {
                if (info.envVar() == null && info.authType() == AuthType.ENV_VAR) {
                    applyLocalStatus(statusLabel, updatedBaseUrl, LocalServiceHealth.isReachableNonBlocking(updatedBaseUrl));
                    Thread.startVirtualThread(() -> {
                        boolean reachable = LocalServiceHealth.isReachable(updatedBaseUrl);
                        SwingUtilities.invokeLater(() -> applyLocalStatus(statusLabel, updatedBaseUrl, reachable));
                    });
                }
                providerList.repaint();
            }
        );
        panel.add(baseUrl, gbc);

        if (info.authType() == AuthType.COPILOT_OAUTH || info.authType() == AuthType.CODEX_OAUTH) {
            row = addSectionHeader(panel, gbc, row + 1, "Authentication");

            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(label("Sign in"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            JButton authButton = new JButton("Login");
            panel.add(wrapLeading(authButton), gbc);

            if (info.authType() == AuthType.COPILOT_OAUTH) {
                configureCopilotAuthAction(name, statusLabel, authButton);
            } else {
                configureCodexAuthAction(name, statusLabel, authButton);
            }
        }

        if ("OpenRouter".equals(name)) {
            row = addSectionHeader(panel, gbc, row + 1, "API Usage");

            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            panel.add(createOpenRouterUsagePanel(info), gbc);
            gbc.gridwidth = 1;
        }

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 1;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    private JLabel createStatusLabel(String providerName, ProviderInfo info, String configuredBaseUrl, boolean localReachable) {
        JLabel status = new JLabel();
        Fonts.apply(status, Font.PLAIN, Fonts.SIZE_BODY);

        if (info.authType() == AuthType.COPILOT_OAUTH) {
            CopilotAuthResolver.CopilotAuthStatus copilotAuthStatus = COPILOT_AUTH_RESOLVER.resolveStatus();
            cacheCopilotAuthAvailability(providerName, copilotAuthStatus.authorized());
            applyCopilotAuthStatus(status, copilotAuthStatus);
            return status;
        }

        if (info.authType() == AuthType.CODEX_OAUTH) {
            CodexAuthResolver.CodexAuthStatus codexAuthStatus = CODEX_AUTH_RESOLVER.resolveStatus();
            cacheCodexAuthAvailability(providerName, codexAuthStatus.authorized());
            applyCodexAuthStatus(status, codexAuthStatus);
            return status;
        }

        if (info.envVar() == null) {
            applyLocalStatus(status, configuredBaseUrl, localReachable);
            return status;
        }

        String configuredEnvVar = CredentialResolver.firstConfiguredEnvVar(info.envVar());
        if (configuredEnvVar != null) {
            status.setText("\u2713 %s detected".formatted(configuredEnvVar));
            status.setForeground(new Color(0, 150, 0));
        } else {
            status.setText("\u2717 %s not set".formatted(formatEnvVarNames(info.envVar())));
            status.setForeground(new Color(200, 50, 50));
        }
        return status;
    }

    private void applyLocalStatus(JLabel status, String configuredBaseUrl, boolean reachable) {
        if (reachable) {
            status.setText("\u2713 Running at %s".formatted(configuredBaseUrl));
            status.setForeground(new Color(0, 150, 0));
        } else {
            status.setText("\u2717 Not running at %s".formatted(configuredBaseUrl));
            status.setForeground(new Color(200, 50, 50));
        }
    }

    private boolean shouldShowMissingApiKeyInfo(ProviderInfo info) {
        if (info.authType() != AuthType.ENV_VAR || info.envVar() == null) {
            return false;
        }
        return CredentialResolver.firstConfiguredEnvVar(info.envVar()) == null;
    }

    private JComponent createMissingApiKeyInfoPanel(String providerName, ProviderInfo info) {
        JPanel infoPanel = new JPanel(new BorderLayout(0, 8));
        infoPanel.setOpaque(true);
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 194, 90)),
                new EmptyBorder(10, 12, 10, 12)
        ));

        Color warningBackground = UIManager.getColor("Component.warning.background");
        infoPanel.setBackground(warningBackground != null ? warningBackground : new Color(255, 248, 225));

        JLabel header = new JLabel("\u26A0 API key setup required");
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        header.setForeground(new Color(214, 117, 0));
        infoPanel.add(header, BorderLayout.NORTH);

        String primaryEnvVar = CredentialResolver.envVarCandidates(info.envVar()).stream()
                .findFirst()
                .orElse("API_KEY");
        String acceptedEnvVars = formatEnvVarNames(info.envVar());

        JTextArea instructions = new JTextArea("""
                No API key environment variable was detected for %s.

                How to set it up:
                1) Create an API key in your provider dashboard.
                2) Add it to your shell profile, for example:
                   export %s=<your-api-key>
                3) Restart Chat4J after updating your shell profile.

                Accepted env vars: %s
                """.formatted(providerName, primaryEnvVar, acceptedEnvVars));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setBorder(null);
        Fonts.apply(instructions, Font.PLAIN, Fonts.SIZE_COMPACT);
        instructions.setForeground(readablePanelTextColor(infoPanel.getBackground()));
        infoPanel.add(instructions, BorderLayout.CENTER);

        String portalUrl = API_KEY_PORTAL_URLS.get(providerName);
        if (portalUrl != null) {
            JButton openPortalButton = new JButton("Open key page");
            openPortalButton.addActionListener(e -> openInBrowser(portalUrl));

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonRow.setOpaque(false);
            buttonRow.add(openPortalButton);
            infoPanel.add(buttonRow, BorderLayout.SOUTH);
        }

        return infoPanel;
    }

    private boolean shouldShowLocalProviderInfo(String providerName, ProviderInfo info, boolean localReachable) {
        if (info.authType() != AuthType.ENV_VAR || info.envVar() != null) {
            return false;
        }

        return LOCAL_PROVIDER_HELP.containsKey(providerName)
                && !localReachable;
    }

    private JComponent createLocalProviderInfoPanel(String providerName, String configuredBaseUrl) {
        LocalProviderHelp localHelp = LOCAL_PROVIDER_HELP.get(providerName);
        if (localHelp == null) {
            return new JPanel();
        }

        JPanel infoPanel = new JPanel(new BorderLayout(0, 8));
        infoPanel.setOpaque(true);
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(124, 179, 255)),
                new EmptyBorder(10, 12, 10, 12)
        ));

        Color infoBackground = UIManager.getColor("Component.info.background");
        infoPanel.setBackground(infoBackground != null ? infoBackground : new Color(232, 245, 253));

        JLabel header = new JLabel("\u2139 Local server setup");
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        header.setForeground(new Color(25, 118, 210));
        infoPanel.add(header, BorderLayout.NORTH);

        JTextArea instructions = new JTextArea("""
                %s is not reachable at %s.

                How to fix:
                1) %s
                2) Ensure the API endpoint remains available.
                3) If needed, update the Base URL below.
                """.formatted(providerName, configuredBaseUrl, localHelp.instruction()));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setBorder(null);
        Fonts.apply(instructions, Font.PLAIN, Fonts.SIZE_COMPACT);
        instructions.setForeground(readablePanelTextColor(infoPanel.getBackground()));
        infoPanel.add(instructions, BorderLayout.CENTER);

        if (StringUtils.isNotBlank(localHelp.docsUrl())) {
            JButton openDocsButton = new JButton("Open setup guide");
            openDocsButton.addActionListener(e -> openInBrowser(localHelp.docsUrl()));

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonRow.setOpaque(false);
            buttonRow.add(openDocsButton);
            infoPanel.add(buttonRow, BorderLayout.SOUTH);
        }

        return infoPanel;
    }

    private static Color readablePanelTextColor(Color background) {
        Color fallback = UIManager.getColor("Label.foreground");
        if (background == null) {
            return fallback != null ? fallback : new Color(60, 60, 60);
        }

        double luminance = (0.2126 * background.getRed() + 0.7152 * background.getGreen() + 0.0722 * background.getBlue()) / 255.0;
        if (luminance > 0.55) {
            return new Color(74, 74, 74);
        }

        return fallback != null ? fallback : Color.WHITE;
    }

    private void openInBrowser(String url) {
        if (SwingUtilities.isEventDispatchThread()) {
            Thread.startVirtualThread(() -> openInBrowser(url));
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            SwingUtilities.invokeLater(() -> setStatusError("Unable to open browser automatically. URL: %s".formatted(url)));
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
            SwingUtilities.invokeLater(() -> setStatusInfo("Opened: %s".formatted(url)));
        } catch (Exception e) {
            log.warn("Failed to open URL {}: {}", url, ExceptionUtils.getMessage(e));
            SwingUtilities.invokeLater(() -> setStatusError("Failed to open URL: %s".formatted(firstLine(ExceptionUtils.getMessage(e)))));
        }
    }

    private void configureCopilotAuthAction(String providerName, JLabel statusLabel, JButton authButton) {
        CopilotAuthResolver.CopilotAuthStatus status = COPILOT_AUTH_RESOLVER.resolveStatus();
        applyCopilotAuthControls(providerName, statusLabel, authButton, status, null);

        authButton.addActionListener(event -> {
            authButton.setEnabled(false);
            authButton.setText("Working...");

            Thread.startVirtualThread(() -> {
                CopilotAuthResolver.CopilotAuthStatus initialStatus = COPILOT_AUTH_RESOLVER.resolveStatus();
                CopilotAuthResolver.CopilotAuthActionResult actionResult;

                if (initialStatus.authorized() && StringUtils.equals(initialStatus.source(), CHAT4J_OAUTH_SOURCE)) {
                    actionResult = COPILOT_AUTH_RESOLVER.logout();
                } else {
                    CompletableFuture<JDialog> loginDialogFuture = new CompletableFuture<>();
                    try {
                        CopilotAuthResolver.CopilotLoginChallenge challenge = COPILOT_AUTH_RESOLVER.beginLogin();
                        SwingUtilities.invokeLater(() -> {
                            try {
                                loginDialogFuture.complete(showCopilotAuthLoginProgress(statusLabel, challenge));
                            } catch (Exception e) {
                                loginDialogFuture.completeExceptionally(e);
                            }
                        });
                        actionResult = COPILOT_AUTH_RESOLVER.completeLogin(challenge);
                    } catch (Exception e) {
                        log.warn("Copilot OAuth action failed: {}", ExceptionUtils.getMessage(e));
                        actionResult = CopilotAuthResolver.CopilotAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
                    } finally {
                        loginDialogFuture.thenAccept(dialog -> SwingUtilities.invokeLater(dialog::dispose));
                    }
                }

                CopilotAuthResolver.CopilotAuthStatus updatedStatus = COPILOT_AUTH_RESOLVER.resolveStatus();

                CopilotAuthResolver.CopilotAuthActionResult finalActionResult = actionResult;
                SwingUtilities.invokeLater(() -> applyCopilotAuthControls(
                        providerName,
                        statusLabel,
                        authButton,
                        updatedStatus,
                        finalActionResult.success() ? null : finalActionResult.message()
                ));
            });
        });
    }

    private void configureCodexAuthAction(String providerName, JLabel statusLabel, JButton authButton) {
        CodexAuthResolver.CodexAuthStatus status = CODEX_AUTH_RESOLVER.resolveStatus();
        applyCodexAuthControls(providerName, statusLabel, authButton, status, null);

        authButton.addActionListener(event -> {
            authButton.setEnabled(false);
            authButton.setText("Working...");
            statusLabel.setText("Preparing OpenAI Codex login...");
            statusLabel.setForeground(new Color(214, 117, 0));

            Thread.startVirtualThread(() -> {
                CodexAuthResolver.CodexAuthStatus initialStatus = CODEX_AUTH_RESOLVER.resolveStatus();
                CodexAuthResolver.CodexAuthActionResult actionResult;

                if (initialStatus.authorized() && StringUtils.equals(initialStatus.source(), CHAT4J_OAUTH_SOURCE)) {
                    actionResult = CODEX_AUTH_RESOLVER.logout();
                } else {
                    actionResult = runCodexLoginDialogFlow();
                }

                CodexAuthResolver.CodexAuthStatus updatedStatus = CODEX_AUTH_RESOLVER.resolveStatus();
                SwingUtilities.invokeLater(() -> applyCodexAuthControls(
                        providerName,
                        statusLabel,
                        authButton,
                        updatedStatus,
                        actionResult.success() ? null : actionResult.message()
                ));
            });
        });
    }

    private CodexAuthResolver.CodexAuthActionResult runCodexLoginDialogFlow() {
        CodexAuthResolver.CodexCallbackWait callbackWait = null;
        try {
            CodexAuthResolver.CodexLoginChallenge challenge = CODEX_AUTH_RESOLVER.beginLogin();
            callbackWait = CODEX_AUTH_RESOLVER.startCallbackWait(challenge);
            String input = showCodexLoginDialogOnEdt(challenge, callbackWait);
            if (StringUtils.isBlank(input)) {
                return CodexAuthResolver.CodexAuthActionResult.failure("OpenAI Codex login cancelled.");
            }

            return CODEX_AUTH_RESOLVER.completeLoginWithAuthorizationInput(challenge, input);
        } catch (Exception e) {
            log.warn("Codex OAuth action failed: {}", ExceptionUtils.getMessage(e));
            return CodexAuthResolver.CodexAuthActionResult.failure(firstLine(ExceptionUtils.getMessage(e)));
        } finally {
            if (callbackWait != null) {
                callbackWait.close();
            }
        }
    }

    private String showCodexLoginDialogOnEdt(
            CodexAuthResolver.CodexLoginChallenge challenge,
            CodexAuthResolver.CodexCallbackWait callbackWait
    ) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return showCodexLoginDialog(challenge, callbackWait);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(showCodexLoginDialog(challenge, callbackWait));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get();
    }

    private String showCodexLoginDialog(
            CodexAuthResolver.CodexLoginChallenge challenge,
            CodexAuthResolver.CodexCallbackWait callbackWait
    ) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "OpenAI Codex Login", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));

        JTextArea instructions = new JTextArea("""
                1. Sign in with OpenAI in your browser.
                2. If the browser callback succeeds, this dialog will close automatically.
                3. If browser opens a localhost/callback error, copy the full address bar URL.
                4. Paste that callback URL below. It should look like:
                %s
                """.formatted(codexCallbackUrlExample(challenge)));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        content.add(instructions, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField loginUrlField = new JTextField(challenge.authorizationUri());
        loginUrlField.setEditable(false);
        JTextField callbackUrlField = new JTextField(codexCallbackUrlExample(challenge));

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        fields.add(new JLabel("Login URL"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        fields.add(loginUrlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        fields.add(new JLabel("Callback URL"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        fields.add(callbackUrlField, gbc);
        content.add(fields, BorderLayout.CENTER);

        String callbackStatusText = callbackWait.listening()
                ? "%s Waiting for browser callback. You can paste manually if it is blocked.".formatted(callbackWait.message())
                : callbackWait.message();
        JLabel callbackStatus = new JLabel(callbackStatusText);
        callbackStatus.setForeground(new Color(214, 117, 0));

        JButton openBrowserButton = new JButton("Open browser");
        JButton copyLoginUrlButton = new JButton("Copy login URL");
        JButton completeButton = new JButton("Complete login with callback URL");
        JButton cancelButton = new JButton("Cancel");

        final String[] resultHolder = new String[1];
        callbackWait.callbackInputFuture().whenComplete((callbackInput, throwable) -> {
            if (throwable != null || StringUtils.isBlank(callbackInput)) {
                return;
            }

            SwingUtilities.invokeLater(() -> {
                if (!dialog.isDisplayable()) {
                    return;
                }

                resultHolder[0] = callbackInput;
                callbackStatus.setText("Browser callback received. Completing login...");
                dialog.dispose();
            });
        });
        openBrowserButton.addActionListener(e -> openInBrowser(challenge.authorizationUri()));
        copyLoginUrlButton.addActionListener(e -> copyToClipboard(challenge.authorizationUri(), "Copied OpenAI Codex login URL."));
        completeButton.addActionListener(e -> {
            resultHolder[0] = callbackUrlField.getText();
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(openBrowserButton);
        buttons.add(copyLoginUrlButton);
        buttons.add(completeButton);
        buttons.add(cancelButton);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(callbackStatus, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setSize(new Dimension(760, 320));
        dialog.setLocationRelativeTo(this);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                openInBrowser(challenge.authorizationUri());
            }
        });
        dialog.setVisible(true);
        return resultHolder[0];
    }

    private String codexCallbackUrlExample(CodexAuthResolver.CodexLoginChallenge challenge) {
        String redirectUri = challenge == null ? "http://localhost:1455/auth/callback" : challenge.redirectUri();
        String separator = redirectUri.contains("?") ? "&" : "?";
        return "%s%scode=...&state=...".formatted(redirectUri, separator);
    }

    private void copyToClipboard(String text, String successMessage) {
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(StringUtils.defaultString(text)), null);
            setStatusInfo(successMessage);
        } catch (Exception e) {
            setStatusError("Unable to copy to clipboard: %s".formatted(firstLine(ExceptionUtils.getMessage(e))));
        }
    }

    private JDialog showCopilotAuthLoginProgress(
            JLabel statusLabel,
            CopilotAuthResolver.CopilotLoginChallenge challenge
    ) {
        String message = "Browser opened and login code copied to clipboard. If needed, use code %s at %s."
                .formatted(challenge.userCode(), challenge.verificationUri());
        statusLabel.setText(message);
        statusLabel.setForeground(new Color(214, 117, 0));
        setStatusInfo(message);

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                "GitHub Copilot Login",
                Dialog.ModalityType.MODELESS
        );
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));

        JTextArea instructions = new JTextArea("""
                Complete GitHub Copilot sign-in in your browser.
                The login code was copied to your clipboard. If needed, copy it again below.
                """);
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        content.add(instructions, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField codeField = new JTextField(challenge.userCode());
        codeField.setEditable(false);
        JTextField verificationUrlField = new JTextField(challenge.verificationUri());
        verificationUrlField.setEditable(false);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        fields.add(new JLabel("Code"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        fields.add(codeField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        fields.add(new JLabel("Login URL"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        fields.add(verificationUrlField, gbc);
        content.add(fields, BorderLayout.CENTER);

        JButton copyCodeButton = new JButton("Copy code");
        JButton openBrowserButton = new JButton("Open browser");
        JButton closeButton = new JButton("Close");

        copyCodeButton.addActionListener(e -> copyToClipboard(
                challenge.userCode(),
                "Copied GitHub Copilot login code."
        ));
        openBrowserButton.addActionListener(e -> {
            copyToClipboard(challenge.userCode(), "Copied GitHub Copilot login code.");
            openInBrowser(challenge.verificationUri());
        });
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(openBrowserButton);
        buttons.add(copyCodeButton);
        buttons.add(closeButton);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setSize(new Dimension(560, 240));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return dialog;
    }

    private void applyCopilotAuthControls(
            String providerName,
            JLabel statusLabel,
            JButton authButton,
            CopilotAuthResolver.CopilotAuthStatus status,
            String failureMessage
    ) {
        cacheCopilotAuthAvailability(providerName, status.authorized());
        applyCopilotAuthStatus(statusLabel, status);

        boolean chat4jSession = status.authorized() && StringUtils.equals(status.source(), CHAT4J_OAUTH_SOURCE);
        boolean oauthClientConfigured = COPILOT_AUTH_RESOLVER.isOAuthClientConfigured();

        authButton.setOpaque(true);
        if (chat4jSession) {
            authButton.setText("Log out");
            authButton.setEnabled(true);
            authButton.setForeground(Color.WHITE);
            authButton.setBackground(new Color(229, 57, 53));
            authButton.setToolTipText("Sign out Chat4J OAuth session");
        } else if (!oauthClientConfigured) {
            authButton.setText("Configure OAuth App");
            authButton.setEnabled(false);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText(COPILOT_AUTH_RESOLVER.oauthClientConfigurationHint());

            if (!status.authorized() && StringUtils.isBlank(failureMessage)) {
                statusLabel.setText(COPILOT_AUTH_RESOLVER.oauthClientConfigurationHint());
                statusLabel.setForeground(new Color(214, 117, 0));
            }
        } else {
            authButton.setText("Login");
            authButton.setEnabled(true);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText("Sign in GitHub for Chat4J");
        }

        if (StringUtils.isNotBlank(failureMessage)) {
            statusLabel.setText(failureMessage);
            statusLabel.setForeground(new Color(200, 50, 50));
        }

        providerList.repaint();
    }

    private void applyCodexAuthControls(
            String providerName,
            JLabel statusLabel,
            JButton authButton,
            CodexAuthResolver.CodexAuthStatus status,
            String failureMessage
    ) {
        cacheCodexAuthAvailability(providerName, status.authorized());
        applyCodexAuthStatus(statusLabel, status);

        boolean chat4jSession = status.authorized() && StringUtils.equals(status.source(), CHAT4J_OAUTH_SOURCE);
        boolean oauthClientConfigured = CODEX_AUTH_RESOLVER.isOAuthClientConfigured();

        authButton.setOpaque(true);
        if (chat4jSession) {
            authButton.setText("Log out");
            authButton.setEnabled(true);
            authButton.setForeground(Color.WHITE);
            authButton.setBackground(new Color(229, 57, 53));
            authButton.setToolTipText("Sign out Chat4J OAuth session");
        } else if (!oauthClientConfigured) {
            authButton.setText("Configure OAuth App");
            authButton.setEnabled(false);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText(CODEX_AUTH_RESOLVER.oauthClientConfigurationHint());

            if (!status.authorized() && StringUtils.isBlank(failureMessage)) {
                statusLabel.setText(CODEX_AUTH_RESOLVER.oauthClientConfigurationHint());
                statusLabel.setForeground(new Color(214, 117, 0));
            }
        } else {
            authButton.setText("Login");
            authButton.setEnabled(true);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText("Sign in OpenAI for Chat4J");
        }

        if (StringUtils.isNotBlank(failureMessage)) {
            statusLabel.setText(failureMessage);
            statusLabel.setForeground(new Color(200, 50, 50));
        }

        providerList.repaint();
    }

    private void applyCopilotAuthStatus(JLabel statusLabel, CopilotAuthResolver.CopilotAuthStatus status) {
        statusLabel.setText(status.authorized() ? "Authorized" : "Not authorized");
        statusLabel.setForeground(status.authorized() ? new Color(0, 150, 0) : new Color(200, 50, 50));
    }

    private void applyCodexAuthStatus(JLabel statusLabel, CodexAuthResolver.CodexAuthStatus status) {
        statusLabel.setText(status.authorized() ? "Authorized" : "Not authorized");
        statusLabel.setForeground(status.authorized() ? new Color(0, 150, 0) : new Color(200, 50, 50));
    }

    private JPanel createOpenRouterUsagePanel(ProviderInfo info) {
        JPanel usagePanel = new JPanel();
        usagePanel.setLayout(new BoxLayout(usagePanel, BoxLayout.Y_AXIS));
        usagePanel.setOpaque(false);

        JLabel summaryLabel = new JLabel("Updated n/a");
        Fonts.apply(summaryLabel, Font.PLAIN, Fonts.SIZE_BODY);
        summaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JProgressBar usageBar = new JProgressBar(0, 100);
        usageBar.setStringPainted(false);
        usageBar.setValue(0);
        usageBar.setPreferredSize(new Dimension(220, 10));
        usageBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));

        JLabel usedLabel = new JLabel("Used: n/a");
        JLabel remainingLabel = new JLabel("Remaining: n/a");

        JPanel statsRow = new JPanel(new BorderLayout());
        statsRow.setOpaque(false);
        statsRow.add(usedLabel, BorderLayout.WEST);
        statsRow.add(remainingLabel, BorderLayout.EAST);

        JLabel balanceLabel = new JLabel("Balance: n/a");
        JLabel limitLabel = new JLabel("Limit: n/a");

        JPanel accountRow = new JPanel(new BorderLayout());
        accountRow.setOpaque(false);
        accountRow.add(balanceLabel, BorderLayout.WEST);
        accountRow.add(limitLabel, BorderLayout.EAST);

        JLabel noteLabel = new JLabel("");
        noteLabel.setForeground(new Color(214, 117, 0));

        JButton refreshButton = new JButton("Refresh usage");
        refreshButton.addActionListener(e -> refreshOpenRouterUsage(
                info,
                refreshButton,
                summaryLabel,
                usageBar,
                usedLabel,
                remainingLabel,
                balanceLabel,
                limitLabel,
                noteLabel
        ));

        usagePanel.add(summaryLabel);
        usagePanel.add(Box.createVerticalStrut(8));
        usagePanel.add(usageBar);
        usagePanel.add(Box.createVerticalStrut(6));
        usagePanel.add(statsRow);
        usagePanel.add(Box.createVerticalStrut(6));
        usagePanel.add(accountRow);
        usagePanel.add(Box.createVerticalStrut(4));
        usagePanel.add(noteLabel);
        usagePanel.add(Box.createVerticalStrut(8));
        usagePanel.add(wrapLeading(refreshButton));

        refreshOpenRouterUsage(
                info,
                refreshButton,
                summaryLabel,
                usageBar,
                usedLabel,
                remainingLabel,
                balanceLabel,
                limitLabel,
                noteLabel
        );

        return usagePanel;
    }

    private void refreshOpenRouterUsage(
        ProviderInfo info,
        JButton refreshButton,
        JLabel summaryLabel,
        JProgressBar usageBar,
        JLabel usedLabel,
        JLabel remainingLabel,
        JLabel balanceLabel,
        JLabel limitLabel,
        JLabel noteLabel
    ) {
        refreshButton.setEnabled(false);
        refreshButton.setText("Refreshing...");

        Thread.startVirtualThread(() -> {
            OpenRouterUsageSnapshot snapshot = fetchOpenRouterUsage(info);
            SwingUtilities.invokeLater(() -> {
                applyOpenRouterUsage(
                        snapshot,
                        summaryLabel,
                        usageBar,
                        usedLabel,
                        remainingLabel,
                        balanceLabel,
                        limitLabel,
                        noteLabel
                );
                refreshButton.setEnabled(true);
                refreshButton.setText("Refresh usage");
            });
        });
    }

    private OpenRouterUsageSnapshot fetchOpenRouterUsage(ProviderInfo info) {
        try {
            String apiKey = CredentialResolver.resolveApiKey(info.envVar(), null);
            if (StringUtils.isBlank(apiKey)) {
                return OpenRouterUsageSnapshot.error("OPENROUTER_API_KEY not set");
            }

            JsonNode keyData = requestOpenRouterData("https://openrouter.ai/api/v1/key", apiKey);
            double limit = asDouble(keyData, "limit");
            double usage = asDouble(keyData, "usage");
            double remainingFromUsage = (!Double.isNaN(limit) && !Double.isNaN(usage))
                    ? Math.max(0, limit - usage)
                    : Double.NaN;

            double limitRemainingField = asDouble(keyData, "limit_remaining");
            double remaining = !Double.isNaN(remainingFromUsage) ? remainingFromUsage : limitRemainingField;

            int usedPercent;
            if (!Double.isNaN(limit) && limit > 0 && !Double.isNaN(usage)) {
                usedPercent = clampPercent((int) Math.round((usage / limit) * 100d));
            } else if (!Double.isNaN(limit) && limit > 0 && !Double.isNaN(remaining)) {
                usedPercent = clampPercent((int) Math.round((1d - (remaining / limit)) * 100d));
            } else {
                usedPercent = -1;
            }

            String limitReset = asText(keyData, "limit_reset");
            String note = StringUtils.isBlank(limitReset) ? null : "Resets %s".formatted(limitReset);

            Double balance = null;
            try {
                JsonNode creditsData = requestOpenRouterData("https://openrouter.ai/api/v1/credits", apiKey);
                double totalCredits = asDouble(creditsData, "total_credits");
                double totalUsage = asDouble(creditsData, "total_usage");
                if (!Double.isNaN(totalCredits) && !Double.isNaN(totalUsage)) {
                    balance = Math.max(0, totalCredits - totalUsage);
                }
            } catch (Exception e) {
                String creditsError = firstLine(e.getMessage());
                note = note == null ? "Balance unavailable: %s".formatted(creditsError) : "%s • Balance unavailable".formatted(note);
            }

            return OpenRouterUsageSnapshot.success(balance, limit, remaining, usedPercent, note);
        } catch (Exception e) {
            return OpenRouterUsageSnapshot.error(firstLine(e.getMessage()));
        }
    }

    private JsonNode requestOpenRouterData(String url, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bearer %s".formatted(apiKey))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP %d from %s".formatted(response.statusCode(), url));
        }

        JsonNode root = JSON.readTree(response.body());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("Missing data object in response from %s".formatted(url));
        }
        return data;
    }

    private void applyOpenRouterUsage(
        OpenRouterUsageSnapshot snapshot,
        JLabel summaryLabel,
        JProgressBar usageBar,
        JLabel usedLabel,
        JLabel remainingLabel,
        JLabel balanceLabel,
        JLabel limitLabel,
        JLabel noteLabel
    ) {
        summaryLabel.setText("Updated %s".formatted(formatRelativeTime(snapshot.updatedAtEpochMs())));

        if (snapshot.errorMessage() != null) {
            usedLabel.setText("Used: n/a");
            remainingLabel.setText("Remaining: n/a");
            balanceLabel.setText("Balance: n/a");
            limitLabel.setText("Limit: n/a");
            usageBar.setValue(0);
            noteLabel.setForeground(new Color(200, 50, 50));
            noteLabel.setText(snapshot.errorMessage());
            return;
        }

        if (snapshot.usedPercent() >= 0) {
            usageBar.setValue(snapshot.usedPercent());
            usedLabel.setText("%d%% used".formatted(snapshot.usedPercent()));
        } else {
            usageBar.setValue(0);
            usedLabel.setText("Used: n/a");
        }

        if (!Double.isNaN(snapshot.remaining())) {
            remainingLabel.setText("%s left".formatted(formatUsd(snapshot.remaining())));
        } else {
            remainingLabel.setText("Remaining: n/a");
        }

        balanceLabel.setText(snapshot.balance() == null
                ? "Balance: n/a"
                : "Balance: %s".formatted(formatUsd(snapshot.balance())));
        limitLabel.setText(Double.isNaN(snapshot.limit())
                ? "Limit: n/a"
                : "Limit: %s".formatted(formatUsd(snapshot.limit())));

        String note = snapshot.note();
        noteLabel.setForeground(new Color(214, 117, 0));
        noteLabel.setText(StringUtils.isBlank(note) ? "" : note);
    }

    private double asDouble(JsonNode node, String fieldName) {
        return node.hasNonNull(fieldName) ? node.path(fieldName).asDouble(Double.NaN) : Double.NaN;
    }

    private String asText(JsonNode node, String fieldName) {
        return node.hasNonNull(fieldName) ? node.path(fieldName).asText("") : null;
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String formatUsd(double value) {
        synchronized (USD_FORMAT) {
            return USD_FORMAT.format(value);
        }
    }

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "Unknown error";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    private String formatRelativeTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "n/a";
        }

        long seconds = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now()).toSeconds();
        if (seconds < 5) {
            return "just now";
        }
        if (seconds < 60) {
            return "%ds ago".formatted(seconds);
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return "%dm ago".formatted(minutes);
        }

        return DIAGNOSTICS_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatEnvVarNames(String envVarExpression) {
        return String.join(" / ", CredentialResolver.envVarCandidates(envVarExpression));
    }

    private boolean isProviderAvailable(String providerName, ProviderInfo info) {
        if (info == null) {
            return false;
        }

        if (info.authType() == AuthType.COPILOT_OAUTH) {
            return isCopilotAuthAvailable(providerName);
        }

        if (info.authType() == AuthType.CODEX_OAUTH) {
            return isCodexAuthAvailable(providerName);
        }

        if (info.envVar() == null) {
            String configuredBaseUrl = readString(providerBaseUrlKey(providerName), info.defaultBaseUrl());
            return LocalServiceHealth.isReachableNonBlocking(configuredBaseUrl);
        }

        return CredentialResolver.hasRequiredCredentials(info.envVar());
    }

    private boolean isCopilotAuthAvailable(String providerName) {
        Instant now = Instant.now();
        CopilotAuthAvailabilitySnapshot cached = copilotAuthAvailabilityByProvider.get(providerName);
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_AVAILABILITY_TTL))) {
            return cached.authorized();
        }

        CopilotAuthResolver.CopilotAuthStatus status = COPILOT_AUTH_RESOLVER.resolveStatus();
        cacheCopilotAuthAvailability(providerName, status.authorized());
        return status.authorized();
    }

    private boolean isCodexAuthAvailable(String providerName) {
        Instant now = Instant.now();
        CodexAuthAvailabilitySnapshot cached = codexAuthAvailabilityByProvider.get(providerName);
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_AVAILABILITY_TTL))) {
            return cached.authorized();
        }

        CodexAuthResolver.CodexAuthStatus status = CODEX_AUTH_RESOLVER.resolveStatus();
        cacheCodexAuthAvailability(providerName, status.authorized());
        return status.authorized();
    }

    private void cacheCopilotAuthAvailability(String providerName, boolean authorized) {
        copilotAuthAvailabilityByProvider.put(providerName, new CopilotAuthAvailabilitySnapshot(authorized, Instant.now()));
    }

    private void cacheCodexAuthAvailability(String providerName, boolean authorized) {
        codexAuthAvailabilityByProvider.put(providerName, new CodexAuthAvailabilitySnapshot(authorized, Instant.now()));
    }

    private static Icon iconForProvider(String providerName, boolean available) {
        ProviderStatusIconKey key = new ProviderStatusIconKey(providerName, available);
        return PROVIDER_STATUS_ICON_CACHE.computeIfAbsent(key, ignored -> {
            Icon baseIcon = loadProviderBaseIcon(providerName);
            if (baseIcon == null) {
                return new StatusDotIcon(available);
            }
            return new ProviderStatusOverlayIcon(baseIcon, available);
        });
    }

    private static Icon loadProviderBaseIcon(String providerName) {
        String path = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(path)) {
            return null;
        }

        return PROVIDER_BASE_ICON_CACHE.computeIfAbsent(path, iconPath -> {
            URL url = ProvidersPanel.class.getResource(iconPath);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(PROVIDER_ICON_GLYPH_SIZE, PROVIDER_ICON_GLYPH_SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                Color foreground = component != null ? component.getForeground() : null;
                if (foreground == null) {
                    foreground = UIManager.getColor("Label.foreground");
                }
                if (foreground == null) {
                    foreground = new Color(80, 80, 80);
                }
                return new Color(
                        foreground.getRed(),
                        foreground.getGreen(),
                        foreground.getBlue(),
                        color.getAlpha());
            }));
            if (!icon.hasFound()) {
                return null;
            }
            return new PaddedIcon(icon, PROVIDER_ICON_PADDING);
        });
    }

    private JComponent createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        statusPanel.add(statusLabel(), BorderLayout.WEST);
        return statusPanel;
    }

    private String providerBaseUrlKey(String providerName) {
        return SettingsKeys.providerBaseUrlKey(providerName);
    }

    private String providerEnabledKey(String providerName) {
        return SettingsKeys.providerEnabledKey(providerName);
    }

    private JComponent wrapLeading(JComponent component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(component);
        return row;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
        Dimension preferred = label.getPreferredSize();
        Dimension alignedSize = new Dimension(DETAIL_LABEL_COLUMN_WIDTH, preferred.height);
        label.setPreferredSize(alignedSize);
        label.setMinimumSize(alignedSize);
        return label;
    }

    private record OpenRouterUsageSnapshot(
        Double balance,
        double limit,
        double remaining,
        int usedPercent,
        String note,
        String errorMessage,
        long updatedAtEpochMs
    ) {

        private static OpenRouterUsageSnapshot success(
            Double balance,
            double limit,
            double remaining,
            int usedPercent,
            String note
        ) {
            return new OpenRouterUsageSnapshot(balance, limit, remaining, usedPercent, note, null, System.currentTimeMillis());
        }

        private static OpenRouterUsageSnapshot error(String errorMessage) {
            return new OpenRouterUsageSnapshot(null, Double.NaN, Double.NaN, -1, null, errorMessage, System.currentTimeMillis());
        }
    }

    private record LocalProviderHelp(String instruction, String docsUrl) {
    }

    private record ProviderStatusIconKey(String providerName, boolean available) {
    }

    private record CopilotAuthAvailabilitySnapshot(boolean authorized, Instant checkedAt) {
    }

    private record CodexAuthAvailabilitySnapshot(boolean authorized, Instant checkedAt) {
    }

    record ProviderInfo(String envVar, String defaultBaseUrl, AuthType authType) {

        static ProviderInfo envVar(String envVar, String baseUrl) {
            return new ProviderInfo(envVar, baseUrl, AuthType.ENV_VAR);
        }

        static ProviderInfo local(String baseUrl) {
            return new ProviderInfo(null, baseUrl, AuthType.ENV_VAR);
        }

        static ProviderInfo copilotOAuth(String baseUrl) {
            return new ProviderInfo(null, baseUrl, AuthType.COPILOT_OAUTH);
        }

        static ProviderInfo codexOAuth(String baseUrl) {
            return new ProviderInfo(null, baseUrl, AuthType.CODEX_OAUTH);
        }
    }

    private static class PaddedIcon implements Icon {
        private final Icon delegate;
        private final int padding;

        private PaddedIcon(Icon delegate, int padding) {
            this.delegate = delegate;
            this.padding = Math.max(0, padding);
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            delegate.paintIcon(component, graphics, x + padding, y + padding);
        }

        @Override
        public int getIconWidth() {
            return delegate.getIconWidth() + padding * 2;
        }

        @Override
        public int getIconHeight() {
            return delegate.getIconHeight() + padding * 2;
        }
    }

    private static class StatusDotIcon implements Icon {
        private final boolean available;

        private StatusDotIcon(boolean available) {
            this.available = available;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(statusDotColor(component, available));
                int dotX = x + (getIconWidth() - STATUS_DOT_SIZE) / 2;
                int dotY = y + (getIconHeight() - STATUS_DOT_SIZE) / 2;
                g2.fillOval(dotX, dotY, STATUS_DOT_SIZE, STATUS_DOT_SIZE);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return PROVIDER_ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return PROVIDER_ICON_SIZE;
        }
    }

    private static class ProviderStatusOverlayIcon implements Icon {
        private final Icon baseIcon;
        private final boolean available;

        private ProviderStatusOverlayIcon(Icon baseIcon, boolean available) {
            this.baseIcon = baseIcon;
            this.available = available;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                baseIcon.paintIcon(component, g2, x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(statusDotColor(component, available));
                int dotX = x + getIconWidth() - STATUS_DOT_SIZE - 1;
                int dotY = y + getIconHeight() - STATUS_DOT_SIZE - 1;
                g2.fillOval(dotX, dotY, STATUS_DOT_SIZE, STATUS_DOT_SIZE);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return baseIcon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return baseIcon.getIconHeight();
        }
    }

    private static Color statusDotColor(Component component, boolean available) {
        Color color = available
                ? UIManager.getColor("Component.accentColor")
                : UIManager.getColor("Label.disabledForeground");
        if (color != null) {
            return color;
        }
        return available ? new Color(67, 160, 71) : new Color(140, 140, 140);
    }

    private class ProviderCellRenderer extends DefaultListCellRenderer {
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
            label.setBorder(new EmptyBorder(4, 10, 4, 10));
            Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
            label.setIconTextGap(8);

            String providerName = value instanceof String s ? s : "";
            ProviderInfo info = PROVIDERS.get(providerName);
            boolean available = info != null && isProviderAvailable(providerName, info);
            label.setText(providerName);
            label.setIcon(iconForProvider(providerName, available));
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
                label.setOpaque(true);
            }
            return label;
        }
    }
}
