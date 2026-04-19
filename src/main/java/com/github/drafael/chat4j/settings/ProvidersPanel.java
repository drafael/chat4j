package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.capability.chat.impl.CodexCliChatCompletionClient;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;

public class ProvidersPanel extends AbstractSettingsPanel {

    private static final CliOAuthRunner CLI_OAUTH_RUNNER = new CliOAuthRunner();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final NumberFormat USD_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DateTimeFormatter DIAGNOSTICS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int DETAIL_LABEL_COLUMN_WIDTH = 132;
    private static final int DETAIL_COLUMN_GAP = 18;

    private static final OAuthCliSpec CODEX_OAUTH = new OAuthCliSpec(
        List.of("codex", "login", "status"),
        List.of("codex", "login"),
        List.of("codex", "logout"),
        emptyList());

    private static final OAuthCliSpec COPILOT_OAUTH = new OAuthCliSpec(
        List.of("gh", "auth", "status"),
        List.of("gh", "auth", "login"),
        List.of("gh", "auth", "logout"),
        List.of("gh", "auth", "token"));

    private final CardLayout detailCardLayout = new CardLayout();
    private final JPanel detailPanel = new JPanel(detailCardLayout);

    private static final Map<String, ProviderInfo> PROVIDERS = new LinkedHashMap<>();
    private static final Map<String, String> API_KEY_PORTAL_URLS = Map.ofEntries(
            Map.entry("Anthropic", "https://console.anthropic.com/settings/keys"),
            Map.entry("OpenAI", "https://platform.openai.com/api-keys"),
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
    private static final Duration CLI_OAUTH_AVAILABILITY_TTL = Duration.ofSeconds(15);
    private static final Map<String, Icon> PROVIDER_BASE_ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<ProviderStatusIconKey, Icon> PROVIDER_STATUS_ICON_CACHE = new ConcurrentHashMap<>();

    private final Map<String, CliOauthAvailabilitySnapshot> cliOauthAvailabilityByProvider = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cliOauthAvailabilityRefreshInFlightByProvider = new ConcurrentHashMap<>();
    private final JList<String> providerList;

    static {
        PROVIDERS.put("Anthropic", ProviderInfo.envVar("ANTHROPIC_API_KEY", "https://api.anthropic.com"));
        PROVIDERS.put("OpenAI", ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"));
        PROVIDERS.put("OpenAI Codex", ProviderInfo.cliOAuth("https://api.openai.com/v1", CODEX_OAUTH));
        PROVIDERS.put("GitHub Copilot", ProviderInfo.cliOAuth("https://api.githubcopilot.com", COPILOT_OAUTH));
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
        setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("Providers");
        title.setFont(Fonts.of(Font.BOLD, 18));
        title.setBorder(new EmptyBorder(0, 0, 16, 0));
        add(title, BorderLayout.NORTH);

        DefaultListModel<String> providerModel = new DefaultListModel<>();
        PROVIDERS.keySet().forEach(providerModel::addElement);

        providerList = new JList<>(providerModel);
        providerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        providerList.setFixedCellHeight(38);
        providerList.setCellRenderer(new ProviderCellRenderer());
        providerList.setBorder(new EmptyBorder(4, 4, 4, 4));

        JScrollPane listScroll = new JScrollPane(providerList);
        listScroll.setPreferredSize(new Dimension(180, 0));
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
        split.setDividerLocation(180);
        split.setDividerSize(1);
        split.setContinuousLayout(true);
        split.setBorder(null);

        add(split, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        providerList.setSelectedIndex(0);
    }

    private JPanel createDetailPanel(String name, ProviderInfo info) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 16, 8, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, DETAIL_COLUMN_GAP);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        JLabel header = new JLabel(name);
        header.setFont(Fonts.of(Font.BOLD, 16));
        panel.add(header, gbc);
        gbc.gridwidth = 1;

        String configuredBaseUrl = readString(providerBaseUrlKey(name), info.defaultBaseUrl());
        boolean localReachable = info.envVar() == null && LocalServiceHealth.isReachable(configuredBaseUrl);

        row++;
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

        if (info.authType() == AuthType.CLI_OAUTH) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(label("Authentication"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            JButton authButton = new JButton("Login");
            panel.add(authButton, gbc);
            configureOAuthAction(name, info, statusLabel, authButton);
        }

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(label("Base URL"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JTextField baseUrl = new JTextField();
        bindTextField(
            baseUrl,
            providerBaseUrlKey(name),
            info.defaultBaseUrl(),
            Validators.httpUrl("Invalid base URL. Use http(s)://host"),
            updatedBaseUrl -> {
                if (info.envVar() == null && info.authType() == AuthType.ENV_VAR) {
                    boolean reachable = LocalServiceHealth.isReachable(updatedBaseUrl);
                    applyLocalStatus(statusLabel, updatedBaseUrl, reachable);
                }
                providerList.repaint();
            }
        );
        panel.add(baseUrl, gbc);

        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(label("Enabled"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JCheckBox enabled = new JCheckBox();
        bindCheckBox(enabled, providerEnabledKey(name), true, null);
        panel.add(enabled, gbc);

        if ("OpenRouter".equals(name)) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(label("API Usage"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            panel.add(createOpenRouterUsagePanel(info), gbc);
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
        status.setFont(Fonts.of(Font.PLAIN, 13));

        if (info.authType() == AuthType.CLI_OAUTH) {
            CliOAuthRunner.OAuthStatus oauthStatus = CLI_OAUTH_RUNNER.checkStatus(info.oauthCliSpec());
            cacheCliOauthAvailability(providerName, oauthStatus.authorized());
            applyOAuthStatus(status, oauthStatus);
            return status;
        }

        if (info.envVar() == null) {
            applyLocalStatus(status, configuredBaseUrl, localReachable);
            return status;
        }

        String configuredEnvVar = CredentialResolver.firstConfiguredEnvVar(info.envVar());
        if (configuredEnvVar != null) {
            status.setText("\u2713 " + configuredEnvVar + " detected");
            status.setForeground(new Color(0, 150, 0));
        } else {
            status.setText("\u2717 " + formatEnvVarNames(info.envVar()) + " not set");
            status.setForeground(new Color(200, 50, 50));
        }
        return status;
    }

    private void applyLocalStatus(JLabel status, String configuredBaseUrl, boolean reachable) {
        if (reachable) {
            status.setText("\u2713 Running at " + configuredBaseUrl);
            status.setForeground(new Color(0, 150, 0));
        } else {
            status.setText("\u2717 Not running at " + configuredBaseUrl);
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
        header.setFont(Fonts.of(Font.BOLD, 13));
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
        instructions.setFont(Fonts.of(Font.PLAIN, 12));
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
        header.setFont(Fonts.of(Font.BOLD, 13));
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
        instructions.setFont(Fonts.of(Font.PLAIN, 12));
        instructions.setForeground(readablePanelTextColor(infoPanel.getBackground()));
        infoPanel.add(instructions, BorderLayout.CENTER);

        if (localHelp.docsUrl() != null && !localHelp.docsUrl().isBlank()) {
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
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            setStatusError("Unable to open browser automatically. URL: %s".formatted(url));
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
            setStatusInfo("Opened: %s".formatted(url));
        } catch (Exception e) {
            setStatusError("Failed to open URL: %s".formatted(firstLine(e.getMessage())));
        }
    }

    private void configureOAuthAction(String providerName, ProviderInfo info, JLabel statusLabel, JButton authButton) {
        CliOAuthRunner.OAuthStatus status = CLI_OAUTH_RUNNER.checkStatus(info.oauthCliSpec());
        applyOAuthControls(providerName, statusLabel, authButton, status, null);

        authButton.addActionListener(e -> {
            authButton.setEnabled(false);
            authButton.setText("Working...");

            Thread.startVirtualThread(() -> {
                CliOAuthRunner.OAuthStatus initialStatus = CLI_OAUTH_RUNNER.checkStatus(info.oauthCliSpec());
                CliOAuthRunner.OAuthActionResult actionResult = !initialStatus.cliAvailable()
                        ? CliOAuthRunner.OAuthActionResult.failure(initialStatus.message())
                        : (initialStatus.authorized()
                            ? CLI_OAUTH_RUNNER.logout(info.oauthCliSpec())
                            : CLI_OAUTH_RUNNER.login(info.oauthCliSpec()));
                CliOAuthRunner.OAuthStatus updatedStatus = CLI_OAUTH_RUNNER.checkStatus(info.oauthCliSpec());

                SwingUtilities.invokeLater(() -> applyOAuthControls(
                    providerName,
                    statusLabel,
                    authButton,
                    updatedStatus,
                    actionResult.success() ? null : actionResult.message()
                ));
            });
        });
    }

    private void applyOAuthControls(
        String providerName,
        JLabel statusLabel,
        JButton authButton,
        CliOAuthRunner.OAuthStatus status,
        String failureMessage
    ) {
        cacheCliOauthAvailability(providerName, status.authorized());
        applyOAuthStatus(statusLabel, status);

        if (!status.cliAvailable()) {
            authButton.setText("Install required CLI");
            authButton.setEnabled(false);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setOpaque(true);
            authButton.setToolTipText(status.message());
        } else {
            boolean authorized = status.authorized();
            authButton.setText(authorized ? "Log out" : "Login");
            authButton.setEnabled(true);
            authButton.setForeground(authorized ? Color.WHITE : UIManager.getColor("Button.foreground"));
            authButton.setBackground(authorized ? new Color(229, 57, 53) : UIManager.getColor("Button.background"));
            authButton.setOpaque(true);
            authButton.setToolTipText(null);
        }

        if (failureMessage != null && !failureMessage.isBlank()) {
            statusLabel.setText(failureMessage);
            statusLabel.setForeground(new Color(200, 50, 50));
        }

        providerList.repaint();
    }

    private void applyOAuthStatus(JLabel statusLabel, CliOAuthRunner.OAuthStatus status) {
        statusLabel.setText(status.message());
        statusLabel.setForeground(status.authorized() ? new Color(0, 150, 0) : new Color(200, 50, 50));
    }

    private JPanel createOpenRouterUsagePanel(ProviderInfo info) {
        JPanel usagePanel = new JPanel();
        usagePanel.setLayout(new BoxLayout(usagePanel, BoxLayout.Y_AXIS));
        usagePanel.setOpaque(false);

        JLabel updatedLabel = new JLabel("Updated n/a");
        updatedLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JLabel balanceLabel = new JLabel("Balance: n/a");
        balanceLabel.setFont(Fonts.of(Font.BOLD, 16));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        headerRow.add(updatedLabel, BorderLayout.WEST);
        headerRow.add(balanceLabel, BorderLayout.EAST);

        JLabel limitTitleLabel = new JLabel("API key limit");
        limitTitleLabel.setFont(Fonts.of(Font.BOLD, 13));

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

        JLabel noteLabel = new JLabel("");
        noteLabel.setForeground(new Color(214, 117, 0));

        JButton refreshButton = new JButton("Refresh usage");
        refreshButton.addActionListener(e -> refreshOpenRouterUsage(
            info,
            refreshButton,
            updatedLabel,
            balanceLabel,
            usageBar,
            usedLabel,
            remainingLabel,
            noteLabel
        ));

        usagePanel.add(headerRow);
        usagePanel.add(Box.createVerticalStrut(8));
        usagePanel.add(new JSeparator());
        usagePanel.add(Box.createVerticalStrut(10));
        usagePanel.add(limitTitleLabel);
        usagePanel.add(Box.createVerticalStrut(6));
        usagePanel.add(usageBar);
        usagePanel.add(Box.createVerticalStrut(6));
        usagePanel.add(statsRow);
        usagePanel.add(Box.createVerticalStrut(4));
        usagePanel.add(noteLabel);
        usagePanel.add(Box.createVerticalStrut(8));
        usagePanel.add(refreshButton);

        refreshOpenRouterUsage(
            info,
            refreshButton,
            updatedLabel,
            balanceLabel,
            usageBar,
            usedLabel,
            remainingLabel,
            noteLabel
        );

        return usagePanel;
    }

    private void refreshOpenRouterUsage(
        ProviderInfo info,
        JButton refreshButton,
        JLabel updatedLabel,
        JLabel balanceLabel,
        JProgressBar usageBar,
        JLabel usedLabel,
        JLabel remainingLabel,
        JLabel noteLabel
    ) {
        refreshButton.setEnabled(false);
        refreshButton.setText("Refreshing...");

        Thread.startVirtualThread(() -> {
            OpenRouterUsageSnapshot snapshot = fetchOpenRouterUsage(info);
            SwingUtilities.invokeLater(() -> {
                applyOpenRouterUsage(snapshot, updatedLabel, balanceLabel, usageBar, usedLabel, remainingLabel, noteLabel);
                refreshButton.setEnabled(true);
                refreshButton.setText("Refresh usage");
            });
        });
    }

    private OpenRouterUsageSnapshot fetchOpenRouterUsage(ProviderInfo info) {
        try {
            String apiKey = CredentialResolver.resolveApiKey(info.envVar(), null);
            if (apiKey == null || apiKey.isBlank()) {
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
            String note = (limitReset == null || limitReset.isBlank()) ? null : "Resets " + limitReset;

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
                note = note == null ? "Balance unavailable: " + creditsError : note + " • Balance unavailable";
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
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }

        JsonNode root = JSON.readTree(response.body());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("Missing data object in response from " + url);
        }
        return data;
    }

    private void applyOpenRouterUsage(
        OpenRouterUsageSnapshot snapshot,
        JLabel updatedLabel,
        JLabel balanceLabel,
        JProgressBar usageBar,
        JLabel usedLabel,
        JLabel remainingLabel,
        JLabel noteLabel
    ) {
        updatedLabel.setText("Updated " + formatRelativeTime(snapshot.updatedAtEpochMs()));

        if (snapshot.errorMessage() != null) {
            balanceLabel.setText("Balance: n/a");
            usedLabel.setText("Used: n/a");
            remainingLabel.setText("Remaining: n/a");
            usageBar.setValue(0);
            noteLabel.setForeground(new Color(200, 50, 50));
            noteLabel.setText(snapshot.errorMessage());
            return;
        }

        balanceLabel.setText(snapshot.balance() == null
                ? "Balance: n/a"
                : "Balance: " + formatUsd(snapshot.balance()));

        if (snapshot.usedPercent() >= 0) {
            usageBar.setValue(snapshot.usedPercent());
            usedLabel.setText(snapshot.usedPercent() + "% used");
        } else {
            usageBar.setValue(0);
            usedLabel.setText("Used: n/a");
        }

        if (!Double.isNaN(snapshot.limit()) && !Double.isNaN(snapshot.remaining())) {
            remainingLabel.setText(formatUsd(snapshot.remaining()) + "/" + formatUsd(snapshot.limit()) + " left");
        } else {
            remainingLabel.setText("Remaining: n/a");
        }

        String note = snapshot.note();
        noteLabel.setForeground(new Color(214, 117, 0));
        noteLabel.setText(note == null || note.isBlank() ? "" : note);
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
        if (text == null || text.isBlank()) {
            return "Unknown error";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    private JPanel createCodexDiagnosticsPanel() {
        JPanel diagnosticsPanel = new JPanel();
        diagnosticsPanel.setLayout(new BoxLayout(diagnosticsPanel, BoxLayout.Y_AXIS));
        diagnosticsPanel.setOpaque(false);

        JLabel transportLabel = new JLabel();
        JLabel deltasLabel = new JLabel();
        JLabel fallbackLabel = new JLabel();
        JLabel failureLabel = new JLabel();
        JLabel appServerErrorLabel = new JLabel();
        JLabel updatedLabel = new JLabel();

        JButton refreshButton = new JButton("Refresh diagnostics");
        refreshButton.addActionListener(e -> refreshCodexDiagnostics(
            transportLabel,
            deltasLabel,
            fallbackLabel,
            failureLabel,
            appServerErrorLabel,
            updatedLabel
        ));

        diagnosticsPanel.add(transportLabel);
        diagnosticsPanel.add(deltasLabel);
        diagnosticsPanel.add(fallbackLabel);
        diagnosticsPanel.add(failureLabel);
        diagnosticsPanel.add(appServerErrorLabel);
        diagnosticsPanel.add(updatedLabel);
        diagnosticsPanel.add(Box.createVerticalStrut(6));
        diagnosticsPanel.add(refreshButton);

        refreshCodexDiagnostics(
            transportLabel,
            deltasLabel,
            fallbackLabel,
            failureLabel,
            appServerErrorLabel,
            updatedLabel
        );

        return diagnosticsPanel;
    }

    private void refreshCodexDiagnostics(
        JLabel transportLabel,
        JLabel deltasLabel,
        JLabel fallbackLabel,
        JLabel failureLabel,
        JLabel appServerErrorLabel,
        JLabel updatedLabel
    ) {
        CodexCliChatCompletionClient.DiagnosticsSnapshot snapshot = CodexCliChatCompletionClient.diagnosticsSnapshot();

        transportLabel.setText("Transport: " + snapshot.transport());
        deltasLabel.setText("Streaming deltas seen: " + yesNo(snapshot.sawStreamingDelta()));
        fallbackLabel.setText("Fallback used: " + yesNo(snapshot.fallbackUsed()));
        failureLabel.setText("Last failure: " + defaultValue(snapshot.lastFailureReason()));
        appServerErrorLabel.setText("Last app-server error: " + defaultValue(snapshot.lastAppServerError()));
        updatedLabel.setText("Updated: " + formatDiagnosticsTime(snapshot.updatedAtEpochMs()));

        Color failureColor = "none".equals(defaultValue(snapshot.lastFailureReason()))
                ? UIManager.getColor("Label.disabledForeground")
                : new Color(200, 50, 50);
        failureLabel.setForeground(failureColor);
        appServerErrorLabel.setForeground("none".equals(defaultValue(snapshot.lastAppServerError()))
                ? UIManager.getColor("Label.disabledForeground")
                : new Color(214, 117, 0));
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
            return seconds + "s ago";
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }

        return DIAGNOSTICS_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private String formatDiagnosticsTime(long epochMillis) {
        return epochMillis <= 0
                ? "n/a"
                : DIAGNOSTICS_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private String defaultValue(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String formatEnvVarNames(String envVarExpression) {
        return String.join(" / ", CredentialResolver.envVarCandidates(envVarExpression));
    }

    private boolean isProviderAvailable(String providerName, ProviderInfo info) {
        if (info == null) {
            return false;
        }

        if (info.authType() == AuthType.CLI_OAUTH) {
            return isCliOauthAvailable(providerName, info);
        }

        if (info.envVar() == null) {
            String configuredBaseUrl = readString(providerBaseUrlKey(providerName), info.defaultBaseUrl());
            return LocalServiceHealth.isReachable(configuredBaseUrl);
        }

        return CredentialResolver.hasRequiredCredentials(info.envVar());
    }

    private boolean isCliOauthAvailable(String providerName, ProviderInfo info) {
        Instant now = Instant.now();
        CliOauthAvailabilitySnapshot cached = cliOauthAvailabilityByProvider.get(providerName);
        if (cached != null && now.isBefore(cached.checkedAt().plus(CLI_OAUTH_AVAILABILITY_TTL))) {
            return cached.authorized();
        }

        triggerCliOauthAvailabilityRefresh(providerName, info.oauthCliSpec());
        return cached != null && cached.authorized();
    }

    private void cacheCliOauthAvailability(String providerName, boolean authorized) {
        cliOauthAvailabilityByProvider.put(providerName, new CliOauthAvailabilitySnapshot(authorized, Instant.now()));
    }

    private void triggerCliOauthAvailabilityRefresh(String providerName, OAuthCliSpec oauthCliSpec) {
        AtomicBoolean inFlight = cliOauthAvailabilityRefreshInFlightByProvider
                .computeIfAbsent(providerName, ignored -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                CliOAuthRunner.OAuthStatus status = CLI_OAUTH_RUNNER.checkStatus(oauthCliSpec);
                cacheCliOauthAvailability(providerName, status.authorized());
                SwingUtilities.invokeLater(providerList::repaint);
            } finally {
                inFlight.set(false);
            }
        });
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
        if (path == null || path.isBlank()) {
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
        return "provider." + providerName + ".baseUrl";
    }

    private String providerEnabledKey(String providerName) {
        return "provider." + providerName + ".enabled";
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Fonts.of(Font.PLAIN, 14));
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

    private record CliOauthAvailabilitySnapshot(boolean authorized, Instant checkedAt) {
    }

    record ProviderInfo(String envVar, String defaultBaseUrl, AuthType authType, OAuthCliSpec oauthCliSpec) {

        static ProviderInfo envVar(String envVar, String baseUrl) {
            return new ProviderInfo(envVar, baseUrl, AuthType.ENV_VAR, null);
        }

        static ProviderInfo local(String baseUrl) {
            return new ProviderInfo(null, baseUrl, AuthType.ENV_VAR, null);
        }

        static ProviderInfo cliOAuth(String baseUrl, OAuthCliSpec oauthCliSpec) {
            return new ProviderInfo(null, baseUrl, AuthType.CLI_OAUTH, oauthCliSpec);
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
            label.setFont(Fonts.of(Font.PLAIN, 13));
            label.setIconTextGap(8);

            String providerName = value instanceof String s ? s : "";
            ProviderInfo info = PROVIDERS.get(providerName);
            boolean available = info != null && isProviderAvailable(providerName, info);
            label.setText(providerName);
            label.setIcon(iconForProvider(providerName, available));
            return label;
        }
    }
}
