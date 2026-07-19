package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.provider.support.ProviderRuntimeSettings;
import com.github.drafael.chat4j.util.Fonts;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class ProvidersPanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final NumberFormat USD_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);
    private static final DateTimeFormatter DIAGNOSTICS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String CHAT4J_OAUTH_SOURCE = "Chat4J OAuth";
    private static final int DETAIL_LABEL_COLUMN_WIDTH = 100;
    private static final int DETAIL_COLUMN_GAP = 4;

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
    private static final Map<String, Icon> PROVIDER_BASE_ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<ProviderStatusIconKey, Icon> PROVIDER_STATUS_ICON_CACHE = new ConcurrentHashMap<>();

    private final Map<String, Boolean> authAvailabilityByProvider = new ConcurrentHashMap<>();
    private final Map<String, String> configuredBaseUrlByProvider = new LinkedHashMap<>();
    private final List<Runnable> authStatusRefreshers = new ArrayList<>();
    private final JList<String> providerList;
    private final ApiTokenFieldRegistry tokenFieldRegistry;
    private final SettingsCredentialChangeListener credentialChangeListener;
    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final ProviderAuthLifecycle authLifecycle = new ProviderAuthLifecycle();
    private final List<ApiTokenFieldPanel> tokenFields = new CopyOnWriteArrayList<>();
    private volatile String lastSaveError = "";
    private volatile boolean removed;

    static {
        PROVIDERS.put("Anthropic", ProviderInfo.envVar("ANTHROPIC_API_KEY", "https://api.anthropic.com"));
        PROVIDERS.put("OpenAI", ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"));
        PROVIDERS.put("Perplexity", ProviderInfo.envVar("PERPLEXITY_API_KEY", "https://api.perplexity.ai"));
        PROVIDERS.put("OpenAI Codex", ProviderInfo.codexOAuth("https://api.openai.com/v1"));
        PROVIDERS.put("GitHub Copilot", ProviderInfo.copilotOAuth("https://api.githubcopilot.com"));
        PROVIDERS.put("Google AI", ProviderInfo.envVar("GEMINI_API_KEY|GOOGLEAI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai"));
        PROVIDERS.put("OpenRouter", ProviderInfo.envVar("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1"));
        PROVIDERS.put("Groq", ProviderInfo.envVar("GROQ_API_KEY", "https://api.groq.com/openai/v1"));
        PROVIDERS.put("DeepSeek", ProviderInfo.envVar("DEEPSEEK_API_KEY", "https://api.deepseek.com/v1"));
        PROVIDERS.put("Mistral", ProviderInfo.envVar("MISTRAL_API_KEY", "https://api.mistral.ai/v1"));
        PROVIDERS.put("xAI", ProviderInfo.envVar("XAI_API_KEY", "https://api.x.ai/v1"));
        PROVIDERS.put("LM Studio", ProviderInfo.local("http://localhost:1234/v1"));
        PROVIDERS.put("Ollama", ProviderInfo.local("http://localhost:11434/v1"));
    }

    public ProvidersPanel(
            @NonNull SettingsRepository settingsRepo,
            @NonNull ApiTokenFieldRegistry tokenFieldRegistry,
            SettingsCredentialChangeListener credentialChangeListener,
            @NonNull CopilotAuthResolver copilotAuthResolver,
            @NonNull CodexAuthResolver codexAuthResolver
    ) {
        super(settingsRepo);
        this.tokenFieldRegistry = tokenFieldRegistry;
        this.credentialChangeListener = credentialChangeListener == null
                ? SettingsCredentialChangeListener.NO_OP
                : credentialChangeListener;
        this.copilotAuthResolver = copilotAuthResolver;
        this.codexAuthResolver = codexAuthResolver;

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

    @Override
    public void addNotify() {
        super.addNotify();
        authLifecycle.activate();
        removed = false;
        authStatusRefreshers.forEach(Runnable::run);
    }

    @Override
    public void removeNotify() {
        removed = true;
        authLifecycle.deactivate();
        super.removeNotify();
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        String conflict = tokenFieldRegistry.conflictMessage();
        if (StringUtils.isNotBlank(conflict)) {
            lastSaveError = conflict;
            return CompletableFuture.completedFuture(false);
        }
        List<CompletableFuture<Boolean>> saveFutures = tokenFields.stream()
                .filter(ApiTokenFieldPanel::dirty)
                .map(ApiTokenFieldPanel::savePendingChangesAsync)
                .toList();
        CompletableFuture<?>[] saves = saveFutures.toArray(CompletableFuture[]::new);
        if (saves.length == 0) {
            lastSaveError = "";
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.allOf(saves).thenApply(ignored -> {
            boolean allSaved = saveFutures.stream().allMatch(CompletableFuture::join);
            String error = tokenFields.stream()
                    .map(ApiTokenFieldPanel::lastSaveError)
                    .filter(StringUtils::isNotBlank)
                    .findFirst()
                    .orElse(allSaved ? "" : "One or more API tokens could not be saved.");
            lastSaveError = error;
            return allSaved && StringUtils.isBlank(error);
        });
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "Provider settings";
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

        String configuredBaseUrl = configuredProviderBaseUrl(name, info);
        configuredBaseUrlByProvider.put(name, configuredBaseUrl);
        boolean localProvider = isLocalProvider(name, info);
        boolean localReachable = localProvider
                && LocalServiceHealth.lastKnownReachable(configuredBaseUrl);

        row = addSectionHeader(panel, gbc, row, "Connection");
        applyDetailRowInsets(gbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(label("Status"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JLabel statusLabel = createStatusLabel(name, info, configuredBaseUrl, localReachable);
        panel.add(statusLabel, gbc);

        JComponent missingApiKeyInfoPanel = shouldShowMissingApiKeyInfo(info)
                ? createMissingApiKeyInfoPanel(name, info)
                : null;

        if (shouldShowApiTokenField(info)) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            panel.add(label("API token"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            ApiTokenFieldPanel tokenField = withPreferredWidth(new ApiTokenFieldPanel(
                    info.envVar(),
                    tokenFieldRegistry,
                    credentialChangeListener,
                    () -> refreshProviderCredentialUi(statusLabel, name, info, missingApiKeyInfoPanel)
            ), 420);
            tokenFields.add(tokenField);
            panel.add(tokenField, gbc);
        }

        if (missingApiKeyInfoPanel != null) {
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            panel.add(missingApiKeyInfoPanel, gbc);
            gbc.gridwidth = 1;
        }

        LocalProviderInfo localProviderInfo = localProvider
                ? createLocalProviderInfo(name)
                : null;
        if (localProvider) {
            updateLocalProviderInfo(localProviderInfo, configuredBaseUrl, localReachable);
            row++;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            panel.add(localProviderInfo.panel(), gbc);
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
                configuredBaseUrlByProvider.put(name, updatedBaseUrl);
                if (localProvider) {
                    refreshLocalProviderStatus(statusLabel, baseUrl, localProviderInfo, updatedBaseUrl);
                }
                providerList.repaint();
            }
        );
        panel.add(baseUrl, gbc);
        configuredBaseUrlByProvider.put(name, baseUrl.getText());
        if (localProvider) {
            refreshLocalProviderStatus(statusLabel, baseUrl, localProviderInfo, baseUrl.getText());
        }

        if (info.authType() == AuthType.COPILOT_OAUTH || info.authType() == AuthType.CODEX_OAUTH) {
            row = addSectionHeader(panel, gbc, row + 1, "Authentication");
            applyDetailRowInsets(gbc);

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
            applyDetailRowInsets(gbc);

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

        if (info.authType() == AuthType.COPILOT_OAUTH || info.authType() == AuthType.CODEX_OAUTH) {
            status.setText("Checking authorization...");
            status.setForeground(warningForeground());
            return status;
        }

        if (info.envVar() == null) {
            applyLocalStatus(status, configuredBaseUrl, localReachable);
            return status;
        }

        applyApiCredentialStatus(status, info);
        return status;
    }

    void refreshProviderCredentialUi(JLabel statusLabel, String providerName, ProviderInfo info, JComponent missingApiKeyInfoPanel) {
        applyApiCredentialStatus(statusLabel, info);
        if (missingApiKeyInfoPanel != null) {
            missingApiKeyInfoPanel.setVisible(shouldShowMissingApiKeyInfo(info));
            Container parent = missingApiKeyInfoPanel.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }
        providerList.repaint();
        if ("OpenRouter".equals(providerName)) {
            setStatusInfo("OpenRouter token updated. Refresh usage to load the latest account data.");
        }
    }

    private void applyApiCredentialStatus(JLabel status, ProviderInfo info) {
        ApiCredentialStatus credentialStatus = CredentialResolver.resolveCredentialStatus(info.envVar(), null);
        switch (credentialStatus.source()) {
            case SAVED_TOKEN -> {
                status.setText("\u2713 Saved token configured");
                status.setForeground(successForeground());
            }
            case PROCESS_ENV -> {
                status.setText("\u2713 %s from environment".formatted(credentialStatus.credentialId()));
                status.setForeground(successForeground());
            }
            case SHELL_ENV -> {
                status.setText("\u2713 %s from shell environment".formatted(credentialStatus.credentialId()));
                status.setForeground(successForeground());
            }
            case ERROR -> {
                status.setText("\u26A0 Could not read saved token");
                status.setForeground(warningForeground());
            }
            default -> {
                status.setText("\u2717 %s not set".formatted(formatEnvVarNames(info.envVar())));
                status.setForeground(errorForeground());
            }
        }
    }

    private void applyLocalStatus(JLabel status, String configuredBaseUrl, boolean reachable) {
        if (reachable) {
            status.setText("\u2713 Running at %s".formatted(configuredBaseUrl));
            status.setForeground(successForeground());
        } else {
            status.setText("\u2717 Not running at %s".formatted(configuredBaseUrl));
            status.setForeground(errorForeground());
        }
    }

    private boolean shouldShowApiTokenField(ProviderInfo info) {
        return info.authType() == AuthType.ENV_VAR && StringUtils.isNotBlank(info.envVar());
    }

    private boolean shouldShowMissingApiKeyInfo(ProviderInfo info) {
        if (!shouldShowApiTokenField(info)) {
            return false;
        }
        return !CredentialResolver.hasRequiredCredentials(info.envVar());
    }

    private JComponent createMissingApiKeyInfoPanel(String providerName, ProviderInfo info) {
        JPanel infoPanel = new JPanel(new BorderLayout(0, 8));
        infoPanel.setOpaque(true);
        infoPanel.setBorder(warningBoxBorder());
        infoPanel.setBackground(warningBoxBackground());

        JLabel header = new JLabel("\u26A0 API token setup required");
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        header.setForeground(warningBoxTitleForeground());
        infoPanel.add(header, BorderLayout.NORTH);

        String primaryEnvVar = CredentialResolver.envVarCandidates(info.envVar()).stream()
                .findFirst()
                .orElse("API_KEY");
        String acceptedEnvVars = formatEnvVarNames(info.envVar());

        JTextArea instructions = new JTextArea("""
                No API token is configured for %s.

                How to set it up:
                1) Create an API key in your provider dashboard.
                2) Paste it into the API token field above; Chat4J saves it when the field loses focus.
                   Or add it to your shell profile:
                   export %s=<your-api-key>
                3) Restart Chat4J only if you choose the shell-profile option.

                Accepted env vars: %s
                """.formatted(providerName, primaryEnvVar, acceptedEnvVars));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setBorder(null);
        Fonts.apply(instructions, Font.PLAIN, Fonts.SIZE_COMPACT);
        instructions.setForeground(messageBoxForeground());
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

    private boolean isLocalProvider(String providerName, ProviderInfo info) {
        return info.authType() == AuthType.ENV_VAR
                && info.envVar() == null
                && LOCAL_PROVIDER_HELP.containsKey(providerName);
    }

    private LocalProviderInfo createLocalProviderInfo(String providerName) {
        LocalProviderHelp localHelp = LOCAL_PROVIDER_HELP.get(providerName);
        JPanel infoPanel = new JPanel(new BorderLayout(0, 8));
        infoPanel.setOpaque(true);
        infoPanel.setBorder(infoBoxBorder());
        infoPanel.setBackground(infoBoxBackground());

        JLabel header = new JLabel("\u2139 Local server setup");
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        header.setForeground(infoBoxTitleForeground());
        infoPanel.add(header, BorderLayout.NORTH);

        JTextArea instructions = new JTextArea();
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setBorder(null);
        Fonts.apply(instructions, Font.PLAIN, Fonts.SIZE_COMPACT);
        instructions.setForeground(messageBoxForeground());
        infoPanel.add(instructions, BorderLayout.CENTER);

        if (StringUtils.isNotBlank(localHelp.docsUrl())) {
            JButton openDocsButton = new JButton("Open setup guide");
            openDocsButton.addActionListener(e -> openInBrowser(localHelp.docsUrl()));

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttonRow.setOpaque(false);
            buttonRow.add(openDocsButton);
            infoPanel.add(buttonRow, BorderLayout.SOUTH);
        }

        return new LocalProviderInfo(infoPanel, instructions, providerName, localHelp.instruction());
    }

    private void refreshLocalProviderStatus(
            JLabel statusLabel,
            JTextField baseUrlField,
            LocalProviderInfo providerInfo,
            String baseUrl
    ) {
        boolean cachedReachable = LocalServiceHealth.isReachableNonBlocking(baseUrl);
        applyLocalStatus(statusLabel, baseUrl, cachedReachable);
        updateLocalProviderInfo(providerInfo, baseUrl, cachedReachable);

        Thread.startVirtualThread(() -> {
            boolean reachable = LocalServiceHealth.isReachable(baseUrl);
            if (removed) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (removed || !Strings.CS.equals(baseUrlField.getText(), baseUrl)) {
                    return;
                }
                applyLocalStatus(statusLabel, baseUrl, reachable);
                updateLocalProviderInfo(providerInfo, baseUrl, reachable);
                providerList.repaint();
            });
        });
    }

    private void updateLocalProviderInfo(LocalProviderInfo providerInfo, String baseUrl, boolean reachable) {
        providerInfo.instructions().setText("""
                %s is not reachable at %s.

                How to fix:
                1) %s
                2) Ensure the API endpoint remains available.
                3) If needed, update the Base URL below.
                """.formatted(providerInfo.providerName(), baseUrl, providerInfo.instruction()));
        providerInfo.panel().setVisible(!reachable);
        Container parent = providerInfo.panel().getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private void openInBrowser(String url) {
        openInBrowser(url, authLifecycle.currentGeneration());
    }

    private void openInBrowser(String url, long lifecycleGeneration) {
        if (SwingUtilities.isEventDispatchThread()) {
            Thread.startVirtualThread(() -> openInBrowser(url, lifecycleGeneration));
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            applyBrowserStatusIfCurrent(
                    lifecycleGeneration,
                    () -> setStatusError("Unable to open browser automatically.")
            );
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
            applyBrowserStatusIfCurrent(lifecycleGeneration, () -> setStatusInfo("Opened browser."));
        } catch (Exception e) {
            String failureDescription = sanitizedBrowserFailureDescription(e);
            log.warn("Failed to open URL {}: {}", sanitizedUrlForDiagnostics(url), failureDescription);
            applyBrowserStatusIfCurrent(
                    lifecycleGeneration,
                    () -> setStatusError("Failed to open URL: %s".formatted(failureDescription))
            );
        }
    }

    private void applyBrowserStatusIfCurrent(long lifecycleGeneration, Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (authLifecycle.isCurrent(lifecycleGeneration)) {
                update.run();
            }
        });
    }

    static String sanitizedBrowserFailureDescription(Throwable failure) {
        return failure == null ? "Unknown error" : failure.getClass().getSimpleName();
    }

    static String sanitizedUrlForDiagnostics(String url) {
        try {
            URI uri = URI.create(url);
            if (StringUtils.isBlank(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
                return "<invalid-url>";
            }
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }

    private void startAuthWorker(long lifecycleGeneration, Runnable action) {
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                action.run();
            } finally {
                authLifecycle.unregister(Thread.currentThread());
            }
        });
        if (authLifecycle.register(lifecycleGeneration, worker, worker::interrupt)) {
            worker.start();
        }
    }

    private BooleanSupplier authCancellationRequested(long lifecycleGeneration) {
        return () -> Thread.currentThread().isInterrupted()
                || !authLifecycle.isCurrent(lifecycleGeneration);
    }

    private void refreshCopilotAuthControls(String providerName, JLabel statusLabel, JButton authButton) {
        long lifecycleGeneration = authLifecycle.currentGeneration();
        startAuthWorker(lifecycleGeneration, () -> {
            CopilotAuthResolver.CopilotAuthStatus status = copilotAuthResolver.resolveStatus();
            if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (authLifecycle.isCurrent(lifecycleGeneration)) {
                    applyCopilotAuthControls(providerName, statusLabel, authButton, status, null);
                }
            });
        });
    }

    private void refreshCodexAuthControls(String providerName, JLabel statusLabel, JButton authButton) {
        long lifecycleGeneration = authLifecycle.currentGeneration();
        startAuthWorker(lifecycleGeneration, () -> {
            CodexAuthResolver.CodexAuthStatus status = codexAuthResolver.resolveStatus();
            if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (authLifecycle.isCurrent(lifecycleGeneration)) {
                    applyCodexAuthControls(providerName, statusLabel, authButton, status, null);
                }
            });
        });
    }

    private void configureCopilotAuthAction(String providerName, JLabel statusLabel, JButton authButton) {
        authButton.setText("Checking...");
        authButton.setEnabled(false);
        authStatusRefreshers.add(() -> refreshCopilotAuthControls(providerName, statusLabel, authButton));

        authButton.addActionListener(event -> {
            long lifecycleGeneration = authLifecycle.currentGeneration();
            authButton.setEnabled(false);
            authButton.setText("Working...");

            startAuthWorker(lifecycleGeneration, () -> {
                BooleanSupplier cancellationRequested = authCancellationRequested(lifecycleGeneration);
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                CopilotAuthResolver.CopilotAuthStatus initialStatus = copilotAuthResolver.resolveStatus();
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                CopilotAuthResolver.CopilotAuthActionResult actionResult = initialStatus.authorized()
                        && Strings.CS.equals(initialStatus.source(), CHAT4J_OAUTH_SOURCE)
                        ? copilotAuthResolver.logout(cancellationRequested)
                        : runCopilotLoginFlow(lifecycleGeneration, statusLabel, cancellationRequested);
                if (actionResult.success()) {
                    notifyProviderAuthChangedSafely(providerName);
                }
                if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                    if (actionResult.success()) {
                        refreshCopilotAuthControls(providerName, statusLabel, authButton);
                    }
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    SwingUtilities.invokeLater(() -> {
                        if (authLifecycle.isCurrent(lifecycleGeneration)) {
                            applyCopilotAuthControls(
                                    providerName,
                                    statusLabel,
                                    authButton,
                                    initialStatus,
                                    actionResult.success() ? null : actionResult.message()
                            );
                        }
                    });
                    return;
                }
                CopilotAuthResolver.CopilotAuthStatus updatedStatus = copilotAuthResolver.resolveStatus();
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                CopilotAuthResolver.CopilotAuthActionResult finalActionResult = actionResult;
                SwingUtilities.invokeLater(() -> {
                    if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                        return;
                    }
                    applyCopilotAuthControls(
                            providerName,
                            statusLabel,
                            authButton,
                            updatedStatus,
                            finalActionResult.success() ? null : finalActionResult.message()
                    );
                });
            });
        });
    }

    private void notifyProviderAuthChangedSafely(String providerName) {
        try {
            credentialChangeListener.providerAuthChanged(providerName);
        } catch (RuntimeException e) {
            log.warn("Failed to publish {} authentication change ({})", providerName, e.getClass().getSimpleName());
        }
    }

    private CopilotAuthResolver.CopilotAuthActionResult runCopilotLoginFlow(
            long lifecycleGeneration,
            JLabel statusLabel,
            BooleanSupplier cancellationRequested
    ) {
        CompletableFuture<JDialog> loginDialogFuture = new CompletableFuture<>();
        if (!authLifecycle.register(lifecycleGeneration, loginDialogFuture, () -> loginDialogFuture.complete(null))) {
            return CopilotAuthResolver.CopilotAuthActionResult.failure("GitHub Copilot login cancelled.");
        }

        AtomicBoolean dialogCancellationRequested = new AtomicBoolean();
        Thread authWorker = Thread.currentThread();
        Runnable cancelLogin = authFlowCancellation(authWorker, dialogCancellationRequested);
        BooleanSupplier flowCancellationRequested = () -> cancellationRequested.getAsBoolean()
                || dialogCancellationRequested.get();
        try {
            return copilotAuthResolver.login(
                    flowCancellationRequested,
                    ignored -> false,
                    challenge -> SwingUtilities.invokeLater(() -> {
                        if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                            loginDialogFuture.complete(null);
                            return;
                        }
                        try {
                            loginDialogFuture.complete(showCopilotAuthLoginProgress(
                                    lifecycleGeneration,
                                    statusLabel,
                                    challenge,
                                    cancelLogin
                            ));
                        } catch (Exception e) {
                            cancelLogin.run();
                            loginDialogFuture.completeExceptionally(e);
                        }
                    })
            );
        } catch (Exception e) {
            if (flowCancellationRequested.getAsBoolean()) {
                return CopilotAuthResolver.CopilotAuthActionResult.failure("GitHub Copilot login cancelled.");
            }
            log.warn("Copilot OAuth action failed ({})", e.getClass().getSimpleName());
            return CopilotAuthResolver.CopilotAuthActionResult.failure("GitHub Copilot login failed.");
        } finally {
            loginDialogFuture.whenComplete((dialog, error) -> {
                authLifecycle.unregister(loginDialogFuture);
                if (dialog != null) {
                    authLifecycle.unregister(dialog);
                    disposeAuthDialog(dialog);
                }
            });
        }
    }

    private void configureCodexAuthAction(String providerName, JLabel statusLabel, JButton authButton) {
        authButton.setText("Checking...");
        authButton.setEnabled(false);
        authStatusRefreshers.add(() -> refreshCodexAuthControls(providerName, statusLabel, authButton));

        authButton.addActionListener(event -> {
            long lifecycleGeneration = authLifecycle.currentGeneration();
            authButton.setEnabled(false);
            authButton.setText("Working...");
            statusLabel.setText("Preparing OpenAI Codex login...");
            statusLabel.setForeground(warningForeground());

            startAuthWorker(lifecycleGeneration, () -> {
                BooleanSupplier cancellationRequested = authCancellationRequested(lifecycleGeneration);
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                CodexAuthResolver.CodexAuthStatus initialStatus = codexAuthResolver.resolveStatus();
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                CodexAuthResolver.CodexAuthActionResult actionResult = initialStatus.authorized()
                        && Strings.CS.equals(initialStatus.source(), CHAT4J_OAUTH_SOURCE)
                        ? codexAuthResolver.logout(cancellationRequested)
                        : runCodexLoginDialogFlow(lifecycleGeneration, cancellationRequested);
                if (actionResult.success()) {
                    notifyProviderAuthChangedSafely(providerName);
                }
                if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                    if (actionResult.success()) {
                        refreshCodexAuthControls(providerName, statusLabel, authButton);
                    }
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    SwingUtilities.invokeLater(() -> {
                        if (authLifecycle.isCurrent(lifecycleGeneration)) {
                            applyCodexAuthControls(
                                    providerName,
                                    statusLabel,
                                    authButton,
                                    initialStatus,
                                    actionResult.success() ? null : actionResult.message()
                            );
                        }
                    });
                    return;
                }
                CodexAuthResolver.CodexAuthStatus updatedStatus = codexAuthResolver.resolveStatus();
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (!authLifecycle.isCurrent(lifecycleGeneration)) {
                        return;
                    }
                    applyCodexAuthControls(
                            providerName,
                            statusLabel,
                            authButton,
                            updatedStatus,
                            actionResult.success() ? null : actionResult.message()
                    );
                });
            });
        });
    }

    private CodexAuthResolver.CodexAuthActionResult runCodexLoginDialogFlow(
            long lifecycleGeneration,
            BooleanSupplier cancellationRequested
    ) {
        AtomicBoolean dialogCancellationRequested = new AtomicBoolean();
        Runnable cancelLogin = authFlowCancellation(Thread.currentThread(), dialogCancellationRequested);
        BooleanSupplier flowCancellationRequested = () -> cancellationRequested.getAsBoolean()
                || dialogCancellationRequested.get();
        return codexAuthResolver.login(
                flowCancellationRequested,
                challenge -> collectCodexAuthorizationInput(lifecycleGeneration, challenge, cancelLogin)
        );
    }

    private String collectCodexAuthorizationInput(
            long lifecycleGeneration,
            CodexAuthResolver.CodexLoginChallenge challenge,
            Runnable cancelLogin
    ) throws Exception {
        if (!authLifecycle.isCurrent(lifecycleGeneration)) {
            return "";
        }

        CodexAuthResolver.CodexCallbackWait callbackWait = codexAuthResolver.startCallbackWait(challenge);
        try {
            boolean registered = authLifecycle.register(lifecycleGeneration, callbackWait, () -> {
                callbackWait.callbackInputFuture().complete("");
                callbackWait.close();
            });
            if (!registered) {
                return "";
            }
            return showCodexLoginDialogOnEdt(lifecycleGeneration, challenge, callbackWait, cancelLogin);
        } finally {
            authLifecycle.unregister(callbackWait);
            callbackWait.close();
        }
    }

    private String showCodexLoginDialogOnEdt(
            long lifecycleGeneration,
            CodexAuthResolver.CodexLoginChallenge challenge,
            CodexAuthResolver.CodexCallbackWait callbackWait,
            Runnable cancelLogin
    ) throws Exception {
        if (!authLifecycle.isCurrent(lifecycleGeneration)) {
            return "";
        }
        long timeoutDeadlineNanos = challenge.timeoutDeadlineNanos();
        if (SwingUtilities.isEventDispatchThread()) {
            return showCodexLoginDialog(
                    lifecycleGeneration,
                    challenge,
                    callbackWait,
                    timeoutDeadlineNanos,
                    cancelLogin
            );
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        Thread waitingWorker = Thread.currentThread();
        if (!authLifecycle.register(
                lifecycleGeneration,
                result,
                authDialogCancellation(waitingWorker, result)
        )) {
            return "";
        }
        try {
            result.orTimeout(
                    Math.max(remainingTimeoutNanos(timeoutDeadlineNanos), 1L),
                    TimeUnit.NANOSECONDS
            );
            SwingUtilities.invokeLater(() -> {
                if (!authLifecycle.isCurrent(lifecycleGeneration) || result.isDone()) {
                    result.complete("");
                    return;
                }
                try {
                    result.complete(showCodexLoginDialog(
                            lifecycleGeneration,
                            challenge,
                            callbackWait,
                            timeoutDeadlineNanos,
                            cancelLogin
                    ));
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
            return awaitAuthDialogResult(result);
        } finally {
            authLifecycle.unregister(result);
        }
    }

    private static long remainingTimeoutNanos(long timeoutDeadlineNanos) {
        return timeoutDeadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : Math.max(timeoutDeadlineNanos - System.nanoTime(), 0L);
    }

    static Runnable authDialogCancellation(Thread waitingWorker, CompletableFuture<String> result) {
        return () -> {
            waitingWorker.interrupt();
            result.complete("");
        };
    }

    static Runnable authFlowCancellation(Thread authWorker, AtomicBoolean cancellationRequested) {
        return () -> {
            cancellationRequested.set(true);
            authWorker.interrupt();
        };
    }

    static Timer createAuthDialogTimeout(
            long timeoutDeadlineNanos,
            AtomicBoolean timedOut,
            CodexAuthResolver.CodexCallbackWait callbackWait,
            Runnable dismissDialog
    ) {
        long remainingTimeoutNanos = remainingTimeoutNanos(timeoutDeadlineNanos);
        int timeoutMillis = (int) Math.max(
                1L,
                Math.min(TimeUnit.NANOSECONDS.toMillis(remainingTimeoutNanos), Integer.MAX_VALUE)
        );
        Timer timeoutTimer = new Timer(timeoutMillis, event -> {
            timedOut.set(true);
            callbackWait.close();
            dismissDialog.run();
        });
        timeoutTimer.setRepeats(false);
        return timeoutTimer;
    }

    static String awaitAuthDialogResult(CompletableFuture<String> result) throws Exception {
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private String showCodexLoginDialog(
            long lifecycleGeneration,
            CodexAuthResolver.CodexLoginChallenge challenge,
            CodexAuthResolver.CodexCallbackWait callbackWait,
            long timeoutDeadlineNanos,
            Runnable cancelLogin
    ) throws TimeoutException {
        if (!authLifecycle.isCurrent(lifecycleGeneration)) {
            return "";
        }
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
        callbackStatus.setForeground(warningForeground());

        JButton openBrowserButton = new JButton("Open browser");
        JButton copyLoginUrlButton = new JButton("Copy login URL");
        JButton completeButton = new JButton("Complete login with callback URL");
        JButton cancelButton = new JButton("Cancel");

        final String[] resultHolder = new String[1];
        var timedOut = new AtomicBoolean();
        Timer timeoutTimer = createAuthDialogTimeout(
                timeoutDeadlineNanos,
                timedOut,
                callbackWait,
                dialog::dispose
        );

        callbackWait.callbackInputFuture().whenComplete((callbackInput, throwable) -> {
            if (throwable != null || StringUtils.isBlank(callbackInput)) {
                return;
            }

            SwingUtilities.invokeLater(() -> {
                if (!authLifecycle.isCurrent(lifecycleGeneration) || !dialog.isDisplayable()) {
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
        cancelButton.addActionListener(e -> {
            cancelLogin.run();
            dialog.dispose();
        });

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

            @Override
            public void windowClosing(WindowEvent e) {
                cancelLogin.run();
            }
        });
        if (!registerAuthDialog(lifecycleGeneration, dialog)) {
            return "";
        }
        try {
            timeoutTimer.start();
            dialog.setVisible(true);
            if (timedOut.get()) {
                throw new TimeoutException("OpenAI Codex login timed out");
            }
            return resultHolder[0];
        } finally {
            timeoutTimer.stop();
            authLifecycle.unregister(dialog);
        }
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
            long lifecycleGeneration,
            JLabel statusLabel,
            CopilotAuthResolver.CopilotLoginChallenge challenge,
            Runnable cancelLogin
    ) {
        String message = "Complete GitHub Copilot sign-in using code %s at %s."
                .formatted(challenge.userCode(), challenge.verificationUri());
        statusLabel.setText(message);
        statusLabel.setForeground(warningForeground());
        setStatusInfo("GitHub Copilot login is waiting for browser authorization.");

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
                Chat4J opens the login page and copies the code when this dialog appears.
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
        closeButton.addActionListener(e -> {
            cancelLogin.run();
            dialog.dispose();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(openBrowserButton);
        buttons.add(copyCodeButton);
        buttons.add(closeButton);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setSize(new Dimension(560, 240));
        dialog.setLocationRelativeTo(this);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                copyToClipboard(challenge.userCode(), "Copied GitHub Copilot login code.");
                openInBrowser(challenge.verificationUri());
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancelLogin.run();
            }
        });
        if (!registerAuthDialog(lifecycleGeneration, dialog)) {
            return null;
        }
        dialog.setVisible(true);
        return dialog;
    }

    private boolean registerAuthDialog(long lifecycleGeneration, JDialog dialog) {
        boolean registered = authLifecycle.register(
                lifecycleGeneration,
                dialog,
                () -> disposeAuthDialog(dialog)
        );
        if (registered) {
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    authLifecycle.unregister(dialog);
                }
            });
        }
        return registered;
    }

    private static void disposeAuthDialog(JDialog dialog) {
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
        } else {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    private void applyCopilotAuthControls(
            String providerName,
            JLabel statusLabel,
            JButton authButton,
            CopilotAuthResolver.CopilotAuthStatus status,
            String failureMessage
    ) {
        cacheAuthAvailability(providerName, status.authorized());
        applyCopilotAuthStatus(statusLabel, status);

        boolean chat4jSession = status.authorized() && Strings.CS.equals(status.source(), CHAT4J_OAUTH_SOURCE);
        boolean oauthClientConfigured = copilotAuthResolver.isOAuthClientConfigured();

        authButton.setOpaque(true);
        if (chat4jSession) {
            Color destructiveBackground = errorForeground();
            authButton.setText("Log out");
            authButton.setEnabled(true);
            authButton.setForeground(readableForegroundOn(destructiveBackground));
            authButton.setBackground(destructiveBackground);
            authButton.setToolTipText("Sign out Chat4J OAuth session");
        } else if (!oauthClientConfigured) {
            authButton.setText("Configure OAuth App");
            authButton.setEnabled(false);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText(copilotAuthResolver.oauthClientConfigurationHint());

            if (!status.authorized() && StringUtils.isBlank(failureMessage)) {
                statusLabel.setText(copilotAuthResolver.oauthClientConfigurationHint());
                statusLabel.setForeground(warningForeground());
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
            statusLabel.setForeground(errorForeground());
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
        cacheAuthAvailability(providerName, status.authorized());
        applyCodexAuthStatus(statusLabel, status);

        boolean chat4jSession = status.authorized() && Strings.CS.equals(status.source(), CHAT4J_OAUTH_SOURCE);
        boolean oauthClientConfigured = codexAuthResolver.isOAuthClientConfigured();

        authButton.setOpaque(true);
        if (chat4jSession) {
            Color destructiveBackground = errorForeground();
            authButton.setText("Log out");
            authButton.setEnabled(true);
            authButton.setForeground(readableForegroundOn(destructiveBackground));
            authButton.setBackground(destructiveBackground);
            authButton.setToolTipText("Sign out Chat4J OAuth session");
        } else if (!oauthClientConfigured) {
            authButton.setText("Configure OAuth App");
            authButton.setEnabled(false);
            authButton.setForeground(UIManager.getColor("Button.foreground"));
            authButton.setBackground(UIManager.getColor("Button.background"));
            authButton.setToolTipText(codexAuthResolver.oauthClientConfigurationHint());

            if (!status.authorized() && StringUtils.isBlank(failureMessage)) {
                statusLabel.setText(codexAuthResolver.oauthClientConfigurationHint());
                statusLabel.setForeground(warningForeground());
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
            statusLabel.setForeground(errorForeground());
        }

        providerList.repaint();
    }

    private void applyCopilotAuthStatus(JLabel statusLabel, CopilotAuthResolver.CopilotAuthStatus status) {
        statusLabel.setText(status.authorized() ? "Authorized" : "Not authorized");
        statusLabel.setForeground(status.authorized() ? successForeground() : errorForeground());
    }

    private void applyCodexAuthStatus(JLabel statusLabel, CodexAuthResolver.CodexAuthStatus status) {
        statusLabel.setText(status.authorized() ? "Authorized" : "Not authorized");
        statusLabel.setForeground(status.authorized() ? successForeground() : errorForeground());
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
        noteLabel.setForeground(warningForeground());

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
            noteLabel.setForeground(errorForeground());
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
        noteLabel.setForeground(warningForeground());
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

        if (info.authType() == AuthType.COPILOT_OAUTH || info.authType() == AuthType.CODEX_OAUTH) {
            return authAvailabilityByProvider.getOrDefault(providerName, false);
        }

        if (info.envVar() == null) {
            String configuredBaseUrl = configuredBaseUrlByProvider.getOrDefault(
                    providerName,
                    info.defaultBaseUrl()
            );
            return LocalServiceHealth.isReachableNonBlocking(configuredBaseUrl);
        }

        return CredentialResolver.hasRequiredCredentials(info.envVar());
    }

    private void cacheAuthAvailability(String providerName, boolean authorized) {
        authAvailabilityByProvider.put(providerName, authorized);
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
                Color foreground = ObjectUtils.firstNonNull(
                        component != null ? component.getForeground() : null,
                        UIManager.getColor("Label.foreground"),
                        new Color(80, 80, 80)
                );
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
        statusPanel.add(statusLabel(), BorderLayout.CENTER);
        return statusPanel;
    }

    String configuredProviderBaseUrl(String providerName, ProviderInfo info) {
        String value = readString(providerBaseUrlKey(providerName), info.defaultBaseUrl());
        return fallbackBlankProviderBaseUrl(value, info.defaultBaseUrl());
    }

    static String fallbackBlankProviderBaseUrl(String configuredValue, String defaultBaseUrl) {
        return StringUtils.isBlank(configuredValue) ? defaultBaseUrl : configuredValue.trim();
    }

    private String providerBaseUrlKey(String providerName) {
        return providerRuntimeSettings(providerName).baseUrlKey();
    }

    private String providerEnabledKey(String providerName) {
        return providerRuntimeSettings(providerName).enabledKey();
    }

    private ProviderRuntimeSettings providerRuntimeSettings(String providerName) {
        return ProviderRuntimeSettings.forProvider(settingsRepo(), providerName);
    }

    private JComponent wrapLeading(JComponent component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(component);
        return row;
    }

    private void applyDetailRowInsets(GridBagConstraints gbc) {
        gbc.insets = new Insets(4, 0, 4, DETAIL_COLUMN_GAP);
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

    private record LocalProviderInfo(
            JPanel panel,
            JTextArea instructions,
            String providerName,
            String instruction
    ) {
    }

    private record ProviderStatusIconKey(String providerName, boolean available) {
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
