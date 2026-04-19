package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatFileViewFileIcon;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyList;

public class InputBar extends JPanel {

    private static final int SHELL_ARC = 20;
    private static final int CHIP_ICON_SIZE = 12;
    private static final int ATTACH_ICON_SIZE = 16;
    private static final int STOP_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int STOP_BUTTON_SIZE = 24;
    private static final int MENU_ICON_SIZE = 14;
    private static final float CHIP_HOVER_BG_DELTA = 0.09f;
    private static final float CHIP_HOVER_BORDER_DELTA = 0.12f;
    private static final Map<String, Icon> CHIP_ICON_CACHE = new ConcurrentHashMap<>();

    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private final JPanel chipsPanel;
    private final JPanel composerShell;
    private final JButton attachButton;
    private final JLabel validationLabel;
    private JPanel generationIndicatorPanel;
    private JPanel generationInfoPanel;
    private JProgressBar generationProgressBar;
    private JLabel generationLabel;
    private JButton jumpToLatestButton;
    private JButton cancelGenerationButton;
    private final JPopupMenu slashPopup = new JPopupMenu();
    private final DefaultListModel<SkillCommand> slashSuggestions = new DefaultListModel<>();
    private final JList<SkillCommand> slashSuggestionsList = new JList<>(slashSuggestions);
    private final List<ComposerAttachment> attachments = new ArrayList<>();
    private final List<String> activeSkills = new ArrayList<>();
    private final List<SkillCommand> availableSkills = new ArrayList<>();
    private final AttachmentSelectionPolicy attachmentSelectionPolicy = new AttachmentSelectionPolicy();
    private final List<ActionListener> sendListeners = new ArrayList<>();
    private final List<ActionListener> jumpToLatestListeners = new ArrayList<>();
    private final List<ActionListener> cancelGenerationListeners = new ArrayList<>();
    private boolean sendOnEnter = true;
    private int slashTokenStart = -1;

