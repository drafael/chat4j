package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationRepo.ConversationRecord;
import com.github.drafael.chat4j.storage.ConversationRepo.SearchResult;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ChatSearchPopup extends JDialog {

    private final JTextField searchField;
    private final JPanel listPanel;
    private final ConversationRepo conversationRepo;
    private final Consumer<UUID> onSelect;
    private final List<JPanel> resultRows = new ArrayList<>();
    private Timer debounceTimer;

    public ChatSearchPopup(Window owner, ConversationRepo conversationRepo, Consumer<UUID> onSelect) {
        super(owner);
        this.conversationRepo = conversationRepo;
        this.onSelect = onSelect;
        setUndecorated(true);
        setType(Type.POPUP);
        setModalityType(ModalityType.MODELESS);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1),
            BorderFactory.createEmptyBorder(8, 0, 8, 0)
        ));

        // Search field
        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search chats...");
        searchField.setFont(Fonts.of(Font.PLAIN, 13));
        searchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
            public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
            public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
        });
        content.add(searchField, BorderLayout.NORTH);

        // Results list
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scrollPane, BorderLayout.CENTER);

        setContentPane(content);

        // Escape key dismisses
        getRootPane().registerKeyboardAction(
            e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Dismiss on focus loss
        addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {}

            @Override
            public void windowLostFocus(WindowEvent e) {
                dispose();
            }
        });
    }

    public void show(Component relativeTo) {
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

        setVisible(true);
        searchField.requestFocusInWindow();
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

    private void showRecentChats() {
        try {
            Map<String, List<ConversationRecord>> grouped = conversationRepo.findAllGroupedByDate();
            listPanel.removeAll();
            resultRows.clear();

            for (Map.Entry<String, List<ConversationRecord>> entry : grouped.entrySet()) {
                addHeader(entry.getKey());
                for (ConversationRecord rec : entry.getValue()) {
                    addResultRow(rec.id(), rec.title(), rec.provider() + " > " + rec.model(), null);
                }
            }

            listPanel.revalidate();
            listPanel.repaint();
        } catch (Exception e) {
            // ignore
        }
    }

    private void scheduleSearch() {
        if (debounceTimer != null) debounceTimer.stop();
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

                    if (results.isEmpty()) {
                        JLabel noResults = new JLabel("No results found");
                        noResults.setFont(noResults.getFont().deriveFont(Font.PLAIN, 13f));
                        noResults.setForeground(UIManager.getColor("Label.disabledForeground"));
                        noResults.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));
                        noResults.setAlignmentX(Component.LEFT_ALIGNMENT);
                        listPanel.add(noResults);
                    } else {
                        addHeader("Results");
                        for (SearchResult result : results) {
                            addResultRow(
                                result.id(),
                                result.title(),
                                result.provider() + " > " + result.model(),
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
        header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
        header.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        listPanel.add(header);
    }

    private void addResultRow(UUID id, String title, String subtitle, String snippet) {
        JPanel row = new JPanel(new BorderLayout());
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, snippet != null ? 52 : 36));
        row.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textPanel.add(titleLabel);

        if (snippet != null) {
            String cleanSnippet = snippet.replace("\n", " ").trim();
            if (cleanSnippet.length() > 80) cleanSnippet = cleanSnippet.substring(0, 80) + "...";
            JLabel snippetLabel = new JLabel(cleanSnippet);
            snippetLabel.setFont(snippetLabel.getFont().deriveFont(Font.PLAIN, 11f));
            snippetLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            snippetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textPanel.add(snippetLabel);
        }

        row.add(textPanel, BorderLayout.CENTER);

        Color defaultBg = row.getBackground();
        Color hoverBg = UIManager.getColor("List.selectionBackground");
        Color hoverFg = UIManager.getColor("List.selectionForeground");
        Color defaultFg = titleLabel.getForeground();

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                row.setBackground(hoverBg);
                titleLabel.setForeground(hoverFg);
                row.setOpaque(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                row.setOpaque(false);
                row.setBackground(defaultBg);
                titleLabel.setForeground(defaultFg);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                onSelect.accept(id);
                dispose();
            }
        });

        resultRows.add(row);
        listPanel.add(row);
    }
}
