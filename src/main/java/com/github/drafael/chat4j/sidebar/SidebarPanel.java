package com.github.drafael.chat4j.sidebar;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatFileViewDirectoryIcon;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.formdev.flatlaf.icons.FlatTreeCollapsedIcon;
import com.formdev.flatlaf.icons.FlatTreeExpandedIcon;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationRepo.ConversationRecord;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;

public class SidebarPanel extends JPanel {

    private static final String CONVERSATIONS_TITLE = "Conversations";
    private static final String FAVORITES_TITLE = "Favorites";
    private static final String LOAD_CONVERSATIONS_ERROR = "Failed to load conversations";
    private static final String RENAME_CONVERSATION_ERROR = "Failed to rename conversation";
    private static final String FAVORITES_ERROR = "Failed to update favorites";
    private static final String DELETE_CONVERSATION_ERROR = "Failed to delete conversation";
    private static final String DELETE_GROUP_ERROR = "Failed to delete group";
    private static final String DELETE_ALL_ERROR = "Failed to delete all chats";

    private static final int SIDEBAR_WIDTH = 250;
    private static final int HOVER_ICON_SIZE = 14;
    private static final int HOVER_ICON_GAP = 8;
    private static final int HOVER_ICON_PADDING = 10;
    private static final int HOVER_BACKGROUND_PADDING = 8;
    private static final int HOVER_BACKGROUND_FADE_WIDTH = 16;
    private static final int PROVIDER_ICON_SIZE = 16;
    private static final int LOADING_ICON_FRAME_DELAY_MILLIS = 100;

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
    private static final Map<String, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();
    private static Icon cachedTrashIcon;
    private static Icon cachedStarIcon;
    private static Icon cachedStarFilledIcon;

    private final DefaultListModel<Object> listModel = new DefaultListModel<>();
    private final JList<Object> conversationList;
    private final JScrollPane conversationScrollPane;
    private final JTextField filterField;
    private final ConversationRepo conversationRepo;
    private final Set<String> collapsedGroups = new HashSet<>();
    private final Set<UUID> streamingConversationIds = new HashSet<>();

    private int loadingIconFrame;

    private final Timer loadingIconTimer = new Timer(LOADING_ICON_FRAME_DELAY_MILLIS, event -> advanceLoadingIconFrame());
    private final Icon loadingIcon = new LoadingIcon(() -> loadingIconFrame);

    private Consumer<UUID> onConversationSelected;
    private Runnable onNewChat;
    private Runnable onSearch;
    private Runnable onProjects;
    private Runnable onSettings;
    private boolean suppressSelection;
    private int hoveredIndex = -1;
    private final AtomicLong refreshRequestCounter = new AtomicLong();
    private UUID pendingSelectionConversationId;
    private Map<String, List<ConversationRecord>> lastGroupedConversations = Map.of();

    public SidebarPanel(ConversationRepo conversationRepo) {
        this.conversationRepo = Objects.requireNonNull(conversationRepo);

        configurePanel();
        conversationList = createConversationList();
        conversationScrollPane = createConversationScrollPane();
        filterField = createFilterField();
        applyScrollPaneStyles();

        add(createNavigationHeader(), BorderLayout.NORTH);
        add(conversationScrollPane, BorderLayout.CENTER);
        add(createNavigationFooter(), BorderLayout.SOUTH);
        loadingIconTimer.setRepeats(true);
        refresh();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyThemeBackgroundTint();
        applyScrollPaneStyles();
    }

