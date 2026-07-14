package com.github.drafael.chat4j.chat.model;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderDef;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

@Slf4j
public class ModelSelectorPopup extends JDialog {

    private static final Duration MODEL_REFRESH_TTL = Duration.ofHours(12);
    private static final Duration LOCAL_PROVIDER_REFRESH_TTL = Duration.ofMinutes(5);
    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final Set<String> LOCAL_HEALTH_GATED_PROVIDERS = Set.of("LM Studio", "Ollama");
    private static final int POPUP_MIN_WIDTH = 340;
    private static final int POPUP_MAX_WIDTH = 768;
    private static final int POPUP_HEIGHT = 560;
    private static final int POPUP_HORIZONTAL_MARGIN = 24;
    private static final int POPUP_CHROME_WIDTH = 170;
    private static final float POPUP_WIDTH_REDUCTION_FACTOR = 0.8f;
    private static final Map<String, String> PROVIDER_ICON_PATHS = Map.ofEntries(
            Map.entry("Anthropic", "/icons/providers/anthropic.svg"),
            Map.entry("OpenAI", "/icons/providers/openai.svg"),
            Map.entry("Perplexity", "/icons/providers/perplexity.svg"),
            Map.entry(CODEX_PROVIDER_NAME, "/icons/providers/codex.svg"),
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
    private static final Map<String, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();

    private final JTextField searchField;
    private final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final JToggleButton allToggle;
    private final JToggleButton favoritesToggle;
    private final BiConsumer<String, String> onSelect;
    private final BiPredicate<List<ProviderDef>, Long> onProvidersLoaded;
    private final Runnable onFavoritesChanged;
    private final Runnable onModelsChanged;

    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final List<ProviderGroup> groups = new ArrayList<>();
    private final LinkedHashMap<String, ProviderEntry> entries = new LinkedHashMap<>();
    private final ConcurrentMap<ModelCapabilityKey, ModelCapabilities> capabilityCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<ModelCapabilityKey, Long> capabilityRefreshInFlight = new ConcurrentHashMap<>();
    private final Set<ProviderModelCacheService.RefreshAttempt> catalogRefreshInFlight = ConcurrentHashMap.newKeySet();
    private final Object capabilityRefreshLock = new Object();
    private final AtomicLong providerLoadCounter = new AtomicLong();
    private final AtomicLong localHealthRefreshCounter = new AtomicLong();
    private final AtomicLong codexLocalRefreshCounter = new AtomicLong();
    private final AtomicBoolean codexModelsChangedPending = new AtomicBoolean();
    private final Object codexLocalRefreshLock = new Object();
    private boolean codexLocalRefreshInFlight;
    private boolean codexLocalRefreshPending;
    private volatile boolean disposed;

    private boolean preloaded;
    private boolean loadingProviders;
    private String currentProvider;
    private String currentModel;
    private ViewMode viewMode = ViewMode.ALL;
    private Component triggerComponent;
    private final AWTEventListener outsideClickListener;
    private boolean outsideClickListenerInstalled;
    private int highlightedIndex = -1;
    private ModelRowComponent highlightedRow;

    private enum ViewMode {
        ALL,
        FAVORITES
    }

    private record ProviderGroup(JLabel header, List<ModelRowComponent> rows) {
    }

    private record ModelCapabilityKey(String providerName, String modelId) {
    }

    private record ModelCapabilities(boolean supportsImageInput, boolean supportsReasoning, boolean supportsNativeWebSearch) {
    }

    private static final class ProviderEntry {
        final ProviderDef def;
        List<String> models;
        boolean selectable;

        ProviderEntry(ProviderDef def, List<String> models, boolean selectable) {
            this.def = def;
            this.models = models;
            this.selectable = selectable;
        }

        String name() {
            return def.name();
        }

        String baseUrl() {
            return def.baseUrl();
        }
    }

    public ModelSelectorPopup(
            Window owner,
            ProviderModelCacheService modelCacheService,
            ModelFavoritesService modelFavoritesService,
            BiConsumer<String, String> onSelect,
            BiPredicate<List<ProviderDef>, Long> onProvidersLoaded,
            Runnable onFavoritesChanged,
            Runnable onModelsChanged
    ) {
        super(owner);
        this.onSelect = onSelect;
        this.onProvidersLoaded = onProvidersLoaded;
        this.onFavoritesChanged = onFavoritesChanged;
        this.onModelsChanged = onModelsChanged;
        this.modelCacheService = modelCacheService;
        this.modelFavoritesService = modelFavoritesService;

        setUndecorated(true);
        setType(Type.POPUP);
        setModalityType(ModalityType.MODELESS);

        this.searchField = buildSearchField();
        this.allToggle = createViewToggle("All", "first", ViewMode.ALL);
        this.favoritesToggle = createViewToggle("Favorites", "last", ViewMode.FAVORITES);
        groupToggles(allToggle, favoritesToggle);
        this.listPanel = buildListPanel();
        this.scrollPane = new JScrollPane(listPanel);

        setContentPane(buildContent(searchField, allToggle, favoritesToggle, scrollPane));

        registerEscapeKey();
        registerKeyboardNavigation();
        registerWindowFocusListener();
        this.outsideClickListener = createOutsideClickListener();
    }

    public void show(Component relativeTo, String selectedProvider, String selectedModel) {
        this.currentProvider = selectedProvider;
        this.currentModel = selectedModel;
        this.triggerComponent = relativeTo;

        preparePopup();
        positionRelativeTo(relativeTo);
        openPopup();
    }

    public void showCentered(String selectedProvider, String selectedModel) {
        this.currentProvider = selectedProvider;
        this.currentModel = selectedModel;
        this.triggerComponent = null;

        preparePopup();
        centerOnScreen();
        openPopup();
    }

    private void preparePopup() {
        boolean refreshLocalModels = preloaded && entries.containsKey(CODEX_PROVIDER_NAME);
        ensureListBuilt();
        if (refreshLocalModels) {
            refreshCodexLocalModelsAsync();
        }
        refreshLocalProviderSelectableStateAsync();
        updateSelectionMarkers();
        highlightedIndex = -1;
        searchField.setText("");
        filterModels();
        setSize(computePopupSize());
    }

    private void openPopup() {
        installOutsideClickListener();
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            toFront();
            requestFocus();
            searchField.requestFocusInWindow();
        });

