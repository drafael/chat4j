package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationRepo.ConversationRecord;
import com.github.drafael.chat4j.storage.ConversationRepo.SearchResult;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

public class ChatSearchPopup extends JDialog {

    private static final int PROVIDER_ICON_SIZE = 16;
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

    private final JTextField searchField;
    private final JPanel listPanel;
    private final JScrollPane scrollPane;
    private final ConversationRepo conversationRepo;
    private final Consumer<UUID> onSelect;
    private final List<JPanel> resultRows = new ArrayList<>();
    private final List<UUID> resultIds = new ArrayList<>();
    private Timer debounceTimer;
    private int highlightedIndex = -1;
    private boolean scrolling;
    private Component triggerComponent;
    private final AWTEventListener outsideClickListener;
    private boolean outsideClickListenerInstalled;

    public ChatSearchPopup(Window owner, ConversationRepo conversationRepo, Consumer<UUID> onSelect) {
        super(owner);
        this.conversationRepo = conversationRepo;
        this.onSelect = onSelect;
        setUndecorated(true);
        setType(Type.POPUP);
        setModalityType(ModalityType.MODELESS);

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

        // Search field
        searchField = new JTextField() {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)
                ));
            }
        };
        searchField.putClientProperty("JTextField.placeholderText", "Search chats...");
        searchField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        Fonts.apply(searchField, Font.PLAIN, Fonts.SIZE_BODY);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
            public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
            public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
        });
        content.add(searchField, BorderLayout.NORTH);

        // Results list
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> ensureHighlightVisible());
        content.add(scrollPane, BorderLayout.CENTER);

        setContentPane(content);

        // Keyboard navigation
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
                        moveHighlightTo(resultRows.size() - 1);
                    }
                    case KeyEvent.VK_ENTER -> {
                        e.consume();
                        selectHighlighted();
                    }
                }
            }
        });

        // Escape key dismisses
        getRootPane().registerKeyboardAction(
            e -> hidePopup(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        this.outsideClickListener = createOutsideClickListener();
    }

    public void show(Component relativeTo) {
        this.triggerComponent = relativeTo;
        searchField.setText("");
        listPanel.removeAll();
        resultRows.clear();
        showRecentChats();

        setSize(360, 400);

        if (relativeTo != null && relativeTo.isShowing()) {
            Point loc = relativeTo.getLocationOnScreen();
            int x = loc.x + (relativeTo.getWidth() - getWidth()) / 2;
            int y = loc.y + relativeTo.getHeight();
            setLocation(x, y);
        } else {
            centerOnScreen();
        }

        installOutsideClickListener();
        setVisible(true);
        searchField.requestFocusInWindow();
    }

    public void hidePopup() {
        setVisible(false);
        uninstallOutsideClickListener();
    }

    @Override
    public void dispose() {
        uninstallOutsideClickListener();
        super.dispose();
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

    private static Icon providerIcon(String providerName) {
        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(iconPath)) {
            return null;
        }

        return PROVIDER_ICON_CACHE.computeIfAbsent(iconPath, path -> {
            URL url = ChatSearchPopup.class.getResource(path);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(PROVIDER_ICON_SIZE, PROVIDER_ICON_SIZE);
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

    private void centerOnScreen() {
        GraphicsConfiguration configuration = getOwner() != null
                ? getOwner().getGraphicsConfiguration()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = configuration.getBounds();
        int x = bounds.x + (bounds.width - getWidth()) / 2;
        int y = bounds.y + (bounds.height - getHeight()) / 2;
        setLocation(x, y);
    }

    private void setHighlightedIndex(int newIndex) {
        if (newIndex == highlightedIndex) {
            return;
        }

        applyRowHighlight(highlightedIndex, false);
        highlightedIndex = newIndex;
        applyRowHighlight(highlightedIndex, true);
    }

    private void moveHighlightTo(int index) {
        if (resultRows.isEmpty()) {
            return;
        }

        setHighlightedIndex(Math.max(0, Math.min(resultRows.size() - 1, index)));
        scrollToHighlighted();
    }

    private void moveHighlight(int direction) {
        if (resultRows.isEmpty()) {
            return;
        }

        int newIndex;
        if (highlightedIndex < 0) {
            newIndex = direction > 0 ? 0 : resultRows.size() - 1;
        } else {
            newIndex = Math.max(0, Math.min(resultRows.size() - 1, highlightedIndex + direction));
        }

        setHighlightedIndex(newIndex);
        scrollToHighlighted();
    }

    private void applyRowHighlight(int index, boolean highlighted) {
        if (index < 0 || index >= resultRows.size()) {
            return;
        }

        JPanel row = resultRows.get(index);

        if (highlighted) {
            row.setOpaque(true);
            row.setBackground(UIManager.getColor("List.selectionBackground"));
            Component textPanel = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            if (textPanel instanceof JPanel tp && tp.getComponentCount() > 0
                    && tp.getComponent(0) instanceof JLabel titleLabel) {
                titleLabel.setForeground(UIManager.getColor("List.selectionForeground"));
            }
        } else {
            row.setOpaque(false);
            row.setBackground(UIManager.getColor("Panel.background"));
            Component textPanel = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            if (textPanel instanceof JPanel tp && tp.getComponentCount() > 0
                    && tp.getComponent(0) instanceof JLabel titleLabel) {
                titleLabel.setForeground(UIManager.getColor("Label.foreground"));
            }
        }
    }

    private void scrollToHighlighted() {
        if (highlightedIndex < 0 || highlightedIndex >= resultRows.size()) {
            return;
        }

        JPanel row = resultRows.get(highlightedIndex);
        Rectangle rowBounds = row.getBounds();
        Rectangle viewRect = scrollPane.getViewport().getViewRect();

        if (viewRect.contains(rowBounds)) {
            return;
        }

        // When scrolling up, include the preceding group header so it stays visible
        Rectangle scrollTarget = new Rectangle(rowBounds);
        int zOrder = listPanel.getComponentZOrder(row);
        if (zOrder > 0 && rowBounds.y < viewRect.y) {
            Component prev = listPanel.getComponent(zOrder - 1);
            if (prev instanceof JLabel) {
                scrollTarget = scrollTarget.union(prev.getBounds());
            }
        }

        listPanel.scrollRectToVisible(scrollTarget);
    }

    private void ensureHighlightVisible() {
        if (scrolling || highlightedIndex < 0 || highlightedIndex >= resultRows.size()) {
            return;
        }

        JPanel row = resultRows.get(highlightedIndex);
        Rectangle viewRect = scrollPane.getViewport().getViewRect();
        Rectangle rowBounds = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), listPanel);

        if (!viewRect.intersects(rowBounds)) {
            scrolling = true;
            try {
                listPanel.scrollRectToVisible(rowBounds);
            } finally {
                scrolling = false;
            }
        }
    }

    private int pageStep() {
        int viewportHeight = scrollPane.getViewport().getExtentSize().height;
        int rowHeight = resultRows.isEmpty() ? 36 : resultRows.getFirst().getHeight();
        if (rowHeight <= 0) {
            rowHeight = 36;
        }
        return Math.max(1, viewportHeight / rowHeight);
    }

    private void selectHighlighted() {
        if (highlightedIndex >= 0 && highlightedIndex < resultIds.size()) {
            onSelect.accept(resultIds.get(highlightedIndex));
            hidePopup();
        }
    }

    private void showRecentChats() {
        try {
            Map<String, List<ConversationRecord>> grouped = conversationRepo.findAllGroupedByDate();
            listPanel.removeAll();
            resultRows.clear();
            resultIds.clear();
            highlightedIndex = -1;

            for (Map.Entry<String, List<ConversationRecord>> entry : grouped.entrySet()) {
                addHeader(entry.getKey());
                for (ConversationRecord rec : entry.getValue()) {
                    addResultRow(rec.id(), rec.provider(), rec.title(), null);
                }
            }

            listPanel.revalidate();
            listPanel.repaint();
        } catch (Exception e) {
            // ignore
        }
    }

    private void scheduleSearch() {
        if (debounceTimer != null) {
            debounceTimer.stop();
        }
        debounceTimer = new Timer(200, e -> performSearch());
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showRecentChats();
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                List<SearchResult> results = conversationRepo.search(query);
                SwingUtilities.invokeLater(() -> {
                    listPanel.removeAll();
                    resultRows.clear();
                    resultIds.clear();
                    highlightedIndex = -1;

                    if (results.isEmpty()) {
                        JLabel noResults = new JLabel("No results found");
                        Fonts.apply(noResults, Font.PLAIN, Fonts.SIZE_BODY);
                        noResults.setForeground(UIManager.getColor("Label.disabledForeground"));
                        noResults.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));
                        noResults.setAlignmentX(Component.LEFT_ALIGNMENT);
                        listPanel.add(noResults);
                    } else {
                        addHeader("Results");
                        for (SearchResult result : results) {
                            addResultRow(
                                result.id(),
                                result.provider(),
                                result.title(),
                                result.snippet());
                        }
                    }

                    listPanel.revalidate();
                    listPanel.repaint();
                });
            } catch (Exception e) {
                // ignore
            }
        });
    }

    private void addHeader(String text) {
        JLabel header = new JLabel(text);
        Fonts.apply(header, Font.BOLD, Fonts.SIZE_SMALL);
        header.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(header);
    }

    private void addResultRow(UUID id, String provider, String title, String snippet) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, snippet != null ? 52 : 36));
        row.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(PROVIDER_ICON_SIZE, PROVIDER_ICON_SIZE));
        Icon providerIcon = providerIcon(provider);
        if (providerIcon != null) {
            iconLabel.setIcon(providerIcon);
        }
        row.add(iconLabel, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        Fonts.apply(titleLabel, Font.PLAIN, Fonts.SIZE_BODY);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textPanel.add(titleLabel);

        if (snippet != null) {
            String cleanSnippet = snippet.replace("\n", " ").trim();
            if (cleanSnippet.length() > 80) {
                cleanSnippet = "%s...".formatted(cleanSnippet.substring(0, 80));
            }
            JLabel snippetLabel = new JLabel(cleanSnippet);
            Fonts.apply(snippetLabel, Font.PLAIN, Fonts.SIZE_SMALL);
            snippetLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            snippetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textPanel.add(snippetLabel);
        }

        row.add(textPanel, BorderLayout.CENTER);

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                int index = resultRows.indexOf(row);
                if (index >= 0) {
                    setHighlightedIndex(index);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                onSelect.accept(id);
                hidePopup();
            }
        });

        resultRows.add(row);
        resultIds.add(id);
        listPanel.add(row);
    }
}