    public InputBar() {
        setLayout(new BorderLayout());

        textArea = new JTextArea(2, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        Fonts.apply(textArea, Font.PLAIN, Fonts.SIZE_BODY_LARGE);
        textArea.putClientProperty("JTextField.placeholderText", "Message, / for skills, ⇧↵ for newline");
        textArea.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0;background:null");
        textArea.putClientProperty("JComponent.outline", null);
        textArea.setTransferHandler(new FileDropTransferHandler());
        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (handleSlashPopupKey(e)) {
                    return;
                }

                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (sendOnEnter && !e.isShiftDown()) {
                        e.consume();
                        fireSend();
                    } else if (!sendOnEnter && (e.isControlDown() || e.isMetaDown())) {
                        e.consume();
                        fireSend();
                    }
                }
            }
        });

        textArea.setComponentPopupMenu(createEditContextMenu(textArea));

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                onInputChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                onInputChanged();
            }

            public void changedUpdate(DocumentEvent e) {
                onInputChanged();
            }
        });

        scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0");
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getViewport().setBorder(null);

        chipsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 0));
        chipsPanel.setOpaque(false);
        chipsPanel.setVisible(false);

        attachButton = new JButton();
        attachButton.putClientProperty("JButton.buttonType", "toolBarButton");
        attachButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:10");
        attachButton.setIcon(attachIcon(UIManager.getColor("Label.foreground")));
        attachButton.setFocusable(false);
        attachButton.setRolloverEnabled(true);
        attachButton.setToolTipText("Attach files/images");
        attachButton.setMargin(new Insets(0, 0, 0, 0));
        attachButton.setPreferredSize(new Dimension(24, 24));
        attachButton.setMinimumSize(new Dimension(24, 24));
        attachButton.addActionListener(e -> openAttachmentPicker());

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        actionsPanel.setOpaque(false);
        actionsPanel.add(attachButton);

        validationLabel = new JLabel();
        Fonts.apply(validationLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        validationLabel.setVisible(false);

        JPanel composerBottomPanel = new JPanel(new BorderLayout(0, 4));
        composerBottomPanel.setOpaque(false);
        composerBottomPanel.add(actionsPanel, BorderLayout.WEST);
        composerBottomPanel.add(validationLabel, BorderLayout.CENTER);

        composerShell = new ComposerShellPanel();
        composerShell.setLayout(new BorderLayout(0, 6));
        composerShell.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        composerShell.setTransferHandler(new FileDropTransferHandler());
        composerShell.add(chipsPanel, BorderLayout.NORTH);
        composerShell.add(scrollPane, BorderLayout.CENTER);
        composerShell.add(composerBottomPanel, BorderLayout.SOUTH);

        generationIndicatorPanel = new JPanel(new BorderLayout());
        generationIndicatorPanel.setVisible(false);

        generationInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        generationProgressBar = new JProgressBar();
        generationProgressBar.setIndeterminate(true);
        generationProgressBar.setPreferredSize(new Dimension(64, 8));
        generationProgressBar.setBorderPainted(false);
        generationInfoPanel.add(generationProgressBar);

        generationLabel = new JLabel("Generating response…");
        Fonts.apply(generationLabel, Font.PLAIN, Fonts.SIZE_COMPACT);
        generationInfoPanel.add(generationLabel);

        jumpToLatestButton = new JButton("Jump to latest");
        jumpToLatestButton.putClientProperty("JButton.buttonType", "borderless");
        Fonts.apply(jumpToLatestButton, Font.PLAIN, Fonts.SIZE_COMPACT);
        jumpToLatestButton.setFocusable(false);
        jumpToLatestButton.addActionListener(e -> fireJumpToLatest());
        generationInfoPanel.add(jumpToLatestButton);

        cancelGenerationButton = new JButton();
        cancelGenerationButton.putClientProperty("JButton.buttonType", "toolBarButton");
        cancelGenerationButton.putClientProperty(
                FlatClientProperties.STYLE,
                "focusWidth:0;innerFocusWidth:0;arc:10"
        );
        cancelGenerationButton.setFocusable(false);
        cancelGenerationButton.setToolTipText("Stop generation");
        cancelGenerationButton.setPreferredSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setMinimumSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setMaximumSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setHorizontalAlignment(SwingConstants.CENTER);
        cancelGenerationButton.setVerticalAlignment(SwingConstants.CENTER);
        cancelGenerationButton.setMargin(new Insets(0, 0, 0, 0));
        cancelGenerationButton.setVisible(false);
        cancelGenerationButton.addActionListener(e -> fireCancelGeneration());

        JPanel cancelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        cancelPanel.setOpaque(false);
        cancelPanel.add(cancelGenerationButton);
        composerBottomPanel.add(cancelPanel, BorderLayout.EAST);

        generationIndicatorPanel.add(generationInfoPanel, BorderLayout.WEST);

        configureSlashPopup();
        refreshSkills();

        applyThemeStyles();
        add(generationIndicatorPanel, BorderLayout.NORTH);
        add(composerShell, BorderLayout.CENTER);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyThemeStyles();
        if (chipsPanel != null) {
            refreshChips();
        }
    }

    public String getText() {
        return textArea.getText().trim();
    }

    public ComposerState getComposerState() {
        return new ComposerState(textArea.getText(), attachments, activeSkills);
    }

    public ComposerState consumeComposerState() {
        ComposerState state = getComposerState();
        clear();
        return state;
    }

    public void clear() {
        textArea.setText("");
        attachments.clear();
        activeSkills.clear();
        refreshChips();
        hideSlashPopup();
        clearValidationMessage();
    }

    public void showValidationMessage(String message) {
        if (StringUtils.isBlank(message)) {
            clearValidationMessage();
            return;
        }

        validationLabel.setText(message);
        validationLabel.setVisible(true);
        revalidate();
        repaint();
    }

    public void clearValidationMessage() {
        validationLabel.setText("");
        validationLabel.setVisible(false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        textArea.setEnabled(enabled);
        attachButton.setEnabled(enabled);
    }

    public void addSendListener(ActionListener listener) {
        sendListeners.add(listener);
    }

    public void setSendOnEnter(boolean sendOnEnter) {
        this.sendOnEnter = sendOnEnter;
    }

    public void addJumpToLatestListener(ActionListener listener) {
        jumpToLatestListeners.add(listener);
    }

    public void addCancelGenerationListener(ActionListener listener) {
        cancelGenerationListeners.add(listener);
    }

    public void requestInputFocus() {
        textArea.requestFocusInWindow();
    }

    public void setGenerationIndicatorVisible(boolean visible) {
        Runnable update = () -> {
            generationIndicatorPanel.setVisible(visible);
            generationProgressBar.setIndeterminate(visible);
            cancelGenerationButton.setVisible(visible);
            revalidate();
            repaint();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    public boolean isGenerationIndicatorVisible() {
        return generationIndicatorPanel.isVisible();
    }

    public void setGenerationStatusText(String text) {
        Runnable update = () -> generationLabel.setText(StringUtils.defaultIfBlank(text, "Generating response…"));

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void fireSend() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "send");
        for (ActionListener l : sendListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireJumpToLatest() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "jumpToLatest");
        for (ActionListener l : jumpToLatestListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireCancelGeneration() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "cancelGeneration");
        for (ActionListener l : cancelGenerationListeners) {
            l.actionPerformed(event);
        }
    }

    private void onInputChanged() {
        adjustHeight();
        clearValidationMessage();
        updateSlashSuggestions();
    }

    private void adjustHeight() {
        int lineCount = Math.min(textArea.getLineCount(), 5);
        textArea.setRows(Math.max(2, lineCount));
        revalidate();
    }

    private void openAttachmentPicker() {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Attach files");
        chooser.setMultiSelectionEnabled(true);
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Allowed files",
                attachmentSelectionPolicy.allowedExtensions().toArray(String[]::new)
        ));

        int result = chooser.showOpenDialog(this);
        if (result != SystemFileChooser.APPROVE_OPTION) {
            return;
        }

        File[] selectedFiles = chooser.getSelectedFiles();
        List<Path> selectedPaths = new ArrayList<>();
        if (selectedFiles != null && selectedFiles.length > 0) {
            for (File selectedFile : selectedFiles) {
                selectedPaths.add(selectedFile.toPath());
            }
        } else if (chooser.getSelectedFile() != null) {
            selectedPaths.add(chooser.getSelectedFile().toPath());
        }

        addAttachments(selectedPaths);
    }

    private void addAttachments(List<Path> candidatePaths) {
        clearValidationMessage();

        candidatePaths.stream()
                .map(this::toComposerAttachment)
                .flatMap(Optional::stream)
                .filter(this::isNewAttachment)
                .forEach(attachments::add);

        refreshChips();
    }

    private Optional<ComposerAttachment> toComposerAttachment(Path path) {
        try {
            return Optional.of(attachmentSelectionPolicy.create(path));
        } catch (IllegalArgumentException e) {
            showValidationMessage(e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            showValidationMessage("Failed to read attachment: %s".formatted(path.getFileName()));
            return Optional.empty();
        }
    }

    private boolean isNewAttachment(ComposerAttachment candidate) {
        return attachments.stream().noneMatch(existing -> existing.path().equals(candidate.path()));
    }

    private void refreshChips() {
        chipsPanel.removeAll();

        Icon attachmentIcon = new FlatFileViewFileIcon();

        new ArrayList<>(attachments).forEach(attachment -> {
            Runnable onRemove = () -> {
                attachments.remove(attachment);
                refreshChips();
            };
            if (attachment.image()) {
                chipsPanel.add(createImageAttachmentChip(attachment, onRemove));
            } else {
                chipsPanel.add(createAttachmentChip(
                        ellipsize(attachment.displayName(), 26),
                        attachmentIcon,
                        onRemove
                ));
            }
        });

        new ArrayList<>(activeSkills).forEach(skill -> chipsPanel.add(createSkillChip(
                "/%s".formatted(ellipsize(skill, 20)),
                () -> {
                    activeSkills.remove(skill);
                    refreshChips();
                }
        )));

        chipsPanel.setVisible(!attachments.isEmpty() || !activeSkills.isEmpty());
        chipsPanel.revalidate();
        composerShell.revalidate();
        revalidate();
        chipsPanel.repaint();
    }

    private JComponent createAttachmentChip(String text, Icon leadingIcon, Runnable onRemove) {
        ChipPanel chip = new ChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 3));

        JLabel label = new JLabel(text, leadingIcon, SwingConstants.LEADING);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_COMPACT);
        label.setForeground(UIManager.getColor("Label.foreground"));
        label.setIconTextGap(4);
        chip.add(label);

        JButton remove = createChipRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                chip.setHovered(false);
            }
        });

        return chip;
    }

    private JComponent createImageAttachmentChip(ComposerAttachment attachment, Runnable onRemove) {
        ChipPanel chip = new ChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        RoundedImageIcon thumbnail = new RoundedImageIcon(attachment.path(), 36);
        JLabel thumbLabel;
        if (thumbnail.hasImage()) {
            thumbLabel = new JLabel(thumbnail);
        } else {
            thumbLabel = new JLabel(new FlatFileViewFileIcon());
        }
        chip.add(thumbLabel);

        JPanel textStack = new JPanel();
        textStack.setOpaque(false);
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));

        JLabel nameLabel = new JLabel(ellipsize(attachment.displayName(), 22));
        Fonts.apply(nameLabel, Font.PLAIN, Fonts.SIZE_COMPACT);
        nameLabel.setForeground(UIManager.getColor("Label.foreground"));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textStack.add(nameLabel);

        JLabel sizeLabel = new JLabel(formatSize(attachment.sizeBytes()));
        Fonts.apply(sizeLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled == null) {
            disabled = UIManager.getColor("Label.foreground");
        }
        sizeLabel.setForeground(disabled);
        sizeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textStack.add(sizeLabel);

        chip.add(textStack);

        JButton remove = createChipRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                chip.setHovered(false);
            }
        });

        chip.setToolTipText(attachment.displayName());
        return chip;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1_000) {
            return "%d B".formatted(bytes);
        }
        if (bytes < 1_000_000) {
            return "%.1f kB".formatted(bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000) {
            return "%.1f MB".formatted(bytes / 1_000_000.0);
        }
        return "%.1f GB".formatted(bytes / 1_000_000_000.0);
    }

    private JComponent createSkillChip(String text, Runnable onRemove) {
        SkillChipPanel chip = new SkillChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 3));

        JLabel label = new JLabel(text);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_COMPACT);
        label.setForeground(resolveSkillTextColor(resolveSkillChipBackground(false)));
        chip.add(label);

        JButton remove = createChipRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                chip.setHovered(false);
            }
        });

        return chip;
    }

    private JButton createChipRemoveButton(Runnable onRemove) {
        JButton remove = new JButton();
        remove.putClientProperty("JButton.buttonType", "toolBarButton");
        remove.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:8");
        remove.setIcon(chipIcon("/icons/sidebar/trash.svg", new Color(190, 86, 86)));
        remove.setToolTipText("Remove");
        remove.setMargin(new Insets(0, 0, 0, 0));
        remove.setFocusable(false);
        remove.setPreferredSize(new Dimension(18, 18));
        remove.setMinimumSize(new Dimension(18, 18));
        remove.addActionListener(e -> onRemove.run());
        return remove;
    }

    private String ellipsize(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }

        return "%s…".formatted(trimmed.substring(0, Math.max(0, maxLength - 1)));
    }

    private JPopupMenu createEditContextMenu(JTextComponent target) {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenuItem cutItem = buildEditMenuItem(
                target,
                "Cut",
                DefaultEditorKit.cutAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcut),
                "/icons/input/scissors.svg"
        );
        JMenuItem copyItem = buildEditMenuItem(
                target,
                "Copy",
                DefaultEditorKit.copyAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut),
                "/icons/input/copy.svg"
        );
        JMenuItem pasteItem = buildEditMenuItem(
                target,
                "Paste",
                DefaultEditorKit.pasteAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcut),
                "/icons/input/clipboard-paste.svg"
        );

        JPopupMenu popup = new JPopupMenu();
        popup.add(cutItem);
        popup.add(copyItem);
        popup.add(pasteItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = target.getSelectionStart() != target.getSelectionEnd();
                boolean editable = target.isEditable() && target.isEnabled();
                cutItem.setEnabled(editable && hasSelection);
                copyItem.setEnabled(hasSelection);
                pasteItem.setEnabled(editable && clipboardHasText());
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        return popup;
    }

    private JMenuItem buildEditMenuItem(
            JTextComponent target,
            String label,
            String actionName,
            KeyStroke accelerator,
            String iconPath
    ) {
        Action action = target.getActionMap().get(actionName);
        JMenuItem item = new JMenuItem(label);
        Fonts.apply(item, Font.PLAIN, Fonts.SIZE_BODY);
        item.setIcon(svgIcon(iconPath, MENU_ICON_SIZE, UIManager.getColor("Label.foreground")));
        item.setAccelerator(accelerator);
        if (action != null) {
            item.addActionListener(e -> {
                ActionEvent forwarded = new ActionEvent(target, ActionEvent.ACTION_PERFORMED, actionName);
                action.actionPerformed(forwarded);
            });
        }
        return item;
    }

    private boolean clipboardHasText() {
        try {
            return Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void configureSlashPopup() {
        slashSuggestionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Fonts.apply(slashSuggestionsList, Font.PLAIN, Fonts.SIZE_BODY);
        slashSuggestionsList.setVisibleRowCount(8);
        slashSuggestionsList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JPanel row = new JPanel(new BorderLayout(0, 2));
            row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

            JLabel title = new JLabel("/%s".formatted(value.name()));
            Fonts.apply(title, Font.PLAIN, Fonts.SIZE_BODY);
            row.add(title, BorderLayout.NORTH);

            JLabel desc = new JLabel(value.description());
            Fonts.apply(desc, Font.PLAIN, Fonts.SIZE_SMALL);
            desc.setForeground(UIManager.getColor("Label.disabledForeground"));
            row.add(desc, BorderLayout.SOUTH);

            if (isSelected) {
                row.setBackground(list.getSelectionBackground());
                title.setForeground(list.getSelectionForeground());
                desc.setForeground(list.getSelectionForeground());
            } else {
                row.setBackground(list.getBackground());
                title.setForeground(list.getForeground());
            }

            row.setOpaque(true);
            return row;
        });

        slashSuggestionsList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    applySelectedSlashSkill();
                }
            }
        });

        slashSuggestionsList.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectSkill");
        slashSuggestionsList.getActionMap().put("selectSkill", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                applySelectedSlashSkill();
            }
        });

        JScrollPane scrollPane = new JScrollPane(slashSuggestionsList);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(320, 220));
        slashPopup.add(scrollPane);
    }

    private void refreshSkills() {
        Map<String, SkillCommand> byName = new LinkedHashMap<>();
        skillSearchPaths().forEach(path -> loadSkills(path).forEach(skill -> byName.putIfAbsent(skill.name(), skill)));
        availableSkills.clear();
        availableSkills.addAll(byName.values());
    }

    private List<Path> skillSearchPaths() {
        List<Path> paths = new ArrayList<>();
        String userDir = System.getProperty("user.dir");
        if (StringUtils.isNotBlank(userDir)) {
            paths.add(Path.of(userDir, ".agents", "skills"));
        }

        String userHome = System.getProperty("user.home");
        if (StringUtils.isNotBlank(userHome)) {
            paths.add(Path.of(userHome, ".agents", "skills"));
        }

        return paths;
    }

    private List<SkillCommand> loadSkills(Path skillRoot) {
        if (!Files.isDirectory(skillRoot)) {
            return emptyList();
        }

        List<SkillCommand> skills = new ArrayList<>();
        try (var stream = Files.list(skillRoot)) {
            stream.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .map(this::parseSkillFile)
                    .flatMap(Optional::stream)
                    .forEach(skills::add);
        } catch (IOException e) {
            return emptyList();
        }

        return skills;
    }

    private Optional<SkillCommand> parseSkillFile(Path file) {
        String fallbackName = file.getParent() == null ? "skill" : file.getParent().getFileName().toString();
        String name = fallbackName;
        String description = "";

        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("name:")) {
                    name = trimmed.substring("name:".length()).trim();
                } else if (trimmed.startsWith("description:")) {
                    description = trimmed.substring("description:".length()).trim();
                }

                if (!description.isBlank() && !name.isBlank()) {
                    break;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        String finalDescription = description.isBlank() ? "No description" : description;
        return Optional.of(new SkillCommand(name, finalDescription));
    }

    private void updateSlashSuggestions() {
        SlashToken slashToken = detectSlashToken();
        if (slashToken == null) {
            hideSlashPopup();
            return;
        }

        List<SkillCommand> matches = availableSkills.stream()
                .filter(skill -> slashToken.query().isBlank()
                        || skill.name().toLowerCase().contains(slashToken.query().toLowerCase())
                        || skill.description().toLowerCase().contains(slashToken.query().toLowerCase()))
                .limit(20)
                .toList();

        if (matches.isEmpty()) {
            hideSlashPopup();
            return;
        }

        slashSuggestions.clear();
        matches.forEach(slashSuggestions::addElement);
        slashSuggestionsList.setSelectedIndex(0);
        slashTokenStart = slashToken.startIndex();
        showSlashPopup();
    }

    private SlashToken detectSlashToken() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        if (caret <= 0 || caret > text.length()) {
            return null;
        }

        int tokenStart = caret - 1;
        while (tokenStart >= 0 && !Character.isWhitespace(text.charAt(tokenStart))) {
            tokenStart--;
        }
        tokenStart += 1;

        String token = text.substring(tokenStart, caret);
        if (!token.startsWith("/")) {
            return null;
        }

        return new SlashToken(tokenStart, token.substring(1));
    }

    private void showSlashPopup() {
        if (slashSuggestions.isEmpty()) {
            return;
        }

        int popupWidth = Math.max(240, (int) (getWidth() * 0.8));
        Dimension popupPref = slashPopup.getPreferredSize();
        slashPopup.setPreferredSize(new Dimension(popupWidth, popupPref.height));

        try {
            Point anchorInInputBar = new Point((getWidth() - popupWidth) / 2, 0);
            Point anchorInTextArea = SwingUtilities.convertPoint(this, anchorInInputBar, textArea);
            int y = Math.max(6, anchorInTextArea.y - slashPopup.getPreferredSize().height - 8);
            slashPopup.show(textArea, anchorInTextArea.x, y);
        } catch (Exception e) {
            slashPopup.show(textArea, 6, 6);
        }
    }

    private void hideSlashPopup() {
        slashTokenStart = -1;
        slashPopup.setVisible(false);
    }

    private boolean handleSlashPopupKey(KeyEvent e) {
        if (!slashPopup.isVisible()) {
            return false;
        }

        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> {
                e.consume();
                int index = Math.max(0, slashSuggestionsList.getSelectedIndex() - 1);
                slashSuggestionsList.setSelectedIndex(index);
                slashSuggestionsList.ensureIndexIsVisible(index);
                return true;
            }
            case KeyEvent.VK_DOWN -> {
                e.consume();
                int index = Math.min(slashSuggestions.size() - 1, slashSuggestionsList.getSelectedIndex() + 1);
                slashSuggestionsList.setSelectedIndex(index);
                slashSuggestionsList.ensureIndexIsVisible(index);
                return true;
            }
            case KeyEvent.VK_ENTER -> {
                e.consume();
                applySelectedSlashSkill();
                return true;
            }
            case KeyEvent.VK_ESCAPE -> {
                e.consume();
                hideSlashPopup();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void applySelectedSlashSkill() {
        SkillCommand selected = slashSuggestionsList.getSelectedValue();
        if (selected == null) {
            hideSlashPopup();
            return;
        }

        removeSlashTokenFromText();
        if (activeSkills.stream().noneMatch(skill -> skill.equalsIgnoreCase(selected.name()))) {
            activeSkills.add(selected.name());
            refreshChips();
        }
        hideSlashPopup();
    }

    private void removeSlashTokenFromText() {
        int caret = textArea.getCaretPosition();
        if (slashTokenStart < 0 || slashTokenStart > caret) {
            return;
        }

        String current = textArea.getText();
        String before = current.substring(0, slashTokenStart);
        String after = current.substring(caret);

        boolean needsSeparator = !before.isEmpty()
                && !Character.isWhitespace(before.charAt(before.length() - 1))
                && !after.isEmpty()
                && !Character.isWhitespace(after.charAt(0));
        String separator = needsSeparator ? " " : "";

        String updated = before + separator + after;
        textArea.setText(updated);
        textArea.setCaretPosition(Math.min(before.length() + separator.length(), updated.length()));
    }

    private void applyThemeStyles() {
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")
                ),
                BorderFactory.createEmptyBorder(8, 16, 12, 16)
        ));

        if (scrollPane != null) {
            scrollPane.setBorder(null);
            scrollPane.setViewportBorder(null);
            scrollPane.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0");
            if (scrollPane.getViewport() != null) {
                scrollPane.getViewport().setBorder(null);
                scrollPane.getViewport().setOpaque(false);
            }
        }

        if (textArea != null) {
            Color textColor = UIManager.getColor("TextArea.foreground");
            if (textColor == null) {
                textColor = UIManager.getColor("Label.foreground");
            }
            textArea.setForeground(textColor);
            textArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            textArea.setMargin(new Insets(0, 0, 0, 0));
            textArea.setOpaque(false);
            textArea.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0;background:null");
            textArea.putClientProperty("JComponent.outline", null);
        }

        if (generationIndicatorPanel != null) {
            generationIndicatorPanel.setOpaque(false);
        }

        if (generationInfoPanel != null) {
            generationInfoPanel.setOpaque(false);
        }

        if (generationLabel != null) {
            generationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        }

        Color linkColor = UIManager.getColor("Component.linkColor");
        if (linkColor == null) {
            linkColor = UIManager.getColor("Component.accentColor");
        }
        if (linkColor == null) {
            linkColor = UIManager.getColor("Label.foreground");
        }

        if (jumpToLatestButton != null) {
            jumpToLatestButton.setForeground(linkColor);
        }

        Color attachColor = UIManager.getColor("Label.foreground");
        if (attachColor == null) {
            attachColor = new Color(120, 120, 120);
        }

        if (attachButton != null) {
            attachButton.setForeground(attachColor);
            attachButton.setIcon(attachIcon(attachColor));
        }

        if (cancelGenerationButton != null) {
            cancelGenerationButton.setForeground(attachColor);
            cancelGenerationButton.setIcon(stopIcon(attachColor));
        }

        if (validationLabel != null) {
            Color errorColor = UIManager.getColor("Component.error.focusedBorderColor");
            if (errorColor == null) {
                errorColor = new Color(190, 58, 58);
            }
            validationLabel.setForeground(errorColor);
        }
    }

    private static class WrapLayout extends FlowLayout {

        private WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= getHgap() + 1;
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth <= 0) {
                    targetWidth = Integer.MAX_VALUE;
                }

                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + (getHgap() * 2);
                int maxWidth = targetWidth - horizontalInsetsAndGap;

                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                int componentCount = target.getComponentCount();
                for (int i = 0; i < componentCount; i++) {
                    Component component = target.getComponent(i);
                    if (!component.isVisible()) {
                        continue;
                    }

                    Dimension d = preferred ? component.getPreferredSize() : component.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }

                    if (rowWidth != 0) {
                        rowWidth += getHgap();
                    }

                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }

                addRow(dim, rowWidth, rowHeight);

                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + (getVgap() * 2);

                Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
                if (scrollPane != null && target.isValid()) {
                    dim.width -= getHgap() + 1;
                }

                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
    }

    private class ChipPanel extends JPanel {

        private static final int ARC = 10;
        private boolean hovered;

        private ChipPanel() {
            setOpaque(false);
        }

        private void setHovered(boolean hovered) {
            if (this.hovered == hovered) {
                return;
            }
            this.hovered = hovered;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color background = resolveChipBackground();
            if (hovered) {
                background = adjustBrightness(background, CHIP_HOVER_BG_DELTA);
            }

            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color background = resolveChipBackground();
            if (hovered) {
                background = adjustBrightness(background, CHIP_HOVER_BG_DELTA);
            }

            Color border = resolveChipBorderColor(background);
            if (hovered) {
                border = adjustBrightness(border, CHIP_HOVER_BORDER_DELTA);
            }

            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
        }
    }

    private class SkillChipPanel extends JPanel {

        private static final int ARC = 14;
        private boolean hovered;

        private SkillChipPanel() {
            setOpaque(false);
        }

        private void setHovered(boolean hovered) {
            if (this.hovered == hovered) {
                return;
            }
            this.hovered = hovered;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color fill = resolveSkillChipBackground(hovered);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color border = resolveSkillChipBorder(hovered);
            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
        }
    }

    private Color resolveSkillTextColor(Color chipBackground) {
        Color fallback = UIManager.getColor("Label.foreground");
        if (fallback == null) {
            fallback = new Color(230, 230, 230);
        }

        Color accent = resolveAccentColor();
        Color darkAccentText = blendColors(accent, Color.BLACK, 0.58f);
        Color lightAccentText = blendColors(accent, Color.WHITE, 0.70f);

        Color best = fallback;
        double bestContrast = contrastRatio(best, chipBackground);

        double darkContrast = contrastRatio(darkAccentText, chipBackground);
        if (darkContrast > bestContrast) {
            best = darkAccentText;
            bestContrast = darkContrast;
        }

        double lightContrast = contrastRatio(lightAccentText, chipBackground);
        if (lightContrast > bestContrast) {
            best = lightAccentText;
            bestContrast = lightContrast;
        }

        if (bestContrast < 3.4d) {
            Color black = Color.BLACK;
            Color white = Color.WHITE;
            best = contrastRatio(black, chipBackground) > contrastRatio(white, chipBackground) ? black : white;
        }

        return best;
    }

    private Color resolveSkillChipBackground(boolean hovered) {
        Color base = resolveChipBackground();
        Color accent = resolveAccentColor();
        float ratio = hovered ? 0.28f : 0.22f;
        return blendColors(base, accent, ratio);
    }

    private Color resolveSkillChipBorder(boolean hovered) {
        Color background = resolveSkillChipBackground(hovered);
        Color border = resolveChipBorderColor(background);
        if (colorDistance(background, border) < 20) {
            border = adjustBrightness(background, 0.18f);
        }
        return border;
    }

    private Color resolveAccentColor() {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) {
            accent = UIManager.getColor("Component.linkColor");
        }
        if (accent == null) {
            accent = new Color(120, 175, 255);
        }
        return accent;
    }

    private Color blendColors(Color base, Color overlay, float ratio) {
        float clamped = Math.max(0f, Math.min(1f, ratio));
        int red = Math.round(base.getRed() * (1f - clamped) + overlay.getRed() * clamped);
        int green = Math.round(base.getGreen() * (1f - clamped) + overlay.getGreen() * clamped);
        int blue = Math.round(base.getBlue() * (1f - clamped) + overlay.getBlue() * clamped);
        return new Color(red, green, blue);
    }

    private Color resolveChipBackground() {
        Color background = UIManager.getColor("TextField.background");
        if (background == null) {
            background = UIManager.getColor("Panel.background");
        }
        if (background == null) {
            background = getBackground();
        }
        return background == null ? new Color(90, 90, 90) : background;
    }

    private Color resolveChipBorderColor(Color background) {
        Color border = UIManager.getColor("TextField.borderColor");
        if (border == null) {
            border = UIManager.getColor("Component.borderColor");
        }
        if (border == null) {
            border = UIManager.getColor("Separator.foreground");
        }
        if (border == null) {
            border = new Color(150, 150, 150);
        }

        if (colorDistance(background, border) < 24) {
            border = adjustBrightness(background, 0.22f);
        }

        return border;
    }

    private int colorDistance(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed())
                + Math.abs(c1.getGreen() - c2.getGreen())
                + Math.abs(c1.getBlue() - c2.getBlue());
    }

    private double contrastRatio(Color c1, Color c2) {
        double l1 = relativeLuminance(c1);
        double l2 = relativeLuminance(c2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private double relativeLuminance(Color color) {
        double red = linearized(color.getRed() / 255.0d);
        double green = linearized(color.getGreen() / 255.0d);
        double blue = linearized(color.getBlue() / 255.0d);
        return 0.2126d * red + 0.7152d * green + 0.0722d * blue;
    }

    private double linearized(double channel) {
        return channel <= 0.03928d ? channel / 12.92d : Math.pow((channel + 0.055d) / 1.055d, 2.4d);
    }

    private Color adjustBrightness(Color color, float amount) {
        if (color == null) {
            return null;
        }

        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float brightness = Math.max(0f, Math.min(1f, hsb[2] + (hsb[2] < 0.5f ? amount : -amount / 2f)));
        float saturation = Math.max(0f, Math.min(1f, hsb[1] + (hsb[2] < 0.5f ? -amount / 2f : amount / 3f)));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private class ComposerShellPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color background = UIManager.getColor("TextArea.background");
            if (background == null) {
                background = UIManager.getColor("Panel.background");
            }
            if (background == null) {
                background = getBackground();
            }

            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, SHELL_ARC, SHELL_ARC);
            g2.dispose();

            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color border = UIManager.getColor("Component.borderColor");
            if (border == null) {
                border = UIManager.getColor("Separator.foreground");
            }
            if (border == null) {
                border = new Color(180, 180, 180);
            }

            g2.setColor(border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, SHELL_ARC, SHELL_ARC);
            g2.dispose();
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    private Icon chipIcon(String path, Color tint) {
        return svgIcon(path, CHIP_ICON_SIZE, tint);
    }

    private Icon attachIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/paperclip.svg", ATTACH_ICON_SIZE, tint);
        return icon != null ? icon : new FlatFileViewFileIcon();
    }

    private Icon stopIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/stop-circle.svg", STOP_ICON_SIZE, tint);
        if (icon != null) {
            return icon;
        }

        Icon fallback = svgIcon("/icons/input/stop.svg", STOP_ICON_SIZE, tint);
        return fallback != null ? fallback : UIManager.getIcon("OptionPane.errorIcon");
    }

    private Icon svgIcon(String path, int size, Color tint) {
        String tintKey = tint == null ? "default" : String.valueOf(tint.getRGB());
        String key = "%s#%d#%s".formatted(path, size, tintKey);
        return CHIP_ICON_CACHE.computeIfAbsent(key, ignored -> {
            URL iconUrl = InputBar.class.getResource(path);
            if (iconUrl == null) {
                return null;
            }

            FlatSVGIcon svgIcon = new FlatSVGIcon(iconUrl).derive(size, size);
            Color resolvedTint = tint != null ? tint : UIManager.getColor("Label.foreground");
            if (resolvedTint == null) {
                resolvedTint = new Color(90, 90, 90);
            }

            Color finalResolvedTint = resolvedTint;
            svgIcon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                    finalResolvedTint.getRed(),
                    finalResolvedTint.getGreen(),
                    finalResolvedTint.getBlue(),
                    color.getAlpha()
            )));
            return svgIcon.hasFound() ? svgIcon : null;
        });
    }

    private class FileDropTransferHandler extends TransferHandler {

        @Override
        public int getSourceActions(JComponent c) {
            return c instanceof JTextComponent ? COPY_OR_MOVE : NONE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            if (c instanceof JTextComponent textComponent) {
                String selected = textComponent.getSelectedText();
                if (selected != null && !selected.isEmpty()) {
                    return new java.awt.datatransfer.StringSelection(selected);
                }
            }
            return null;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            if (action == MOVE && source instanceof JTextComponent textComponent) {
                textComponent.replaceSelection("");
            }
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return true;
            }
            return support.getComponent() instanceof JTextComponent
                    && support.isDataFlavorSupported(DataFlavor.stringFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    List<java.io.File> files = (List<java.io.File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = files.stream().map(java.io.File::toPath).toList();
                    addAttachments(paths);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            if (support.getComponent() instanceof JTextComponent textComponent
                    && support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    String text = (String) support.getTransferable()
                            .getTransferData(DataFlavor.stringFlavor);
                    textComponent.replaceSelection(text);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            return false;
        }
    }

    private record SkillCommand(String name, String description) {
    }

    private record SlashToken(int startIndex, String query) {
    }
}