    @Override
    public void removeNotify() {
        loadingIconTimer.stop();
        super.removeNotify();
    }

    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }

        long refreshRequestId = refreshRequestCounter.incrementAndGet();

        Thread.startVirtualThread(() -> {
            try {
                Map<String, List<ConversationRecord>> grouped = conversationRepo.findAllGroupedByDate();
                SwingUtilities.invokeLater(() -> applyRefreshResult(refreshRequestId, grouped));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> applyRefreshFailure(refreshRequestId, e));
            }
        });
    }

    public void selectConversation(UUID id) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> selectConversation(id));
            return;
        }

        pendingSelectionConversationId = id;
        expandGroupContaining(id);
        withSelectionSuppressed(this::applyPendingSelection);
    }

    public void clearSelection() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::clearSelection);
            return;
        }

        pendingSelectionConversationId = null;
        withSelectionSuppressed(conversationList::clearSelection);
    }

    public void setConversationStreaming(UUID conversationId, boolean streaming) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setConversationStreaming(conversationId, streaming));
            return;
        }
        if (conversationId == null) {
            return;
        }

        boolean changed = streaming
                ? streamingConversationIds.add(conversationId)
                : streamingConversationIds.remove(conversationId);
        if (!changed) {
            return;
        }

        updateLoadingAnimationState();
        repaintConversation(conversationId);
    }

    public void setOnConversationSelected(Consumer<UUID> handler) {
        onConversationSelected = handler;
    }

    public void setOnNewChat(Runnable handler) {
        onNewChat = handler;
    }

    public void setOnSearch(Runnable handler) {
        onSearch = handler;
    }

    public void setOnProjects(Runnable handler) {
        onProjects = handler;
    }

    public void setOnSettings(Runnable handler) {
        onSettings = handler;
    }

    private void configurePanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        applyThemeBackgroundTint();
        setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
    }

    private JList<Object> createConversationList() {
        var list = new JList<>(listModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                return resolveTooltip(event);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                paintHoverIcons(graphics);
            }
        };

        ToolTipManager.sharedInstance().registerComponent(list);
        list.setOpaque(false);
        list.putClientProperty("FlatLaf.style", "selectionArc:12;selectionInsets:2,8,2,8");
        list.setCellRenderer(new ConversationCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(this::handleSelectionChanged);
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                updateHoveredIndex(resolveListIndex(event.getPoint()));
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                handleMouseClick(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                updateHoveredIndex(-1);
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showContextMenu(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showContextMenu(event);
                }
            }
        });

        return list;
    }

    private JPanel createNavigationHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));

        JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
        actions.setOpaque(false);
        actions.add(createHeaderActionButton("New chat", navigationIcon("/icons/titlebar/square-pen.svg"), () -> {
            if (onNewChat != null) {
                onNewChat.run();
            }
        }));
        actions.add(createHeaderActionButton("Search", navigationIcon("/icons/titlebar/search.svg"), () -> {
            if (onSearch != null) {
                onSearch.run();
            }
        }));
        actions.add(createHeaderActionButton("Projects", new FlatFileViewDirectoryIcon(), () -> {
            if (onProjects != null) {
                onProjects.run();
            }
        }));
        header.add(actions, BorderLayout.NORTH);
        header.add(filterField, BorderLayout.CENTER);

        JLabel sectionLabel = new JLabel(CONVERSATIONS_TITLE);
        Fonts.apply(sectionLabel, Font.BOLD, Fonts.SIZE_SMALL);
        sectionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.add(sectionLabel, BorderLayout.SOUTH);
        return header;
    }

    private JTextField createFilterField() {
        JTextField field = new JTextField();
        field.putClientProperty("JTextField.placeholderText", "Filter conversations");
        field.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        field.putClientProperty("FlatLaf.style", "arc:12;showClearButton:true");
        field.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        Fonts.apply(field, Font.PLAIN, Fonts.SIZE_BODY);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyConversationFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyConversationFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyConversationFilter();
            }
        });
        return field;
    }

    private JPanel createNavigationFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 10, 12, 10));
        footer.add(createHeaderActionButton("Settings", null, () -> {
            if (onSettings != null) {
                onSettings.run();
            }
        }), BorderLayout.CENTER);
        return footer;
    }

    private JButton createHeaderActionButton(String text, Icon icon, Runnable action) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.putClientProperty("FlatLaf.style", "focusWidth:0;innerFocusWidth:0;arc:12");
        button.setIcon(icon);
        button.setIconTextGap(8);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusable(false);
        button.setMargin(new Insets(6, 10, 6, 10));
        Fonts.apply(button, Font.PLAIN, Fonts.SIZE_BODY);
        button.addActionListener(event -> action.run());
        return button;
    }

    private Icon navigationIcon(String path) {
        URL url = SidebarPanel.class.getResource(path);
        if (url == null) {
            return null;
        }
        var icon = new FlatSVGIcon(url).derive(16, 16);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
            Color foreground = component != null ? component.getForeground() : UIManager.getColor("Label.foreground");
            if (foreground == null) {
                foreground = new Color(90, 90, 90);
            }
            return new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), color.getAlpha());
        }));
        return icon.hasFound() ? icon : null;
    }

    private JScrollPane createConversationScrollPane() {
        var scrollPane = new JScrollPane(conversationList);
        scrollPane.addPropertyChangeListener("UI", event -> SwingUtilities.invokeLater(this::applyScrollPaneStyles));
        return scrollPane;
    }

    private void handleSelectionChanged(ListSelectionEvent event) {
        if (event.getValueIsAdjusting() || suppressSelection) {
            return;
        }

        selectedConversationId().ifPresent(id -> {
            if (onConversationSelected != null) {
                onConversationSelected.accept(id);
            }
        });
    }

    private void handleMouseClick(MouseEvent event) {
        int index = resolveListIndex(event.getPoint());
        if (index < 0) {
            return;
        }

        Object entry = listModel.get(index);
        if (entry instanceof GroupHeader header) {
            toggleGroup(header);
            return;
        }

        if (entry instanceof ConversationItem conversation && SwingUtilities.isLeftMouseButton(event)) {
            switch (resolveHoverIcon(event)) {
                case TRASH -> handleDelete(conversation, event.isShiftDown());
                case STAR -> handleToggleFavorite(conversation);
                case NONE -> {
                }
            }
        }
    }

    private void toggleGroup(GroupHeader header) {
        if (collapsedGroups.contains(header.name())) {
            collapsedGroups.remove(header.name());
        } else {
            collapsedGroups.add(header.name());
        }
        refresh();
    }

    private String resolveTooltip(MouseEvent event) {
        int index = resolveListIndex(event.getPoint());
        if (index < 0) {
            return null;
        }

        return conversationItemAt(index)
            .map(conversation -> switch (resolveHoverIcon(event)) {
                case TRASH -> "Delete, Shift+Delete to skip confirmation";
                case STAR -> conversation.isFavorite() ? "Remove from favorites" : "Add to favorites";
                case NONE -> null;
            })
            .orElse(null);
    }

    private void paintHoverIcons(Graphics graphics) {
        if (hoveredIndex < 0 || hoveredIndex >= listModel.size()) {
            return;
        }

        Optional<ConversationItem> hoveredConversation = conversationItemAt(hoveredIndex);
        if (hoveredConversation.isEmpty()) {
            return;
        }

        Rectangle bounds = conversationList.getCellBounds(hoveredIndex, hoveredIndex);
        if (bounds == null) {
            return;
        }

        Rectangle visible = conversationList.getVisibleRect();
        int rightEdge = visible.x + visible.width;
        int iconY = bounds.y + (bounds.height - HOVER_ICON_SIZE) / 2;
        var layout = HoverIconLayout.fromRightEdge(rightEdge);

        paintHoverBackground(graphics, bounds, layout.backgroundLeft(), rightEdge);

        Icon trashIcon = trashIcon();
        Icon starIcon = hoveredConversation.get().isFavorite() ? starFilledIcon() : starIcon();
        if (trashIcon != null) {
            trashIcon.paintIcon(this, graphics, layout.trashX(), iconY);
        }
        if (starIcon != null) {
            starIcon.paintIcon(this, graphics, layout.starX(), iconY);
        }
    }

    private void paintHoverBackground(Graphics graphics, Rectangle bounds, int backgroundLeft, int rightEdge) {
        boolean isSelected = conversationList.getSelectedIndex() == hoveredIndex;

        Color background = isSelected
            ? UIManager.getColor("List.selectionBackground")
            : conversationList.getParent() != null ? conversationList.getParent().getBackground() : getBackground();

        if (background == null) {
            background = getBackground();
        }

        Graphics2D graphics2d = (Graphics2D) graphics.create();
        graphics2d.setPaint(new GradientPaint(
            backgroundLeft - HOVER_BACKGROUND_FADE_WIDTH,
            0,
            new Color(background.getRed(), background.getGreen(), background.getBlue(), 0),
            backgroundLeft,
            0,
            background
        ));
        graphics2d.fillRect(
            backgroundLeft - HOVER_BACKGROUND_FADE_WIDTH,
            bounds.y,
            HOVER_BACKGROUND_FADE_WIDTH,
            bounds.height
        );
        graphics2d.setColor(background);
        graphics2d.fillRect(backgroundLeft, bounds.y, rightEdge - backgroundLeft, bounds.height);
        graphics2d.dispose();
    }

    private void handleDelete(ConversationItem conversation, boolean skipConfirmation) {
        if (skipConfirmation) {
            deleteConversation(conversation);
            return;
        }

        int confirmation = showThemedConfirmDialog("Delete \"%s\"?".formatted(conversation.title()), "Confirm");
        if (confirmation == JOptionPane.YES_OPTION) {
            deleteConversation(conversation);
        }
    }

    private void deleteConversation(ConversationItem conversation) {
        runRepositoryAction(DELETE_CONVERSATION_ERROR, CONVERSATIONS_TITLE, () -> {
            conversationRepo.deleteConversation(conversation.id());
            refresh();
            if (onNewChat != null) {
                onNewChat.run();
            }
        });
    }

    private void handleDeleteGroup(String groupName) {
        int confirmation = showThemedConfirmDialog(
                "Delete all chats in \"%s\"?".formatted(groupName), "Confirm");
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }

        runRepositoryAction(DELETE_GROUP_ERROR, CONVERSATIONS_TITLE, () -> {
            Map<String, List<ConversationRepo.ConversationRecord>> grouped = conversationRepo.findAllGroupedByDate();
            List<ConversationRepo.ConversationRecord> groupRecords = grouped.get(groupName);
            if (ObjectUtils.isNotEmpty(groupRecords)) {
                List<UUID> ids = groupRecords.stream().map(ConversationRepo.ConversationRecord::id).toList();
                conversationRepo.deleteConversations(ids);
                refresh();
                if (onNewChat != null) {
                    onNewChat.run();
                }
            }
        });
    }

    private void handleDeleteAll() {
        int confirmation = showThemedConfirmDialog("Delete all chats?", "Confirm");
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }

        runRepositoryAction(DELETE_ALL_ERROR, CONVERSATIONS_TITLE, () -> {
            conversationRepo.deleteAllConversations();
            refresh();
            if (onNewChat != null) {
                onNewChat.run();
            }
        });
    }

    private void handleToggleFavorite(ConversationItem conversation) {
        runRepositoryAction(FAVORITES_ERROR, CONVERSATIONS_TITLE, () -> {
            conversationRepo.toggleFavorite(conversation.id());
            refresh();
        });
    }

    private void renameConversation(ConversationItem conversation, String newTitle) {
        runRepositoryAction(RENAME_CONVERSATION_ERROR, CONVERSATIONS_TITLE, () -> {
            conversationRepo.updateTitle(conversation.id(), newTitle);
            refresh();
        });
    }

    private void applyRefreshResult(long refreshRequestId, Map<String, List<ConversationRecord>> grouped) {
        if (refreshRequestId != refreshRequestCounter.get()) {
            return;
        }

        UUID selectedConversationId = pendingSelectionConversationId != null
                ? pendingSelectionConversationId
                : selectedConversationId().orElse(null);

        lastGroupedConversations = grouped;
        withSelectionSuppressed(() -> {
            int restoreIndex = rebuildConversationList(filteredConversations(grouped), selectedConversationId);
            restoreSelection(restoreIndex);
            applyPendingSelection();
        });
    }

    private void applyConversationFilter() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::applyConversationFilter);
            return;
        }

        UUID selectedConversationId = pendingSelectionConversationId != null
                ? pendingSelectionConversationId
                : selectedConversationId().orElse(null);
        withSelectionSuppressed(() -> {
            int restoreIndex = rebuildConversationList(filteredConversations(lastGroupedConversations), selectedConversationId);
            restoreSelection(restoreIndex);
            applyPendingSelection();
        });
    }

    private Map<String, List<ConversationRecord>> filteredConversations(Map<String, List<ConversationRecord>> grouped) {
        String query = StringUtils.trimToEmpty(filterField == null ? "" : filterField.getText());
        if (StringUtils.isBlank(query)) {
            return grouped;
        }

        String normalizedQuery = query.toLowerCase();
        Map<String, List<ConversationRecord>> filtered = new java.util.LinkedHashMap<>();
        grouped.forEach((groupName, records) -> {
            List<ConversationRecord> matches = records.stream()
                    .filter(record -> conversationMatches(record, normalizedQuery))
                    .toList();
            if (!matches.isEmpty()) {
                filtered.put(groupName, matches);
            }
        });
        return filtered;
    }

    private boolean conversationMatches(ConversationRecord record, String normalizedQuery) {
        return StringUtils.containsIgnoreCase(record.title(), normalizedQuery)
                || StringUtils.containsIgnoreCase(record.provider(), normalizedQuery)
                || StringUtils.containsIgnoreCase(record.model(), normalizedQuery)
                || StringUtils.containsIgnoreCase(record.agentProjectRoot(), normalizedQuery);
    }

    private void applyRefreshFailure(long refreshRequestId, Exception e) {
        if (refreshRequestId != refreshRequestCounter.get()) {
            return;
        }

        lastGroupedConversations = Map.of();
        listModel.clear();
        hoveredIndex = -1;
        showOperationError(LOAD_CONVERSATIONS_ERROR, CONVERSATIONS_TITLE, e, JOptionPane.WARNING_MESSAGE);
    }

    private void applyPendingSelection() {
        if (pendingSelectionConversationId == null) {
            return;
        }

        UUID pendingId = pendingSelectionConversationId;
        findConversationIndex(pendingId).ifPresent(index -> {
            conversationList.setSelectedIndex(index);
            conversationList.ensureIndexIsVisible(index);
            pendingSelectionConversationId = null;
        });
    }

    private void showContextMenu(MouseEvent event) {
        int index = resolveListIndex(event.getPoint());
        if (index < 0) {
            return;
        }

        conversationItemAt(index).ifPresent(conversation -> {
            conversationList.setSelectedIndex(index);
            String groupName = findGroupForIndex(index);
            JPopupMenu menu = createContextMenu(conversation, groupName);
            menu.show(conversationList, event.getX(), event.getY());
        });
    }

    private String findGroupForIndex(int index) {
        for (int i = index - 1; i >= 0; i--) {
            Object entry = listModel.get(i);
            if (entry instanceof GroupHeader header) {
                return header.name();
            }
        }
        return null;
    }

    private JPopupMenu createContextMenu(ConversationItem conversation, String groupName) {
        var menu = new JPopupMenu();
        menu.add(createRenameMenuItem(conversation));
        menu.add(createFavoriteMenuItem(conversation));
        menu.addSeparator();
        menu.add(createDeleteMenuItem(conversation));
        if (groupName != null && !FAVORITES_TITLE.equals(groupName)) {
            menu.add(createDeleteGroupMenuItem(groupName));
        }
        menu.add(createDeleteAllMenuItem());
        return menu;
    }

    private JMenuItem createRenameMenuItem(ConversationItem conversation) {
        var renameMenuItem = new JMenuItem("Rename");
        renameMenuItem.addActionListener(event -> promptRename(conversation));
        return renameMenuItem;
    }

    private JMenuItem createFavoriteMenuItem(ConversationItem conversation) {
        var favoriteMenuItem = new JMenuItem(conversation.isFavorite() ? "Unfavorite" : "Favorite");
        favoriteMenuItem.addActionListener(event -> handleToggleFavorite(conversation));
        return favoriteMenuItem;
    }

    private JMenuItem createDeleteMenuItem(ConversationItem conversation) {
        var deleteMenuItem = new JMenuItem("Delete");
        deleteMenuItem.addActionListener(event -> handleDelete(conversation, false));
        return deleteMenuItem;
    }

    private JMenuItem createDeleteGroupMenuItem(String groupName) {
        var menuItem = new JMenuItem("Delete \"%s\"".formatted(groupName));
        menuItem.addActionListener(event -> handleDeleteGroup(groupName));
        return menuItem;
    }

    private JMenuItem createDeleteAllMenuItem() {
        var menuItem = new JMenuItem("Delete All Chats");
        menuItem.addActionListener(event -> handleDeleteAll());
        return menuItem;
    }

    private void promptRename(ConversationItem conversation) {
        String newTitle = showThemedInputDialog("New title:", conversation.title());
        if (StringUtils.isBlank(newTitle)) {
            return;
        }

        renameConversation(conversation, newTitle.trim());
    }

    private String showThemedInputDialog(String message, String initialValue) {
        var textField = new JTextField(StringUtils.defaultString(initialValue), 24);
        textField.selectAll();

        var content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(new JLabel(message), BorderLayout.NORTH);
        content.add(textField, BorderLayout.CENTER);

        var pane = new JOptionPane(content, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setContentPane(pane);
        pane.addPropertyChangeListener(event -> {
            if (dialog.isVisible()
                && event.getSource() == pane
                && JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())) {
                dialog.setVisible(false);
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(() -> {
            textField.requestFocusInWindow();
            textField.selectAll();
        });
        dialog.setVisible(true);
        dialog.dispose();

        Object value = pane.getValue();
        if (!Objects.equals(value, JOptionPane.OK_OPTION)) {
            return null;
        }
        return textField.getText();
    }

    private int showThemedConfirmDialog(String message, String title) {
        var pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setContentPane(pane);
        pane.addPropertyChangeListener(event -> {
            if (dialog.isVisible()
                && event.getSource() == pane
                && JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())) {
                dialog.setVisible(false);
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        dialog.dispose();

        Object value = pane.getValue();
        return value instanceof Integer selectedValue ? selectedValue : JOptionPane.CLOSED_OPTION;
    }

    private int rebuildConversationList(Map<String, List<ConversationRecord>> grouped, UUID selectedConversationId) {
        listModel.clear();
        hoveredIndex = -1;

        int index = 0;
        int restoreIndex = -1;

        List<ConversationRecord> favorites = grouped.values().stream()
                .flatMap(List::stream)
                .filter(ConversationRecord::isFavorite)
                .toList();
        if (!favorites.isEmpty()) {
            boolean collapsed = collapsedGroups.contains(FAVORITES_TITLE);
            listModel.addElement(new GroupHeader(FAVORITES_TITLE, collapsed));
            index++;

            if (!collapsed) {
                for (ConversationRecord record : favorites) {
                    listModel.addElement(toConversationItem(record));
                    if (record.id().equals(selectedConversationId)) {
                        restoreIndex = index;
                    }
                    index++;
                }
            }
        }

        Map<String, List<ConversationRecord>> projectGroups = projectGroups(grouped);
        for (var projectEntry : projectGroups.entrySet()) {
            boolean collapsed = collapsedGroups.contains(projectEntry.getKey());
            listModel.addElement(new GroupHeader(projectEntry.getKey(), collapsed));
            index++;

            if (collapsed) {
                continue;
            }

            for (ConversationRecord record : projectEntry.getValue()) {
                listModel.addElement(toConversationItem(record));
                if (record.id().equals(selectedConversationId)) {
                    restoreIndex = index;
                }
                index++;
            }
        }

        for (var entry : grouped.entrySet()) {
            if (FAVORITES_TITLE.equals(entry.getKey())) {
                continue;
            }

            List<ConversationRecord> dateGroupRecords = entry.getValue().stream()
                    .filter(record -> !record.isFavorite() && !isProjectConversation(record))
                    .toList();
            if (dateGroupRecords.isEmpty()) {
                continue;
            }

            boolean collapsed = collapsedGroups.contains(entry.getKey());
            listModel.addElement(new GroupHeader(entry.getKey(), collapsed));
            index++;

            if (collapsed) {
                continue;
            }

            for (ConversationRecord record : dateGroupRecords) {
                var conversation = toConversationItem(record);
                listModel.addElement(conversation);
                if (record.id().equals(selectedConversationId)) {
                    restoreIndex = index;
                }
                index++;
            }
        }

        return restoreIndex;
    }

    private Map<String, List<ConversationRecord>> projectGroups(Map<String, List<ConversationRecord>> grouped) {
        Map<String, List<ConversationRecord>> projectGroups = new LinkedHashMap<>();
        grouped.values().stream()
                .flatMap(List::stream)
                .filter(record -> !record.isFavorite() && isProjectConversation(record))
                .forEach(record -> projectGroups.computeIfAbsent(projectGroupName(record.agentProjectRoot()), ignored -> new java.util.ArrayList<>())
                        .add(record));
        return projectGroups;
    }

    private boolean isProjectConversation(ConversationRecord record) {
        return StringUtils.isNotBlank(record.agentProjectRoot());
    }

    private String projectGroupName(String projectRoot) {
        String trimmedRoot = StringUtils.trimToEmpty(projectRoot);
        String folderName;
        try {
            Path fileName = Path.of(trimmedRoot).getFileName();
            folderName = fileName == null ? trimmedRoot : fileName.toString();
        } catch (Exception e) {
            folderName = trimmedRoot;
        }
        return "Project · %s".formatted(StringUtils.defaultIfBlank(folderName, "Unknown"));
    }

    private ConversationItem toConversationItem(ConversationRecord record) {
        return new ConversationItem(
                record.id(),
                record.title(),
                record.provider(),
                record.model(),
                record.isFavorite(),
                record.updatedAt()
        );
    }

    private void restoreSelection(int index) {
        if (index >= 0) {
            conversationList.setSelectedIndex(index);
        } else {
            conversationList.clearSelection();
        }
    }

    private void expandGroupContaining(UUID id) {
        try {
            Optional<String> groupName = conversationRepo.findAllGroupedByDate()
                .entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                    .anyMatch(record -> record.id().equals(id)))
                .map(Map.Entry::getKey)
                .findFirst();

            if (groupName.isPresent() && collapsedGroups.remove(groupName.get())) {
                refresh();
            }
        } catch (Exception e) {
            showOperationError(LOAD_CONVERSATIONS_ERROR, CONVERSATIONS_TITLE, e, JOptionPane.WARNING_MESSAGE);
        }
    }

    private Optional<UUID> selectedConversationId() {
        Object selectedValue = conversationList != null ? conversationList.getSelectedValue() : null;
        return selectedValue instanceof ConversationItem conversation ? Optional.of(conversation.id()) : Optional.empty();
    }

    private OptionalInt findConversationIndex(UUID id) {
        return IntStream.range(0, listModel.size())
            .filter(index -> conversationItemAt(index)
                .map(conversation -> conversation.id().equals(id))
                .orElse(false))
            .findFirst();
    }

    private Optional<ConversationItem> conversationItemAt(int index) {
        if (index < 0 || index >= listModel.size()) {
            return Optional.empty();
        }

        Object entry = listModel.get(index);
        return entry instanceof ConversationItem conversation ? Optional.of(conversation) : Optional.empty();
    }

    private int resolveListIndex(Point point) {
        int index = conversationList.locationToIndex(point);
        if (index < 0) {
            return -1;
        }

        Rectangle bounds = conversationList.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }

    private HoverIcon resolveHoverIcon(MouseEvent event) {
        Rectangle visible = conversationList.getVisibleRect();
        int relativeX = event.getX() - visible.x;
        var bounds = HoverIconBounds.forCellWidth(visible.width);

        if (relativeX >= bounds.starLeft() && relativeX <= bounds.starRight()) {
            return HoverIcon.STAR;
        }
        if (relativeX >= bounds.trashLeft() && relativeX <= bounds.trashRight()) {
            return HoverIcon.TRASH;
        }
        return HoverIcon.NONE;
    }

    private void updateHoveredIndex(int newIndex) {
        if (hoveredIndex == newIndex) {
            return;
        }

        repaintCell(hoveredIndex);
        hoveredIndex = newIndex;
        repaintCell(hoveredIndex);
    }

    private void repaintCell(int index) {
        if (index < 0 || index >= listModel.size()) {
            return;
        }

        Rectangle bounds = conversationList.getCellBounds(index, index);
        if (bounds != null) {
            conversationList.repaint(bounds);
        }
    }

    private void repaintConversation(UUID conversationId) {
        findConversationIndex(conversationId).ifPresent(this::repaintCell);
    }

    private void advanceLoadingIconFrame() {
        loadingIconFrame = (loadingIconFrame + 1) % LoadingIcon.SEGMENT_COUNT;
        streamingConversationIds.forEach(this::repaintConversation);
    }

    private void updateLoadingAnimationState() {
        if (streamingConversationIds.isEmpty()) {
            loadingIconTimer.stop();
            return;
        }
        if (!loadingIconTimer.isRunning()) {
            loadingIconFrame = 0;
            loadingIconTimer.start();
        }
    }

    private void runRepositoryAction(String errorMessage, String title, RepositoryAction action) {
        try {
            action.run();
        } catch (Exception e) {
            showOperationError(errorMessage, title, e, JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showOperationError(String message, String title, Exception exception, int messageType) {
        JOptionPane.showMessageDialog(
            this,
            "%s: %s".formatted(message, exception.getMessage()),
            title,
            messageType
        );
    }

    private void withSelectionSuppressed(Runnable action) {
        suppressSelection = true;
        try {
            action.run();
        } finally {
            suppressSelection = false;
        }
    }

    private void applyScrollPaneStyles() {
        if (conversationScrollPane == null) {
            return;
        }

        conversationScrollPane.setBorder(null);
        conversationScrollPane.setViewportBorder(null);
        conversationScrollPane.setOpaque(false);
        conversationScrollPane.getViewport().setOpaque(false);
        conversationScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        conversationScrollPane.getVerticalScrollBar().setUnitIncrement(14);
    }

    private void applyThemeBackgroundTint() {
        Color background = UIManager.getColor("Panel.background");
        if (background != null) {
            float[] hsb = Color.RGBtoHSB(background.getRed(), background.getGreen(), background.getBlue(), null);
            boolean isLight = hsb[2] > 0.5f;
            float brightness = clamp(hsb[2] + (isLight ? -0.03f : 0.05f));
            float saturation = clamp(hsb[1] + (isLight ? 0.01f : -0.01f));
            setBackground(Color.getHSBColor(hsb[0], saturation, brightness));
        } else {
            setBackground(UIManager.getColor("Panel.background"));
        }
        setOpaque(true);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static Icon trashIcon() {
        if (cachedTrashIcon == null) {
            cachedTrashIcon = loadHoverIcon("/icons/sidebar/trash.svg", new Color(200, 80, 80));
        }
        return cachedTrashIcon;
    }

    private static Icon starIcon() {
        if (cachedStarIcon == null) {
            cachedStarIcon = loadHoverIcon("/icons/sidebar/star.svg", new Color(220, 160, 60));
        }
        return cachedStarIcon;
    }

    private static Icon starFilledIcon() {
        if (cachedStarFilledIcon == null) {
            cachedStarFilledIcon = loadHoverIcon("/icons/sidebar/star-filled.svg", new Color(220, 160, 60));
        }
        return cachedStarFilledIcon;
    }

    private static Icon loadHoverIcon(String path, Color tint) {
        URL url = SidebarPanel.class.getResource(path);
        if (url == null) {
            return null;
        }

        var icon = new FlatSVGIcon(url).derive(HOVER_ICON_SIZE, HOVER_ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
            tint.getRed(),
            tint.getGreen(),
            tint.getBlue(),
            color.getAlpha()
        )));
        return icon.hasFound() ? icon : null;
    }

    private static Icon providerIcon(String providerName) {
        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(iconPath)) {
            return null;
        }

        return PROVIDER_ICON_CACHE.computeIfAbsent(iconPath, path -> {
            URL url = SidebarPanel.class.getResource(path);
            if (url == null) {
                return null;
            }

            var icon = new FlatSVGIcon(url).derive(PROVIDER_ICON_SIZE, PROVIDER_ICON_SIZE);
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
                    color.getAlpha()
                );
            }));
            return icon.hasFound() ? icon : null;
        });
    }

    private enum HoverIcon {
        TRASH,
        STAR,
        NONE
    }

    private record GroupHeader(String name, boolean collapsed) {
    }

    private record HoverIconBounds(int trashLeft, int trashRight, int starLeft, int starRight) {
        private static HoverIconBounds forCellWidth(int cellWidth) {
            int starRight = cellWidth - HOVER_ICON_PADDING;
            int starLeft = starRight - HOVER_ICON_SIZE;
            int trashRight = starLeft - HOVER_ICON_GAP;
            int trashLeft = trashRight - HOVER_ICON_SIZE;
            return new HoverIconBounds(trashLeft, trashRight, starLeft, starRight);
        }
    }

    private record HoverIconLayout(int trashX, int starX, int backgroundLeft) {
        private static HoverIconLayout fromRightEdge(int rightEdge) {
            int starX = rightEdge - HOVER_ICON_PADDING - HOVER_ICON_SIZE;
            int trashX = starX - HOVER_ICON_GAP - HOVER_ICON_SIZE;
            return new HoverIconLayout(trashX, starX, trashX - HOVER_BACKGROUND_PADDING);
        }
    }

    @FunctionalInterface
    private interface RepositoryAction {
        void run() throws Exception;
    }

    private static final class LoadingIcon implements Icon {

        private static final int SEGMENT_COUNT = 10;
        private static final int SIZE = 14;
        private static final float STROKE_WIDTH = 1.4f;
        private static final float INNER_RADIUS = 2.9f;
        private static final float OUTER_RADIUS = 5.4f;

        private final IntSupplier frameSupplier;

        private LoadingIcon(IntSupplier frameSupplier) {
            this.frameSupplier = frameSupplier;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D graphics2d = (Graphics2D) graphics.create();
            graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics2d.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Color baseColor = resolveBaseColor(component);
            boolean darkText = isDarkText(component);
            float centerX = x + SIZE / 2f;
            float centerY = y + SIZE / 2f;
            int frame = Math.floorMod(frameSupplier.getAsInt(), SEGMENT_COUNT);

            for (int offset = 0; offset < SEGMENT_COUNT; offset++) {
                int segment = Math.floorMod(frame + offset, SEGMENT_COUNT);
                double angle = Math.toRadians(segment * (360d / SEGMENT_COUNT) - 90d);
                float alpha = darkText
                        ? 0.20f + (SEGMENT_COUNT - offset) / (float) SEGMENT_COUNT * 0.60f
                        : 0.12f + (SEGMENT_COUNT - offset) / (float) SEGMENT_COUNT * 0.50f;
                graphics2d.setColor(new Color(
                        baseColor.getRed(),
                        baseColor.getGreen(),
                        baseColor.getBlue(),
                        Math.round(alpha * 255)
                ));
                int x1 = Math.round(centerX + (float) Math.cos(angle) * INNER_RADIUS);
                int y1 = Math.round(centerY + (float) Math.sin(angle) * INNER_RADIUS);
                int x2 = Math.round(centerX + (float) Math.cos(angle) * OUTER_RADIUS);
                int y2 = Math.round(centerY + (float) Math.sin(angle) * OUTER_RADIUS);
                graphics2d.drawLine(x1, y1, x2, y2);
            }

            graphics2d.dispose();
        }

        private static Color resolveBaseColor(Component component) {
            Color foreground = component != null ? component.getForeground() : null;
            if (foreground != null && perceivedBrightness(foreground) < 150) {
                return new Color(100, 106, 114);
            }

            Color selectionForeground = UIManager.getColor("List.selectionForeground");
            if (selectionForeground != null && selectionForeground.equals(foreground)) {
                return new Color(218, 223, 229);
            }

            Color color = UIManager.getColor("Label.disabledForeground");
            if (color != null) {
                return new Color(
                        Math.max(0, color.getRed() - 6),
                        Math.max(0, color.getGreen() - 4),
                        Math.max(0, color.getBlue())
                );
            }
            return new Color(136, 140, 146);
        }

        private static boolean isDarkText(Component component) {
            Color foreground = component != null ? component.getForeground() : null;
            return foreground != null && perceivedBrightness(foreground) < 150;
        }

        private static int perceivedBrightness(Color color) {
            return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
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

    private final class ConversationCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(
            JList<?> list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            if (value instanceof GroupHeader header) {
                var label = new JLabel(header.name());
                Fonts.apply(label, Font.BOLD, Fonts.SIZE_SMALL);
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
                label.setBorder(new EmptyBorder(8, 10, 4, 10));
                label.setOpaque(false);
                label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                label.setIcon(header.collapsed() ? new FlatTreeCollapsedIcon() : new FlatTreeExpandedIcon());
                label.setHorizontalTextPosition(SwingConstants.LEFT);
                return label;
            }

            if (value instanceof ConversationItem conversation) {
                var label = (JLabel) super.getListCellRendererComponent(
                    list,
                    conversation.title(),
                    index,
                    isSelected,
                    cellHasFocus
                );
                label.setBorder(new EmptyBorder(8, 14, 8, 10));
                Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
                label.setIcon(streamingConversationIds.contains(conversation.id())
                        ? loadingIcon
                        : providerIcon(conversation.provider()));
                label.setIconTextGap(8);

                if (isSelected) {
                    Color selectionBackground = UIManager.getColor("List.selectionBackground");
                    Color selectionForeground = UIManager.getColor("List.selectionForeground");
                    if (selectionBackground != null) {
                        label.setBackground(selectionBackground);
                    }
                    if (selectionForeground != null) {
                        label.setForeground(selectionForeground);
                    }
                }

                return label;
            }

            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
    }
}
