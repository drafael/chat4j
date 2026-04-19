package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderDef;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatSearchIcon;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ModelSelectorPopup extends JDialog {

    private static final Duration MODEL_REFRESH_TTL = Duration.ofHours(12);
    private static final Duration LOCAL_PROVIDER_REFRESH_TTL = Duration.ofSeconds(20);
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
    private static final Map<String, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();

    private final JTextField searchField;
    private final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final JToggleButton allToggle;
    private final JToggleButton favoritesToggle;
    private final BiConsumer<String, String> onSelect;
    private final Runnable onFavoritesChanged;
    private final Runnable onModelsChanged;

    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final List<ProviderGroup> groups = new ArrayList<>();
    private final LinkedHashMap<String, ProviderEntry> entries = new LinkedHashMap<>();

    private boolean preloaded;
    private String currentProvider;
    private String currentModel;
    private ViewMode viewMode = ViewMode.ALL;
    private Component triggerComponent;
    private final AWTEventListener outsideClickListener;
    private boolean outsideClickListenerInstalled;
    private int highlightedIndex = -1;
    private boolean scrolling;

    private enum ViewMode {
        ALL,
        FAVORITES
    }

    private record ProviderGroup(JLabel header, String groupName, List<ModelRowComponent> rows) {
    }

    private record DisplayModel(String providerName, String modelId, String displayLabel) {
    }

    private static final class ProviderEntry {
        final ProviderDef def;
        List<String> models;
        String baseUrl;
        boolean selectable;

        ProviderEntry(ProviderDef def, List<String> models, String baseUrl, boolean selectable) {
            this.def = def;
            this.models = models;
            this.baseUrl = baseUrl;
            this.selectable = selectable;
        }

        String name() {
            return def.name();
        }
    }

    public ModelSelectorPopup(
            Window owner,
            ProviderModelCacheService modelCacheService,
            ModelFavoritesService modelFavoritesService,
            BiConsumer<String, String> onSelect,
            Runnable onFavoritesChanged,
            Runnable onModelsChanged
    ) {
        super(owner);
        this.onSelect = onSelect;
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
        ensureListBuilt();
        refreshProviderSelectableState();
        updateSelectionMarkers();
        highlightedIndex = -1;
        searchField.setText("");
        filterModels();
        setSize(computePopupSize());
    }

    private void openPopup() {
        installOutsideClickListener();
        setVisible(true);
        searchField.requestFocusInWindow();

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
        preloaded = false;
        entries.clear();
    }

    @Override
    public void dispose() {
        uninstallOutsideClickListener();
        super.dispose();
    }

    private JTextField buildSearchField() {
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", "Search models");
        field.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
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
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> ensureHighlightVisible());
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
        searchField.addKeyListener(new java.awt.event.KeyAdapter() {
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
                if (row.panel().isVisible() && row.panel().getParent() != null) {
                    visible.add(row);
                }
            }
        }
        return visible;
    }

    private void setHighlightedIndex(int newIndex, List<ModelRowComponent> visible) {
        if (newIndex == highlightedIndex) {
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < visible.size()) {
            visible.get(highlightedIndex).setHighlighted(false);
        }

        highlightedIndex = newIndex;

        if (highlightedIndex >= 0 && highlightedIndex < visible.size()) {
            visible.get(highlightedIndex).setHighlighted(true);
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

    private void ensureHighlightVisible() {
        if (scrolling || highlightedIndex < 0) {
            return;
        }

        List<ModelRowComponent> visible = visibleSelectableRows();
        if (highlightedIndex >= visible.size()) {
            return;
        }

        JPanel rowPanel = visible.get(highlightedIndex).panel();
        Rectangle viewRect = scrollPane.getViewport().getViewRect();
        Rectangle rowBounds = SwingUtilities.convertRectangle(rowPanel.getParent(), rowPanel.getBounds(), listPanel);

        if (!viewRect.intersects(rowBounds)) {
            scrolling = true;
            try {
                listPanel.scrollRectToVisible(rowBounds);
            } finally {
                scrolling = false;
            }
        }
    }

    private void registerWindowFocusListener() {
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hidePopup();
            }
        });
    }

    private AWTEventListener createOutsideClickListener() {
        return event -> {
            if (!(event instanceof MouseEvent mouseEvent) || mouseEvent.getID() != MouseEvent.MOUSE_PRESSED) {
                return;
            }

            if (!isVisible()) {
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

    private void installOutsideClickListener() {
        if (outsideClickListenerInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
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
        if (preloaded) {
            return;
        }

        buildList(ProviderRegistry.availableProviders());
    }

    private void buildList(List<ProviderDef> providers) {
        entries.clear();

        providers.forEach(provider -> {
            List<String> cached = sanitizeModels(provider.name(), modelCacheService.getModels(provider.name()));
            List<String> models = cached.isEmpty()
                    ? sanitizeModels(provider.name(), provider.seedModels())
                    : cached;
            entries.put(provider.name(), new ProviderEntry(
                    provider,
                    models,
                    provider.baseUrl(),
                    isProviderSelectable(provider)));
        });

        preloaded = true;
        rebuildVisibleList();
    }

    private void rebuildVisibleList() {
        listPanel.removeAll();
        groups.clear();

        entries.values().forEach(entry -> {
            List<DisplayModel> models = entry.models.stream()
                    .filter(modelId -> viewMode != ViewMode.FAVORITES
                            || modelFavoritesService.isFavorite(entry.name(), modelId))
                    .map(modelId -> new DisplayModel(entry.name(), modelId, modelId))
                    .toList();

            if (!models.isEmpty()) {
                addProviderGroup(entry.name(), models, entry.selectable);
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

    private void addProviderGroup(String groupName, List<DisplayModel> models, boolean selectable) {
        String label = selectable ? groupName : groupName + " (offline)";
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
        models.forEach(model -> {
            ModelRowComponent row = createModelRow(model.providerName(), model.modelId(), model.displayLabel(), selectable);
            rows.add(row);
            row.panel().setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(row.panel());
        });

        groups.add(new ProviderGroup(header, groupName, rows));
    }

    private void fetchModelsAsync() {
        entries.values().forEach(entry -> {
            Duration ttl = LOCAL_HEALTH_GATED_PROVIDERS.contains(entry.name())
                    ? LOCAL_PROVIDER_REFRESH_TTL
                    : MODEL_REFRESH_TTL;
            if (!modelCacheService.shouldRefresh(entry.name(), ttl)
                    || !modelCacheService.tryMarkRefreshInFlight(entry.name())
            ) {
                return;
            }

            Thread.startVirtualThread(() -> {
                try {
                    modelCacheService.update(entry.name(), entry.def.fetcher().fetchModels());
                    List<String> fetched = sanitizeModels(entry.name(), modelCacheService.getModels(entry.name()));
                    SwingUtilities.invokeLater(() -> refreshProvider(entry.name(), fetched));
                } catch (Exception e) {
                    // Fetch failed — keep showing cached/seed models
                } finally {
                    modelCacheService.clearRefreshInFlight(entry.name());
                    modelCacheService.logMetricsSnapshot("refresh-complete:" + entry.name());
                }
            });
        });
    }

    private void refreshProvider(String providerName, List<String> models) {
        ProviderEntry entry = entries.get(providerName);
        if (entry == null) {
            return;
        }

        entry.models = List.copyOf(models);
        entry.selectable = isProviderCurrentlySelectable(providerName);

        if (onModelsChanged != null) {
            onModelsChanged.run();
        }

        if (!preloaded) {
            return;
        }

        rebuildVisibleList();
    }

    private ModelRowComponent createModelRow(String providerName, String modelId, String displayLabel, boolean selectable) {
        return new ModelRowComponent(
                providerName,
                modelId,
                displayLabel,
                selectable,
                modelFavoritesService.isFavorite(providerName, modelId),
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
                    "Failed to update favorites: " + e.getMessage(),
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
        String query = searchField.getText().trim().toLowerCase();

        for (ProviderGroup group : groups) {
            boolean anyVisible = false;
            for (ModelRowComponent row : group.rows()) {
                boolean matches = query.isEmpty()
                        || row.modelId().toLowerCase().contains(query)
                        || row.providerName().toLowerCase().contains(query);
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

    private static List<String> sanitizeModels(String providerName, List<String> modelIds) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
    }

    private static Icon providerIcon(String providerName, int size) {
        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (iconPath == null || iconPath.isBlank()) {
            return null;
        }

        String cacheKey = iconPath + "#" + size;
        return PROVIDER_ICON_CACHE.computeIfAbsent(cacheKey, key -> {
            URL url = ModelSelectorPopup.class.getResource(iconPath);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                Color foreground = component != null ? component.getForeground() : null;
                if (foreground == null) {
                    foreground = UIManager.getColor("Label.foreground");
                }
                if (foreground == null) {
                    foreground = new Color(90, 90, 90);
                }
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
        if (!LOCAL_HEALTH_GATED_PROVIDERS.contains(provider.name())) {
            return true;
        }

        return LocalServiceHealth.isReachable(provider.baseUrl());
    }

    private boolean isProviderCurrentlySelectable(String providerName) {
        if (!LOCAL_HEALTH_GATED_PROVIDERS.contains(providerName)) {
            return true;
        }

        ProviderEntry entry = entries.get(providerName);
        if (entry == null || entry.baseUrl == null || entry.baseUrl.isBlank()) {
            return true;
        }

        return LocalServiceHealth.isReachable(entry.baseUrl);
    }

    private void refreshProviderSelectableState() {
        boolean changed = false;

        for (ProviderEntry entry : entries.values()) {
            boolean selectable = isProviderCurrentlySelectable(entry.name());
            if (entry.selectable != selectable) {
                entry.selectable = selectable;
                changed = true;
            }
        }

        if (changed && preloaded) {
            rebuildVisibleList();
        }
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
