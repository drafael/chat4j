package com.github.drafael.chat4j.prompts;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.github.drafael.chat4j.util.Fonts;
import lombok.NonNull;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Collections.emptyList;

public class PromptCommandCenter extends JDialog {

    private static final int WIDTH = 440;
    private static final int HEIGHT = 420;

    private final PromptCatalogRepo promptCatalogRepo;
    private final Supplier<List<CommandCenterAction>> commandActionsSupplier;
    private final Consumer<PromptTemplate> promptSelected;
    private final JTextField searchField = new JTextField() {
        @Override
        public void updateUI() {
            super.updateUI();
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, resolveSeparatorColor()),
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)
            ));
        }
    };
    private final DefaultListModel<CommandCenterItem> model = new DefaultListModel<>();
    private final JList<CommandCenterItem> promptList = new JList<>(model);
    private final AWTEventListener outsideClickListener;
    private List<PromptTemplate> prompts = new ArrayList<>();
    private Component triggerComponent;
    private boolean outsideClickListenerInstalled;

    public PromptCommandCenter(
            @NonNull Window owner,
            @NonNull PromptCatalogRepo promptCatalogRepo,
            @NonNull Supplier<List<CommandCenterAction>> commandActionsSupplier,
            @NonNull Consumer<PromptTemplate> promptSelected
    ) {
        super(owner);
        this.promptCatalogRepo = promptCatalogRepo;
        this.commandActionsSupplier = commandActionsSupplier;
        this.promptSelected = promptSelected;

        setUndecorated(true);
        setModal(false);
        setSize(WIDTH, HEIGHT);
        setType(Type.POPUP);
        setModalityType(ModalityType.MODELESS);
        setLayout(new BorderLayout());
        add(createSearchPanel(), BorderLayout.NORTH);
        add(createListPane(), BorderLayout.CENTER);
        getRootPane().setBorder(BorderFactory.createLineBorder(resolveBorderColor(), 1));
        outsideClickListener = createOutsideClickListener();
        installActions();
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hidePopup();
            }
        });
    }

    public void openNear(@NonNull Component relativeTo) {
        triggerComponent = relativeTo;
        SwingUtilities.updateComponentTreeUI(this);
        getRootPane().setBorder(BorderFactory.createLineBorder(resolveBorderColor(), 1));
        searchField.setText("");
        prompts = emptyList();
        filterPrompts();
        positionNear(relativeTo);
        installOutsideClickListener();
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            toFront();
            requestFocus();
            searchField.requestFocusInWindow();
            searchField.selectAll();
        });
        loadPromptsAsync();
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

    private JComponent createSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBorder(new EmptyBorder(0, 0, 0, 0));
        panel.setBackground(resolvePopupBackground());

        searchField.putClientProperty("JTextField.placeholderText", "Type a command or search...");
        searchField.putClientProperty("JTextField.leadingIcon", new FlatSearchIcon());
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        Fonts.apply(searchField, Font.PLAIN, Fonts.SIZE_BODY);
        searchField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectPrompt");
        searchField.getActionMap().put("selectPrompt", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectCurrentPrompt();
            }
        });
        searchField.getDocument().addDocumentListener((SimpleDocumentListener) this::filterPrompts);
        panel.add(searchField, BorderLayout.CENTER);

        return panel;
    }

    private JComponent createListPane() {
        promptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        promptList.setCellRenderer(new PromptRowRenderer());
        promptList.setFixedCellHeight(38);
        promptList.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectPrompt");
        promptList.getActionMap().put("selectPrompt", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectCurrentPrompt();
            }
        });
        promptList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectCurrentPrompt();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(promptList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(resolvePopupBackground());
        return scrollPane;
    }

    private void installActions() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hidePopup();
            }
        });

        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        moveSelection(1);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        moveSelection(-1);
                        e.consume();
                    }
                    case KeyEvent.VK_PAGE_DOWN -> {
                        moveSelection(pageStep());
                        e.consume();
                    }
                    case KeyEvent.VK_PAGE_UP -> {
                        moveSelection(-pageStep());
                        e.consume();
                    }
                    case KeyEvent.VK_HOME -> {
                        moveSelectionTo(0);
                        e.consume();
                    }
                    case KeyEvent.VK_END -> {
                        moveSelectionTo(model.size() - 1);
                        e.consume();
                    }
                    default -> {
                    }
                }
            }
        });
    }

    private int pageStep() {
        int rowHeight = Math.max(1, promptList.getFixedCellHeight());
        int visibleRows = Math.max(1, promptList.getVisibleRect().height / rowHeight);
        return Math.max(1, visibleRows - 1);
    }

    private void moveSelection(int delta) {
        if (model.isEmpty()) {
            return;
        }
        int current = Math.max(0, promptList.getSelectedIndex());
        moveSelectionTo(current + delta);
    }

    private void moveSelectionTo(int index) {
        if (model.isEmpty()) {
            return;
        }
        int next = Math.max(0, Math.min(model.size() - 1, index));
        promptList.setSelectedIndex(next);
        promptList.ensureIndexIsVisible(next);
    }

    private void loadPromptsAsync() {
        Thread.startVirtualThread(() -> {
            List<PromptTemplate> loadedPrompts = promptCatalogRepo.load();
            SwingUtilities.invokeLater(() -> {
                if (!isVisible()) {
                    return;
                }
                prompts = loadedPrompts;
                filterPrompts();
            });
        });
    }

    private void filterPrompts() {
        String query = StringUtils.trimToEmpty(searchField.getText()).toLowerCase(Locale.ROOT);
        model.clear();
        prompts.stream()
                .filter(prompt -> query.isBlank()
                        || prompt.title().toLowerCase(Locale.ROOT).contains(query)
                        || prompt.id().toLowerCase(Locale.ROOT).contains(query))
                .map(CommandCenterItem::prompt)
                .forEach(model::addElement);
        commandActionsSupplier.get().stream()
                .filter(CommandCenterAction::isVisible)
                .filter(command -> query.isBlank() || command.title().toLowerCase(Locale.ROOT).contains(query))
                .map(CommandCenterItem::command)
                .forEach(model::addElement);
        if (!model.isEmpty()) {
            promptList.setSelectedIndex(0);
        }
    }

    private void selectCurrentPrompt() {
        CommandCenterItem selected = promptList.getSelectedValue();
        if (selected == null) {
            return;
        }
        hidePopup();
        selected.invoke(promptSelected);
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

    private void positionNear(Component relativeTo) {
        Point location = relativeTo.getLocationOnScreen();
        Window owner = SwingUtilities.getWindowAncestor(relativeTo);
        Rectangle bounds = owner == null ? new Rectangle(location.x, location.y, WIDTH, HEIGHT) : owner.getBounds();
        int x = bounds.x + Math.max(12, (bounds.width - WIDTH) / 2);
        int y = Math.max(bounds.y + 60, location.y - HEIGHT - 24);
        setLocation(x, y);
    }

    private Color resolvePopupBackground() {
        Color color = UIManager.getColor("PopupMenu.background");
        return color != null ? color : UIManager.getColor("Panel.background");
    }

    private Color resolveBorderColor() {
        Color color = ObjectUtils.firstNonNull(
                UIManager.getColor("Component.borderColor"),
                UIManager.getColor("Separator.foreground")
        );
        return color != null ? color : Color.GRAY;
    }

    private Color resolveSeparatorColor() {
        Color color = UIManager.getColor("Separator.foreground");
        return color != null ? color : resolveBorderColor();
    }

    private record CommandCenterItem(PromptTemplate prompt, CommandCenterAction command) {
        private CommandCenterItem {
            if ((prompt == null && command == null) || (prompt != null && command != null)) {
                throw new IllegalArgumentException("Command center item must contain exactly one item type");
            }
        }

        static CommandCenterItem prompt(PromptTemplate prompt) {
            return new CommandCenterItem(prompt, null);
        }

        static CommandCenterItem command(CommandCenterAction command) {
            return new CommandCenterItem(null, command);
        }

        String title() {
            return prompt != null ? prompt.title() : command.title();
        }

        String metadata() {
            if (prompt != null) {
                return "Prompt";
            }

            return StringUtils.defaultIfBlank(command.keyBinding(), "Command");
        }

        void invoke(Consumer<PromptTemplate> promptSelected) {
            if (prompt != null) {
                promptSelected.accept(prompt);
                return;
            }
            if (command.isVisible()) {
                command.action().run();
            }
        }

        @Override
        public String toString() {
            return "CommandCenterItem[title=%s]".formatted(title());
        }
    }

    private final class PromptRowRenderer extends JPanel implements ListCellRenderer<CommandCenterItem> {
        private final JLabel title = new JLabel();
        private final JLabel type = new JLabel();

        private PromptRowRenderer() {
            super(new BorderLayout());
            setBorder(new EmptyBorder(0, 12, 0, 12));
            Fonts.apply(title, Font.PLAIN, Fonts.SIZE_BODY);
            Fonts.apply(type, Font.PLAIN, Fonts.SIZE_BODY);
            type.setForeground(UIManager.getColor("Label.disabledForeground"));
            add(title, BorderLayout.CENTER);
            add(type, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends CommandCenterItem> list,
                CommandCenterItem value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            title.setText(value.title());
            type.setText(value.metadata());
            setBackground(isSelected ? UIManager.getColor("List.selectionBackground") : resolvePopupBackground());
            title.setForeground(isSelected ? UIManager.getColor("List.selectionForeground") : UIManager.getColor("Label.foreground"));
            type.setForeground(isSelected ? UIManager.getColor("List.selectionForeground") : UIManager.getColor("Label.disabledForeground"));
            setOpaque(true);
            return this;
        }
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends DocumentListener {
        void update();

        @Override
        default void insertUpdate(DocumentEvent e) {
            update();
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update();
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update();
        }
    }
}