        modelCacheService.logMetricsSnapshot("popup-opened");
        fetchModelsAsync();
    }

    private void positionRelativeTo(Component relativeTo) {
        Point location = relativeTo.getLocationOnScreen();
        Rectangle bounds = screenBounds(relativeTo);

        int x = location.x + (relativeTo.getWidth() - getWidth()) / 2;
        x = clamp(x, bounds.x + POPUP_HORIZONTAL_MARGIN,
                bounds.x + bounds.width - getWidth() - POPUP_HORIZONTAL_MARGIN);

        int yBelow = location.y + relativeTo.getHeight();
        int yAbove = location.y - getHeight();
        int y = yBelow;
        if (y + getHeight() > bounds.y + bounds.height - POPUP_HORIZONTAL_MARGIN && yAbove >= bounds.y + POPUP_HORIZONTAL_MARGIN) {
            y = yAbove;
        }
        y = clamp(y, bounds.y + POPUP_HORIZONTAL_MARGIN,
                bounds.y + bounds.height - getHeight() - POPUP_HORIZONTAL_MARGIN);

        setLocation(x, y);
    }

    private void centerOnScreen() {
        Rectangle bounds = screenBounds(getOwner());
        int x = bounds.x + (bounds.width - getWidth()) / 2;
        int y = bounds.y + (bounds.height - getHeight()) / 2;
        setLocation(x, y);
    }

    private Dimension computePopupSize() {
        Rectangle bounds = screenBounds(getOwner());
        Font modelFont = popupModelFont();
        FontMetrics metrics = getFontMetrics(modelFont);

        int longestModelWidth = entries.values().stream()
                .flatMap(entry -> entry.models.stream())
                .mapToInt(metrics::stringWidth)
                .max()
                .orElse(0);

        int desiredWidth = longestModelWidth + POPUP_CHROME_WIDTH;
        int reducedDesiredWidth = Math.round(desiredWidth * POPUP_WIDTH_REDUCTION_FACTOR);
        int maxWidth = Math.min(POPUP_MAX_WIDTH, bounds.width - POPUP_HORIZONTAL_MARGIN * 2);
        int width = Math.max(POPUP_MIN_WIDTH, Math.min(reducedDesiredWidth, maxWidth));

        return new Dimension(width, POPUP_HEIGHT);
    }

    private Font popupModelFont() {
        Font mono = UIManager.getFont("monospaced.font");
        int size = Fonts.scale(Fonts.SIZE_BODY);
        if (mono != null) {
            return new Font(mono.getFamily(), Font.PLAIN, size);
        }

        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    private static Rectangle screenBounds(Component component) {
        GraphicsConfiguration configuration = component != null
                ? component.getGraphicsConfiguration()
                : null;
        if (configuration == null) {
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
        }

        return configuration.getBounds();
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public void preload() {
        ensureListBuilt();
    }

    public void hidePopup() {
        setVisible(false);
        uninstallOutsideClickListener();
    }

    public void invalidateModelList() {
        synchronized (capabilityRefreshLock) {
            providerLoadCounter.incrementAndGet();
            capabilityCache.clear();
            capabilityRefreshInFlight.clear();
        }
        localHealthRefreshCounter.incrementAndGet();
        codexLocalRefreshCounter.incrementAndGet();
        catalogRefreshInFlight.forEach(modelCacheService::clearRefreshInFlight);
        catalogRefreshInFlight.clear();
        preloaded = false;
        loadingProviders = false;
        entries.clear();
    }

    @Override
    public void dispose() {
        synchronized (codexLocalRefreshLock) {
            disposed = true;
            codexLocalRefreshPending = false;
        }
        codexModelsChangedPending.set(false);
        invalidateModelList();
        uninstallOutsideClickListener();
        super.dispose();
    }

    private JTextField buildSearchField() {
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", "Search models");
        field.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        Fonts.apply(field, Font.PLAIN, Fonts.SIZE_BODY);
        field.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                filterModels();
            }

            public void removeUpdate(DocumentEvent e) {
                filterModels();
            }

            public void changedUpdate(DocumentEvent e) {
                filterModels();
            }
        });
        return field;
    }

    private JPanel buildListPanel() {
        JPanel panel = new ScrollableListPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JPanel buildContent(
            JTextField searchField,
            JToggleButton allToggle,
            JToggleButton favoritesToggle,
            JScrollPane scrollPane
    ) {
        JPanel content = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1),
                        BorderFactory.createEmptyBorder(8, 0, 8, 0)
                ));
            }
        };

        JPanel controls = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                        BorderFactory.createEmptyBorder(0, 12, 6, 12)
                ));
            }
        };
        controls.add(searchField, BorderLayout.CENTER);

        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        togglePanel.setOpaque(false);
        togglePanel.add(allToggle);
        togglePanel.add(favoritesToggle);
        controls.add(togglePanel, BorderLayout.EAST);

        JLabel tipLabel = new JLabel("Tip: click the star icon on the right to favorite models");
        Fonts.apply(tipLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        tipLabel.setForeground(colorOrDefault(UIManager.getColor("Label.disabledForeground"), new Color(120, 120, 120)));
        tipLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);
        headerPanel.add(controls, BorderLayout.NORTH);
        headerPanel.add(tipLabel, BorderLayout.SOUTH);

        content.add(headerPanel, BorderLayout.NORTH);

        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scrollPane, BorderLayout.CENTER);

        return content;
    }

    private void groupToggles(JToggleButton... toggles) {
        ButtonGroup group = new ButtonGroup();
        for (JToggleButton toggle : toggles) {
            group.add(toggle);
        }
        if (toggles.length > 0) {
            toggles[0].setSelected(true);
        }
    }

    private void registerEscapeKey() {
        getRootPane().registerKeyboardAction(
                e -> hidePopup(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void registerKeyboardNavigation() {
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        moveHighlight(1);
                    }
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        moveHighlight(-1);
                    }
                    case KeyEvent.VK_PAGE_DOWN -> {
                        e.consume();
                        moveHighlight(pageStep());
                    }
                    case KeyEvent.VK_PAGE_UP -> {
                        e.consume();
                        moveHighlight(-pageStep());
                    }
                    case KeyEvent.VK_HOME -> {
                        e.consume();
                        moveHighlightTo(0);
                    }
                    case KeyEvent.VK_END -> {
                        e.consume();
                        moveHighlightTo(visibleSelectableRows().size() - 1);
                    }
                    case KeyEvent.VK_ENTER -> {
                        e.consume();
                        selectHighlighted();
                    }
                }
            }
        });
    }

    private List<ModelRowComponent> visibleSelectableRows() {
        List<ModelRowComponent> visible = new ArrayList<>();
        for (ProviderGroup group : groups) {
            for (ModelRowComponent row : group.rows()) {
                if (row.selectable() && row.panel().isVisible() && row.panel().getParent() != null) {
                    visible.add(row);
                }
            }
        }
        return visible;
    }

    private void setHighlightedIndex(int newIndex, List<ModelRowComponent> visible) {
        ModelRowComponent nextHighlightedRow = newIndex >= 0 && newIndex < visible.size()
                ? visible.get(newIndex)
                : null;
        if (newIndex == highlightedIndex && highlightedRow == nextHighlightedRow) {
            return;
        }

        if (highlightedRow != null && highlightedRow != nextHighlightedRow) {
            highlightedRow.setHighlighted(false);
        }

        highlightedIndex = newIndex;
        highlightedRow = nextHighlightedRow;

        if (highlightedRow != null) {
            highlightedRow.setHighlighted(true);
        }
    }

    private void moveHighlightTo(int index) {
        List<ModelRowComponent> visible = visibleSelectableRows();
        if (visible.isEmpty()) {
            return;
        }

        setHighlightedIndex(Math.max(0, Math.min(visible.size() - 1, index)), visible);
        scrollToHighlighted(visible);
    }

    private void moveHighlight(int direction) {
        List<ModelRowComponent> visible = visibleSelectableRows();
        if (visible.isEmpty()) {
            return;
        }

        int newIndex;
        if (highlightedIndex < 0) {
            newIndex = direction > 0 ? 0 : visible.size() - 1;
        } else {
            newIndex = Math.max(0, Math.min(visible.size() - 1, highlightedIndex + direction));
        }

        setHighlightedIndex(newIndex, visible);
        scrollToHighlighted(visible);
    }

    private int pageStep() {
        int viewportHeight = scrollPane.getViewport().getExtentSize().height;
        List<ModelRowComponent> visible = visibleSelectableRows();
        int rowHeight = visible.isEmpty() ? 40 : visible.getFirst().panel().getHeight();
        if (rowHeight <= 0) {
            rowHeight = 40;
        }
        return Math.max(1, viewportHeight / rowHeight);
    }

    private void selectHighlighted() {
        List<ModelRowComponent> visible = visibleSelectableRows();
        if (highlightedIndex >= 0 && highlightedIndex < visible.size()) {
            ModelRowComponent row = visible.get(highlightedIndex);
            currentProvider = row.providerName();
            currentModel = row.modelId();
            updateSelectionMarkers();
            onSelect.accept(row.providerName(), row.modelId());
            hidePopup();
        }
    }

    private void resetHighlight() {
        List<ModelRowComponent> visible = visibleSelectableRows();
        setHighlightedIndex(-1, visible);
    }

    private void scrollToHighlighted(List<ModelRowComponent> visible) {
        if (highlightedIndex < 0 || highlightedIndex >= visible.size()) {
            return;
        }

        JPanel rowPanel = visible.get(highlightedIndex).panel();
        Rectangle rowBounds = rowPanel.getBounds();
        Rectangle viewRect = scrollPane.getViewport().getViewRect();

        if (viewRect.contains(rowBounds)) {
            return;
        }

        // When scrolling up, include the preceding group header so it stays visible
        Rectangle scrollTarget = new Rectangle(rowBounds);
        int zOrder = listPanel.getComponentZOrder(rowPanel);
        if (zOrder > 0 && rowBounds.y < viewRect.y) {
            Component prev = listPanel.getComponent(zOrder - 1);
            if (prev instanceof JLabel) {
                scrollTarget = scrollTarget.union(prev.getBounds());
            }
        }

        listPanel.scrollRectToVisible(scrollTarget);
    }

    private void registerWindowFocusListener() {
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (shouldHideForWindowEvent(e)) {
                    hidePopup();
                }
            }
        });
    }

    private AWTEventListener createOutsideClickListener() {
        return event -> {
            if (!isVisible()) {
                return;
            }

            if (event instanceof WindowEvent windowEvent && shouldHideForWindowEvent(windowEvent)) {
                hidePopup();
                return;
            }

            if (!(event instanceof MouseEvent mouseEvent) || mouseEvent.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }

            Object source = mouseEvent.getSource();
            if (!(source instanceof Component sourceComponent)) {
                hidePopup();
                return;
            }

            if (SwingUtilities.isDescendingFrom(sourceComponent, this)) {
                return;
            }

            if (triggerComponent != null && SwingUtilities.isDescendingFrom(sourceComponent, triggerComponent)) {
                return;
            }

            hidePopup();
        };
    }

    private boolean shouldHideForWindowEvent(WindowEvent event) {
        return shouldHideForWindowEvent(
                event.getID(),
                event.getSource(),
                this,
                getOwner(),
                event.getOppositeWindow()
        );
    }

    static boolean shouldHideForWindowEvent(int eventId, Object source, Object popup, Object owner, Object opposite) {
        boolean sourceIsPopup = source == popup;
        boolean sourceIsOwner = owner != null && source == owner;
        if (!sourceIsPopup && !sourceIsOwner) {
            return false;
        }

        boolean oppositeIsPopup = opposite == popup;
        boolean oppositeIsOwner = owner != null && opposite == owner;
        return switch (eventId) {
            case WindowEvent.WINDOW_DEACTIVATED, WindowEvent.WINDOW_LOST_FOCUS -> !oppositeIsPopup && !oppositeIsOwner;
            default -> false;
        };
    }

    private void installOutsideClickListener() {
        if (outsideClickListenerInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(
                outsideClickListener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK
        );
        outsideClickListenerInstalled = true;
    }

    private void uninstallOutsideClickListener() {
        if (!outsideClickListenerInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
        outsideClickListenerInstalled = false;
    }

    private JToggleButton createViewToggle(String label, String segmentPosition, ViewMode targetMode) {
        JToggleButton button = new JToggleButton(label);
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", segmentPosition);
        button.setFocusable(false);
        button.addActionListener(e -> switchView(targetMode));
        return button;
    }

    private void switchView(ViewMode nextMode) {
        if (viewMode == nextMode) {
            return;
        }

        viewMode = nextMode;
        rebuildVisibleList();
    }

    private void ensureListBuilt() {
        if (preloaded || loadingProviders) {
            return;
        }

        showProviderLoadingState();
        loadingProviders = true;
        long loadId = providerLoadCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            long scopeVersion = -1L;
            try {
                if (!isCurrentProviderLoad(loadId)) {
                    return;
                }

                List<ProviderDef> providers = ProviderRegistry.availableProviders();
                scopeVersion = synchronizeProviderScopes(loadId, providers);
                if (scopeVersion < 0L) {
                    SwingUtilities.invokeLater(() -> applySupersededProviderLoad(loadId));
                    return;
                }

                boolean codexAvailable = providers.stream()
                        .map(ProviderDef::name)
                        .anyMatch(CODEX_PROVIDER_NAME::equals);
                List<String> previousCodexModels = codexAvailable
                        ? modelCacheService.getModels(CODEX_PROVIDER_NAME)
                        : emptyList();
                List<String> codexModels = codexAvailable
                        ? modelCacheService.refreshCodexLocalModels()
                        : emptyList();
                if (codexAvailable && !codexModels.equals(previousCodexModels)) {
                    codexModelsChangedPending.set(true);
                }
                if (!isCurrentProviderLoad(loadId)) {
                    return;
                }

                long loadedScopeVersion = scopeVersion;
                SwingUtilities.invokeLater(() -> applyLoadedProviders(loadId, loadedScopeVersion, providers));
            } catch (Exception e) {
                log.warn("Failed to load provider models: {}", ExceptionUtils.getMessage(e));
                long failedScopeVersion = scopeVersion;
                SwingUtilities.invokeLater(() -> applyProviderLoadFailure(loadId, failedScopeVersion));
            }
        });
    }

    private long synchronizeProviderScopes(long loadId, List<ProviderDef> providers) {
        long scopeVersion = modelCacheService.nextScopeVersion();
        for (ProviderDef provider : providers) {
            if (!isCurrentProviderLoad(loadId)) {
                modelCacheService.cancelScopeVersion(scopeVersion);
                return -1L;
            }
            modelCacheService.synchronizeScope(provider.name(), provider.baseUrl(), scopeVersion);
        }
        if (!isCurrentProviderLoad(loadId) || !modelCacheService.isScopeVersionCurrent(scopeVersion)) {
            modelCacheService.cancelScopeVersion(scopeVersion);
            return -1L;
        }
        return scopeVersion;
    }

    private boolean isCurrentProviderLoad(long loadId) {
        return providerLoadCounter.get() == loadId;
    }

    private void applyLoadedProviders(
            long loadId,
            long scopeVersion,
            List<ProviderDef> providers
    ) {
        if (!isCurrentProviderLoad(loadId)) {
            return;
        }
        if (!modelCacheService.isScopeVersionCurrent(scopeVersion)) {
            applySupersededProviderLoad(loadId);
            return;
        }

        loadingProviders = false;
        if (!onProvidersLoaded.test(List.copyOf(providers), scopeVersion)) {
            applySupersededProviderLoad(loadId);
            return;
        }
        buildList(providers);
        refreshLocalProviderSelectableStateAsync();
        if (codexModelsChangedPending.getAndSet(false) && onModelsChanged != null) {
            onModelsChanged.run();
        }
        if (isVisible()) {
            setSize(computePopupSize());
            if (triggerComponent != null && triggerComponent.isShowing()) {
                positionRelativeTo(triggerComponent);
            } else {
                centerOnScreen();
            }
            fetchModelsAsync();
        }
    }

    private void applySupersededProviderLoad(long loadId) {
        if (!isCurrentProviderLoad(loadId)) {
            return;
        }

        loadingProviders = false;
        if (isVisible()) {
            ensureListBuilt();
        }
    }

    private void applyProviderLoadFailure(long loadId, long scopeVersion) {
        if (!isCurrentProviderLoad(loadId)) {
            return;
        }
        if (scopeVersion >= 0L && !modelCacheService.isScopeVersionCurrent(scopeVersion)) {
            applySupersededProviderLoad(loadId);
            return;
        }

        loadingProviders = false;
        showProviderState("Failed to load models");
    }

    private void showProviderLoadingState() {
        showProviderState("Loading models...");
    }

    private void showProviderState(String message) {
        listPanel.removeAll();
        groups.clear();
        JLabel stateLabel = new JLabel(message);
        stateLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        stateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        stateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(stateLabel);
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void buildList(List<ProviderDef> providers) {
        entries.clear();

        providers.forEach(provider -> {
            List<String> models = initialModels(
                    provider.name(),
                    modelCacheService.getModels(provider.name()),
                    modelCacheService.modelsWithLocalOverlay(provider.name(), provider.seedModels()),
                    modelCacheService.isInvalidated(provider.name())
            );
            entries.put(provider.name(), new ProviderEntry(
                    provider,
                    models,
                    isProviderSelectable(provider)));
        });

        preloaded = true;
        rebuildVisibleList();
    }

    private void rebuildVisibleList() {
        listPanel.removeAll();
        groups.clear();

        entries.values().forEach(entry -> {
            List<String> models = entry.models.stream()
                    .filter(modelId -> viewMode != ViewMode.FAVORITES
                            || modelFavoritesService.isFavorite(entry.name(), modelId))
                    .toList();

            if (!models.isEmpty()) {
                addProviderGroup(entry, models);
            }
        });

        if (groups.isEmpty()) {
            String message = viewMode == ViewMode.FAVORITES
                    ? "No favorite models yet"
                    : "No models available";
            JLabel emptyState = new JLabel(message);
            emptyState.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            emptyState.setForeground(UIManager.getColor("Label.disabledForeground"));
            emptyState.setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(emptyState);
        }

        updateSelectionMarkers();
        filterModels();
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void addProviderGroup(ProviderEntry entry, List<String> models) {
        String groupName = entry.name();
        boolean selectable = entry.selectable;
        String label = selectable ? groupName : "%s (offline)".formatted(groupName);
        JLabel header = new JLabel(label);
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_BODY);
        int providerIconSize = Math.max(10, header.getFontMetrics(header.getFont()).getHeight() - 1);
        header.setIcon(providerIcon(groupName, providerIconSize));
        header.setIconTextGap(6);
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(header);

        List<ModelRowComponent> rows = new ArrayList<>();
        models.forEach(modelId -> {
            ModelRowComponent row = createModelRow(entry, modelId, selectable);
            rows.add(row);
            row.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(row.panel());
        });

        groups.add(new ProviderGroup(header, rows));
    }

    private void fetchModelsAsync() {
        long providerGeneration = providerLoadCounter.get();
        entries.values().forEach(entry -> {
            if (Strings.CS.equals(entry.name(), "Perplexity")) {
                return;
            }

            Duration ttl = refreshTtl(entry.name());
            ProviderModelCacheService.RefreshAttempt refreshAttempt = modelCacheService
                    .tryBeginRefreshIfNeeded(entry.name(), entry.baseUrl(), ttl)
                    .orElse(null);
            if (refreshAttempt == null) {
                return;
            }

            catalogRefreshInFlight.add(refreshAttempt);
            Thread.startVirtualThread(() -> {
                try {
                    if (!isCatalogRefreshCurrent(providerGeneration)) {
                        return;
                    }
                    List<String> fetchedModels = entry.def.fetcher().fetchModels();
                    if (!isCatalogRefreshCurrent(providerGeneration)) {
                        return;
                    }
                    boolean updated = modelCacheService.update(refreshAttempt, fetchedModels);
                    if (updated && isCatalogRefreshCurrent(providerGeneration)) {
                        SwingUtilities.invokeLater(() -> {
                            if (isCatalogRefreshCurrent(providerGeneration)) {
                                refreshProviderFromCache(entry.name());
                            }
                        });
                    }
                } catch (Exception e) {
                    if (isCatalogRefreshCurrent(providerGeneration)) {
                        modelCacheService.recordRefreshFailure(refreshAttempt);
                        log.warn("Failed to refresh models for provider {}. Keeping cached/seed models: {}",
                                entry.name(), ExceptionUtils.getMessage(e));
                    }
                } finally {
                    catalogRefreshInFlight.remove(refreshAttempt);
                    modelCacheService.clearRefreshInFlight(refreshAttempt);
                    modelCacheService.logMetricsSnapshot("refresh-complete:%s".formatted(entry.name()));
                }
            });
        });
    }

    private boolean isCatalogRefreshCurrent(long providerGeneration) {
        return !disposed && isCurrentProviderLoad(providerGeneration);
    }

    static Duration refreshTtl(String providerName) {
        return LOCAL_HEALTH_GATED_PROVIDERS.contains(providerName)
                ? LOCAL_PROVIDER_REFRESH_TTL
                : MODEL_REFRESH_TTL;
    }

    private void refreshProviderFromCache(String providerName) {
        ProviderEntry entry = entries.get(providerName);
        if (entry == null) {
            return;
        }

        List<String> models = modelCacheService.isInvalidated(providerName)
                ? emptyList()
                : modelCacheService.getModels(providerName);
        if (models.isEmpty()) {
            models = modelCacheService.modelsWithLocalOverlay(providerName, entry.def.seedModels());
        }
        refreshProvider(entry, models);
    }

    private void refreshProvider(ProviderEntry entry, List<String> models) {
        List<String> refreshedModels = List.copyOf(models);
        boolean modelsChanged = !refreshedModels.equals(entry.models);
        boolean selectable = isProviderSelectable(entry.name(), entry.baseUrl());
        boolean selectableChanged = selectable != entry.selectable;
        if (!modelsChanged && !selectableChanged) {
            return;
        }

        entry.models = refreshedModels;
        entry.selectable = selectable;
        if (modelsChanged && onModelsChanged != null) {
            onModelsChanged.run();
        }

        if (preloaded) {
            rebuildVisibleList();
        }
    }

    private void refreshCodexLocalModelsAsync() {
        long refreshId = codexLocalRefreshCounter.incrementAndGet();
        synchronized (codexLocalRefreshLock) {
            if (disposed) {
                return;
            }
            if (codexLocalRefreshInFlight) {
                codexLocalRefreshPending = true;
                return;
            }
            codexLocalRefreshInFlight = true;
            startCodexLocalRefresh(refreshId);
        }
    }

    private void startCodexLocalRefresh(long refreshId) {
        Thread.startVirtualThread(() -> {
            try {
                modelCacheService.refreshCodexLocalModels();
                SwingUtilities.invokeLater(() -> {
                    if (codexLocalRefreshCounter.get() == refreshId) {
                        refreshProviderFromCache(CODEX_PROVIDER_NAME);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to refresh local OpenAI Codex models: {}", ExceptionUtils.getMessage(e));
            } finally {
                completeCodexLocalRefresh();
            }
        });
    }

    private void completeCodexLocalRefresh() {
        long pendingRefreshId;
        synchronized (codexLocalRefreshLock) {
            if (disposed || !codexLocalRefreshPending) {
                codexLocalRefreshInFlight = false;
                return;
            }
            codexLocalRefreshPending = false;
            pendingRefreshId = codexLocalRefreshCounter.get();
            startCodexLocalRefresh(pendingRefreshId);
        }
    }

    private ModelRowComponent createModelRow(
            ProviderEntry entry,
            String modelId,
            boolean selectable
    ) {
        ModelCapabilities initialCapabilities = resolveCachedCapabilities(entry, modelId);

        ModelRowComponent row = new ModelRowComponent(
                entry.name(),
                modelId,
                selectable,
                modelFavoritesService.isFavorite(entry.name(), modelId),
                initialCapabilities.supportsImageInput(),
                initialCapabilities.supportsReasoning(),
                initialCapabilities.supportsNativeWebSearch(),
                new ModelRowComponent.Listener() {
                    @Override
                    public void onSelect(String p, String m) {
                        currentProvider = p;
                        currentModel = m;
                        updateSelectionMarkers();
                        onSelect.accept(p, m);
                        hidePopup();
                    }

                    @Override
                    public void onToggleFavorite(String p, String m) {
                        toggleFavorite(p, m);
                    }

                    @Override
                    public void onMouseEnter(String p, String m) {
                        List<ModelRowComponent> visible = visibleSelectableRows();
                        for (int i = 0; i < visible.size(); i++) {
                            ModelRowComponent row = visible.get(i);
                            if (row.providerName().equals(p) && row.modelId().equals(m)) {
                                setHighlightedIndex(i, visible);
                                return;
                            }
                        }
                    }
                });

        if (selectable) {
            refreshCapabilitiesAsync(entry, modelId);
        }
        return row;
    }

    private ModelCapabilities resolveCachedCapabilities(ProviderEntry entry, String modelId) {
        ModelCapabilityKey key = new ModelCapabilityKey(entry.name(), modelId);
        ModelCapabilities cached = capabilityCache.get(key);
        if (cached != null) {
            return cached;
        }

        ModelCapabilities fallback = fallbackCapabilities(entry, modelId);
        capabilityCache.put(key, fallback);
        return fallback;
    }

    private ModelCapabilities fallbackCapabilities(ProviderEntry entry, String modelId) {
        boolean supportsImageInput = ProviderCapabilityResolver.supportsImageInput(
                entry.def.capabilities(),
                entry.name(),
                modelId
        );
        boolean supportsReasoning = ProviderCapabilityResolver.supportsReasoning(
                entry.def.capabilities(),
                entry.name(),
                modelId
        );

        boolean supportsNativeWebSearch = ProviderCapabilityResolver.supportsRuntimeNativeWebSearch(
                entry.def.capabilities(),
                entry.name(),
                modelId
        );

        return new ModelCapabilities(supportsImageInput, supportsReasoning, supportsNativeWebSearch);
    }

    private void refreshCapabilitiesAsync(ProviderEntry entry, String modelId) {
        if (StringUtils.isBlank(entry.baseUrl())) {
            return;
        }

        ModelCapabilityKey key = new ModelCapabilityKey(entry.name(), modelId);
        long providerGeneration;
        synchronized (capabilityRefreshLock) {
            providerGeneration = providerLoadCounter.get();
            if (capabilityRefreshInFlight.putIfAbsent(key, providerGeneration) != null) {
                return;
            }
        }

        Thread.startVirtualThread(() -> {
            try {
                if (!isCurrentProviderLoad(providerGeneration)) {
                    return;
                }

                String apiKey = CredentialResolver.resolveApiKey(entry.def.envVar(), null);
                boolean supportsImageInput = ProviderCapabilityResolver.supportsImageInput(
                        entry.def.capabilities(),
                        entry.name(),
                        modelId,
                        entry.baseUrl(),
                        apiKey
                );
                if (!isCurrentProviderLoad(providerGeneration)) {
                    return;
                }

                boolean supportsReasoning = ProviderCapabilityResolver.supportsReasoning(
                        entry.def.capabilities(),
                        entry.name(),
                        modelId,
                        entry.baseUrl(),
                        apiKey
                );
                if (!isCurrentProviderLoad(providerGeneration)) {
                    return;
                }

                boolean supportsNativeWebSearch = ProviderCapabilityResolver.supportsRuntimeNativeWebSearch(
                        entry.def.capabilities(),
                        entry.name(),
                        modelId,
                        entry.baseUrl(),
                        apiKey
                );

                ModelCapabilities resolved = new ModelCapabilities(
                        supportsImageInput,
                        supportsReasoning,
                        supportsNativeWebSearch
                );
                synchronized (capabilityRefreshLock) {
                    if (!isCurrentProviderLoad(providerGeneration)) {
                        return;
                    }
                    capabilityCache.put(key, resolved);
                }
                SwingUtilities.invokeLater(() -> {
                    if (isCurrentProviderLoad(providerGeneration)) {
                        applyCapabilitiesToVisibleRows(key, resolved);
                    }
                });
            } finally {
                capabilityRefreshInFlight.remove(key, providerGeneration);
            }
        });
    }

    private void applyCapabilitiesToVisibleRows(ModelCapabilityKey key, ModelCapabilities capabilities) {
        groups.forEach(group -> group.rows().forEach(row -> {
            if (row.providerName().equals(key.providerName()) && row.modelId().equals(key.modelId())) {
                row.updateCapabilities(
                        capabilities.supportsImageInput(),
                        capabilities.supportsReasoning(),
                        capabilities.supportsNativeWebSearch()
                );
            }
        }));
    }


    private void toggleFavorite(String providerName, String modelId) {
        try {
            modelFavoritesService.toggleFavorite(providerName, modelId);
            rebuildVisibleList();
            if (onFavoritesChanged != null) {
                onFavoritesChanged.run();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to update favorites: %s".formatted(e.getMessage()),
                    "Favorites",
                    JOptionPane.WARNING_MESSAGE
            );
        }
    }

    private void updateSelectionMarkers() {
        groups.forEach(group -> group.rows().forEach(row -> {
            boolean selected = row.providerName().equals(currentProvider)
                    && row.modelId().equals(currentModel);
            row.setSelected(selected);
            row.updateFavoriteState(modelFavoritesService.isFavorite(row.providerName(), row.modelId()));
        }));
    }

    private void filterModels() {
        resetHighlight();
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);

        for (ProviderGroup group : groups) {
            boolean anyVisible = false;
            for (ModelRowComponent row : group.rows()) {
                boolean matches = query.isEmpty()
                        || row.modelId().toLowerCase(Locale.ROOT).contains(query)
                        || row.providerName().toLowerCase(Locale.ROOT).contains(query);
                row.panel().setVisible(matches);
                if (matches) {
                    anyVisible = true;
                }
            }
            group.header().setVisible(anyVisible);
        }

        listPanel.revalidate();
        listPanel.repaint();
    }

    private static Color colorOrDefault(Color candidate, Color fallback) {
        return candidate != null ? candidate : fallback;
    }

    static List<String> initialModels(String providerName, List<String> cachedModels, List<String> seedModels, boolean invalidated) {
        if (Strings.CS.equals(providerName, "Perplexity")) {
            return PerplexityModelIds.SONAR_MODELS;
        }

        if (invalidated) {
            return sanitizeModels(providerName, seedModels);
        }

        List<String> cached = sanitizeModels(providerName, cachedModels);
        return cached.isEmpty()
                ? sanitizeModels(providerName, seedModels)
                : cached;
    }

    private static List<String> sanitizeModels(String providerName, List<String> modelIds) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
    }

    private static Icon providerIcon(String providerName, int size) {
        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(iconPath)) {
            return null;
        }

        String cacheKey = "%s#%d".formatted(iconPath, size);
        return PROVIDER_ICON_CACHE.computeIfAbsent(cacheKey, key -> {
            URL url = ModelSelectorPopup.class.getResource(iconPath);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                Color foreground = ObjectUtils.firstNonNull(
                        component != null ? component.getForeground() : null,
                        UIManager.getColor("Label.foreground"),
                        new Color(90, 90, 90)
                );
                return new Color(
                        foreground.getRed(),
                        foreground.getGreen(),
                        foreground.getBlue(),
                        color.getAlpha());
            }));
            return icon.hasFound() ? icon : null;
        });
    }

    private boolean isProviderSelectable(ProviderDef provider) {
        return isProviderSelectable(provider.name(), provider.baseUrl());
    }

    static boolean isProviderSelectable(String providerName, String baseUrl) {
        if (!LOCAL_HEALTH_GATED_PROVIDERS.contains(providerName)) {
            return true;
        }

        return StringUtils.isNotBlank(baseUrl) && LocalServiceHealth.lastKnownReachable(baseUrl);
    }

    private void refreshLocalProviderSelectableStateAsync() {
        Map<String, String> baseUrlByProvider = entries.values().stream()
                .filter(entry -> LOCAL_HEALTH_GATED_PROVIDERS.contains(entry.name()))
                .filter(entry -> StringUtils.isNotBlank(entry.baseUrl()))
                .collect(toMap(
                        ProviderEntry::name,
                        ProviderEntry::baseUrl,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        if (baseUrlByProvider.isEmpty()) {
            return;
        }

        long providerGeneration = providerLoadCounter.get();
        long healthRefreshId = localHealthRefreshCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            if (!isCurrentProviderLoad(providerGeneration)
                    || localHealthRefreshCounter.get() != healthRefreshId) {
                return;
            }

            Map<String, Boolean> selectableByProvider = baseUrlByProvider.entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            entry -> LocalServiceHealth.isReachable(entry.getValue())
                    ));
            SwingUtilities.invokeLater(() -> {
                if (!isCurrentProviderLoad(providerGeneration)
                        || localHealthRefreshCounter.get() != healthRefreshId) {
                    return;
                }

                boolean changed = false;
                for (Map.Entry<String, Boolean> selectableEntry : selectableByProvider.entrySet()) {
                    ProviderEntry entry = entries.get(selectableEntry.getKey());
                    if (entry != null && entry.selectable != selectableEntry.getValue()) {
                        entry.selectable = selectableEntry.getValue();
                        changed = true;
                    }
                }
                if (changed && preloaded) {
                    rebuildVisibleList();
                }
            });
        });
    }

    private static class ScrollableListPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
