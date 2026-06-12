package com.github.drafael.chat4j.sidebar;

import com.formdev.flatlaf.ui.FlatLineBorder;
import com.github.drafael.chat4j.storage.ConversationRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class SidebarPanelTest {

    @Test
    @DisplayName("Sidebar starts wider and trims rows at the viewport edge")
    void constructor_whenCreated_usesWiderDefaultWidthAndViewportWidthTracking() throws Exception {
        var repo = new DelayedConversationRepo(0, emptyMap());
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));

        SidebarPanel subject = panelRef.get();
        assertThat(subject.getPreferredSize()).isEqualTo(new Dimension(300, 0));
        assertThat(readConversationList(subject).getScrollableTracksViewportWidth()).isTrue();
    }

    @Test
    @DisplayName("Sidebar refresh does not block EDT while repository call is in flight")
    void refresh_whenRepositoryIsSlow_doesNotBlockEdt() throws Exception {
        SwingUtilities.invokeAndWait(JPanel::new);
        Thread.startVirtualThread(() -> {}).join();

        var repo = new DelayedConversationRepo(350, grouped("Today", "Slow conversation"));
        var panelRef = new AtomicReference<SidebarPanel>();

        long started = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(325);

        SidebarPanel subject = panelRef.get();
        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Slow conversation"));
    }

    @Test
    @DisplayName("Latest refresh result wins when older refresh completes later")
    void refresh_whenRequestsCompleteOutOfOrder_keepsLatestListContents() throws Exception {
        var repo = new SequencedConversationRepo();
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Initial conversation"));

        SwingUtilities.invokeAndWait(subject::refresh);
        repo.awaitSecondCallStarted();
        SwingUtilities.invokeAndWait(subject::refresh);

        awaitCondition(5, TimeUnit.SECONDS, () -> {
            List<String> titles = conversationTitles(subject);
            return titles.contains("Latest conversation") && !titles.contains("Older conversation");
        });
    }

    @Test
    @DisplayName("Repository date group headers render in repository order")
    void refresh_whenDateGroupsExist_rendersDateGroupHeaders() throws Exception {
        UUID todayId = UUID.randomUUID();
        UUID yesterdayId = UUID.randomUUID();
        Map<String, List<ConversationRepo.ConversationRecord>> grouped = new LinkedHashMap<>();
        grouped.put("Today", List.of(conversation(todayId, "Today chat")));
        grouped.put("Yesterday", List.of(conversation(yesterdayId, "Yesterday chat")));
        var repo = new DelayedConversationRepo(0, grouped);
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Today chat"));

        DefaultListModel<?> model = readListModel(subject);
        assertThat(groupHeaderName(model.get(0))).isEqualTo("Today");
        assertThat(((ConversationItem) model.get(1)).title()).isEqualTo("Today chat");
        assertThat(groupHeaderName(model.get(2))).isEqualTo("Yesterday");
        assertThat(((ConversationItem) model.get(3)).title()).isEqualTo("Yesterday chat");
    }

    @Test
    @DisplayName("Selected conversation row is inset from sidebar edges")
    void renderer_whenConversationSelected_usesInsetSelectionPadding() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DelayedConversationRepo(0, grouped("Today", conversation(conversationId, "Selected chat")));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Selected chat"));

        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(subject);
            JList<?> list = readConversationList(subject);
            int conversationIndex = IntStream.range(0, model.size())
                    .filter(index -> model.get(index) instanceof ConversationItem)
                    .findFirst()
                    .orElseThrow();
            list.setSelectedIndex(conversationIndex);
            @SuppressWarnings({"rawtypes", "unchecked"})
            JLabel label = (JLabel) ((JList) list).getCellRenderer().getListCellRendererComponent(
                    (JList) list,
                    model.get(conversationIndex),
                    conversationIndex,
                    true,
                    false
            );
            assertThat(label.getBorder()).isInstanceOf(EmptyBorder.class);
            assertThat(label.getBorder().getBorderInsets(label)).isEqualTo(new Insets(6, 14, 6, 10));
            assertThat(list.getClientProperty("FlatLaf.style")).isNull();
        });
    }

    @Test
    @DisplayName("Selection handler is notified when installed after an existing selection")
    void setOnConversationSelected_whenRowAlreadySelected_notifiesHandler() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DelayedConversationRepo(0, grouped("Today", conversation(conversationId, "Selected chat")));
        var panelRef = new AtomicReference<SidebarPanel>();
        var selectedId = new AtomicReference<UUID>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();
        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Selected chat"));

        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(subject);
            JList<?> list = readConversationList(subject);
            int conversationIndex = IntStream.range(0, model.size())
                    .filter(index -> model.get(index) instanceof ConversationItem)
                    .findFirst()
                    .orElseThrow();
            list.setSelectedIndex(conversationIndex);
            subject.setOnConversationSelected(selectedId::set);
        });

        assertThat(selectedId.get()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Filter field narrows conversation list locally")
    void filterField_whenTextEntered_filtersConversationRows() throws Exception {
        var repo = new DelayedConversationRepo(0, grouped(
                "Today",
                conversation(UUID.randomUUID(), "Alpha plan"),
                conversation(UUID.randomUUID(), "Beta notes")
        ));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Alpha plan"));
        JTextField filterField = findTextField(subject);
        assertThat(filterField).isNotNull();
        assertThat(filterField.getClientProperty("JTextField.leadingIcon")).isNotNull();
        assertThat(filterField.getClientProperty("JComponent.roundRect")).isNull();
        assertThat(filterField.getClientProperty("JTextField.showClearButton")).isEqualTo(true);
        assertThat(filterField.getBackground()).isEqualTo(UIManager.getColor("TextField.background"));
        assertThat(filterField.getForeground()).isEqualTo(UIManager.getColor("TextField.foreground"));
        assertThat(filterField.getBorder()).isInstanceOf(FlatLineBorder.class);
        Insets borderInsets = filterField.getBorder().getBorderInsets(filterField);
        assertThat(borderInsets).isEqualTo(new Insets(4, 6, 4, 6));
        assertThat(((FlatLineBorder) filterField.getBorder()).getArc()).isEqualTo(10);

        SwingUtilities.invokeAndWait(() -> filterField.setText("beta"));

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).equals(List.of("Beta notes")));
    }

    @Test
    @DisplayName("Repository favorites group is not rendered twice")
    void refresh_whenRepositoryAlreadyGroupsFavorites_rendersSingleFavoritesSection() throws Exception {
        UUID favoriteId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        Map<String, List<ConversationRepo.ConversationRecord>> grouped = new LinkedHashMap<>();
        grouped.put("Favorites", List.of(conversation(favoriteId, "Favorite chat", true)));
        grouped.put("Today", List.of(conversation(regularId, "Regular chat")));
        var repo = new DelayedConversationRepo(0, grouped);
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Favorite chat"));

        DefaultListModel<?> model = readListModel(subject);
        List<String> headers = Collections.list(model.elements()).stream()
                .filter(entry -> entry.getClass().getSimpleName().equals("GroupHeader"))
                .map(entry -> {
                    try {
                        return groupHeaderName(entry);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();

        assertThat(headers).containsExactly("Favorites", "Today");
    }

    @Test
    @DisplayName("Favorite flags do not create synthetic sidebar groups")
    void refresh_whenFavoriteFlagExists_rendersRepositoryGroupsOnly() throws Exception {
        UUID favoriteId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        var repo = new DelayedConversationRepo(0, grouped(
                "Today",
                conversation(favoriteId, "Favorite chat", true),
                conversation(regularId, "Regular chat")
        ));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Favorite chat"));

        DefaultListModel<?> model = readListModel(subject);
        assertThat(groupHeaderName(model.get(0))).isEqualTo("Today");
        assertThat(((ConversationItem) model.get(1)).title()).isEqualTo("Favorite chat");
        assertThat(((ConversationItem) model.get(2)).title()).isEqualTo("Regular chat");
        assertThat(Collections.frequency(conversationTitles(subject), "Favorite chat")).isEqualTo(1);
    }

    @Test
    @DisplayName("Sidebar keeps filter and settings without workspace action buttons")
    void headerActionButtons_whenClicked_dispatchCallbacks() throws Exception {
        var repo = new DelayedConversationRepo(0, emptyMap());
        var panelRef = new AtomicReference<SidebarPanel>();
        var settingsInvoked = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            SidebarPanel panel = new SidebarPanel(repo);
            panel.setOnSettings(settingsInvoked::incrementAndGet);
            panelRef.set(panel);
        });

        JButton settingsButton = findButtonByTooltip(panelRef.get(), "Settings");
        assertThat(findButton(panelRef.get(), "Settings")).isNull();
        assertThat(findButton(panelRef.get(), "New chat")).isNull();
        assertThat(findButton(panelRef.get(), "Search")).isNull();
        assertThat(findTextField(panelRef.get())).isNotNull();
        assertThat(settingsButton).isNotNull();
        assertThat(settingsButton.getText()).isEmpty();
        assertThat(settingsButton.getIcon()).isNotNull();
        assertThat(settingsButton.getPreferredSize()).isEqualTo(new Dimension(24, 24));
        assertThat(settingsButton.isContentAreaFilled()).isFalse();

        SwingUtilities.invokeAndWait(settingsButton::doClick);

        assertThat(settingsInvoked).hasValue(1);
    }


    @Test
    @DisplayName("Deleting a conversation notifies deletion callback without starting a new chat")
    void deleteConversation_whenDeletionCallbackConfigured_doesNotInvokeNewChatCallback() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DeletableConversationRepo(grouped("Today", conversation(conversationId, "Delete me")));
        var panelRef = new AtomicReference<SidebarPanel>();
        var newChatCalls = new AtomicInteger();
        var deletedIds = new AtomicReference<List<UUID>>();

        SwingUtilities.invokeAndWait(() -> {
            SidebarPanel panel = new SidebarPanel(repo);
            panel.setOnNewChat(newChatCalls::incrementAndGet);
            panel.setOnConversationsDeleted(deletedIds::set);
            panelRef.set(panel);
        });

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(panelRef.get()).contains("Delete me"));

        SwingUtilities.invokeAndWait(() -> invokeDeleteConversation(
                panelRef.get(),
                new ConversationItem(conversationId, "Delete me", "OpenAI", "gpt-4.1", false, LocalDateTime.now())
        ));

        assertThat(repo.deletedConversationIds).containsExactly(conversationId);
        assertThat(deletedIds.get()).containsExactly(conversationId);
        assertThat(newChatCalls).hasValue(0);
    }

    @Test
    @DisplayName("Refresh keeps the restored selected row visible after sidebar reordering")
    void refresh_whenSelectedConversationMoves_keepsSelectedRowVisible() throws Exception {
        UUID selectedId = UUID.randomUUID();
        List<ConversationRepo.ConversationRecord> initialRecords = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            initialRecords.add(conversation(UUID.randomUUID(), "Chat " + i));
        }
        initialRecords.add(conversation(selectedId, "Streaming LM Studio chat"));

        List<ConversationRepo.ConversationRecord> reorderedRecords = new ArrayList<>();
        reorderedRecords.add(conversation(selectedId, "Streaming LM Studio chat"));
        reorderedRecords.addAll(initialRecords.stream()
                .filter(record -> !record.id().equals(selectedId))
                .toList());

        var repo = new MutableConversationRepo(grouped("Today", initialRecords.toArray(ConversationRepo.ConversationRecord[]::new)));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();
        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Streaming LM Studio chat"));

        SwingUtilities.invokeAndWait(() -> {
            subject.setSize(300, 140);
            subject.doLayout();
            JList<?> list = readConversationList(subject);
            list.setFixedCellHeight(24);
            list.setSize(300, list.getPreferredSize().height);
            JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(
                    JScrollPane.class,
                    list
            );
            scrollPane.setSize(300, 140);
            scrollPane.getViewport().setExtentSize(new Dimension(300, 140));
            scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, list.getHeight() - 140)));
            scrollPane.doLayout();
            subject.selectConversation(selectedId);
        });
        assertThat(selectedConversationIsVisible(subject)).isTrue();

        repo.grouped = grouped("Today", reorderedRecords.toArray(ConversationRepo.ConversationRecord[]::new));
        SwingUtilities.invokeAndWait(subject::refresh);

        awaitCondition(2, TimeUnit.SECONDS, () -> selectedConversationIsVisible(subject));
    }

    @Test
    @DisplayName("Streaming state swaps the provider icon for a loading icon and restores it afterwards")
    void setConversationStreaming_whenStateChanges_swapsConversationIcon() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DelayedConversationRepo(0, grouped("Today", conversation(conversationId, "Streaming conversation")));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Streaming conversation"));

        Icon providerIcon = readConversationIcon(subject, conversationId);
        assertThat(providerIcon).isNotNull();

        SwingUtilities.invokeAndWait(() -> subject.setConversationStreaming(conversationId, true));
        Icon loadingIcon = readConversationIcon(subject, conversationId);

        assertThat(loadingIcon).isNotNull();
        assertThat(loadingIcon.getClass().getName()).contains("LoadingIcon");
        assertThat(loadingIcon).isNotSameAs(providerIcon);

        SwingUtilities.invokeAndWait(() -> subject.setConversationStreaming(conversationId, false));
        Icon restoredIcon = readConversationIcon(subject, conversationId);

        assertThat(restoredIcon).isSameAs(providerIcon);
    }

    private List<String> conversationTitles(SidebarPanel panel) throws Exception {
        var titlesRef = new AtomicReference<List<String>>();
        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(panel);
            List<String> titles = Collections.list(model.elements()).stream()
                    .filter(ConversationItem.class::isInstance)
                    .map(ConversationItem.class::cast)
                    .map(ConversationItem::title)
                    .toList();
            titlesRef.set(titles);
        });
        return titlesRef.get();
    }

    private JTextField findTextField(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextField textField) {
                return textField;
            }
            if (component instanceof Container childContainer) {
                JTextField textField = findTextField(childContainer);
                if (textField != null) {
                    return textField;
                }
            }
        }
        return null;
    }

    private JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container childContainer) {
                JButton button = findButton(childContainer, text);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private JButton findButtonByTooltip(Container container, String tooltip) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && tooltip.equals(button.getToolTipText())) {
                return button;
            }
            if (component instanceof Container childContainer) {
                JButton button = findButtonByTooltip(childContainer, tooltip);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private Icon readConversationIcon(SidebarPanel panel, UUID conversationId) throws Exception {
        var iconRef = new AtomicReference<Icon>();
        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(panel);
            JList<?> list = readConversationList(panel);
            int conversationIndex = findConversationIndex(model, conversationId);
            Object value = model.get(conversationIndex);
            @SuppressWarnings({"rawtypes", "unchecked"})
            JLabel label = (JLabel) ((JList) list).getCellRenderer().getListCellRendererComponent(
                    (JList) list,
                    value,
                    conversationIndex,
                    false,
                    false
            );
            iconRef.set(label.getIcon());
        });
        return iconRef.get();
    }

    private boolean selectedConversationIsVisible(SidebarPanel panel) throws Exception {
        var visibleRef = new AtomicReference<Boolean>();
        SwingUtilities.invokeAndWait(() -> {
            JList<?> list = readConversationList(panel);
            int selectedIndex = list.getSelectedIndex();
            Rectangle selectedBounds = selectedIndex < 0 ? null : list.getCellBounds(selectedIndex, selectedIndex);
            JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
            Rectangle visibleRect = scrollPane.getViewport().getViewRect();
            visibleRef.set(selectedBounds != null && visibleRect.intersects(selectedBounds));
        });
        return visibleRef.get();
    }

    private int findConversationIndex(DefaultListModel<?> model, UUID conversationId) {
        return IntStream.range(0, model.size())
                .filter(index -> model.get(index) instanceof ConversationItem conversation
                        && conversation.id().equals(conversationId))
                .findFirst()
                .orElseThrow();
    }

    private void invokeDeleteConversation(SidebarPanel panel, ConversationItem conversation) {
        try {
            Method method = SidebarPanel.class.getDeclaredMethod("deleteConversation", ConversationItem.class);
            method.setAccessible(true);
            method.invoke(panel, conversation);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DefaultListModel<?> readListModel(SidebarPanel panel) {
        try {
            Field field = SidebarPanel.class.getDeclaredField("listModel");
            field.setAccessible(true);
            return (DefaultListModel<?>) field.get(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JList<?> readConversationList(SidebarPanel panel) {
        try {
            Field field = SidebarPanel.class.getDeclaredField("conversationList");
            field.setAccessible(true);
            return (JList<?>) field.get(panel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, List<ConversationRepo.ConversationRecord>> grouped(String groupName, String title) {
        return grouped(groupName, conversation(UUID.randomUUID(), title));
    }

    private static Map<String, List<ConversationRepo.ConversationRecord>> grouped(
            String groupName,
            ConversationRepo.ConversationRecord... records
    ) {
        return Map.of(groupName, List.of(records));
    }

    private static ConversationRepo.ConversationRecord conversation(UUID id, String title) {
        return conversation(id, title, false);
    }

    private static ConversationRepo.ConversationRecord conversation(UUID id, String title, boolean favorite) {
        return new ConversationRepo.ConversationRecord(
                id,
                title,
                "OpenAI",
                "gpt-4.1",
                favorite,
                "off",
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static String groupHeaderName(Object entry) throws Exception {
        Field field = entry.getClass().getDeclaredField("name");
        field.setAccessible(true);
        return (String) field.get(entry);
    }

    private static void awaitCondition(long timeout, TimeUnit unit, CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(15);
        }

        assertThat(condition.getAsBoolean()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static class DelayedConversationRepo extends ConversationRepo {

        private final long delayMillis;
        private final Map<String, List<ConversationRepo.ConversationRecord>> grouped;

        private DelayedConversationRepo(long delayMillis, Map<String, List<ConversationRepo.ConversationRecord>> grouped) {
            super(null);
            this.delayMillis = delayMillis;
            this.grouped = grouped;
        }

        @Override
        public Map<String, List<ConversationRepo.ConversationRecord>> findAllGroupedByDate() {
            sleep(delayMillis);
            return grouped;
        }
    }

    private static class MutableConversationRepo extends ConversationRepo {

        private Map<String, List<ConversationRepo.ConversationRecord>> grouped;

        private MutableConversationRepo(Map<String, List<ConversationRepo.ConversationRecord>> grouped) {
            super(null);
            this.grouped = grouped;
        }

        @Override
        public Map<String, List<ConversationRepo.ConversationRecord>> findAllGroupedByDate() {
            return grouped;
        }
    }

    private static class DeletableConversationRepo extends DelayedConversationRepo {

        private final List<UUID> deletedConversationIds = new ArrayList<>();

        private DeletableConversationRepo(Map<String, List<ConversationRepo.ConversationRecord>> grouped) {
            super(0, grouped);
        }

        @Override
        public void deleteConversation(UUID id) {
            deletedConversationIds.add(id);
        }
    }

    private static class SequencedConversationRepo extends ConversationRepo {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch secondCallStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSecondCall = new CountDownLatch(1);

        private SequencedConversationRepo() {
            super(null);
        }

        private void awaitSecondCallStarted() {
            awaitLatch(secondCallStarted, 2, TimeUnit.SECONDS);
        }

        @Override
        public Map<String, List<ConversationRepo.ConversationRecord>> findAllGroupedByDate() {
            int call = calls.incrementAndGet();
            return switch (call) {
                case 1 -> grouped("Today", "Initial conversation");
                case 2 -> {
                    secondCallStarted.countDown();
                    awaitLatch(releaseSecondCall, 2, TimeUnit.SECONDS);
                    yield grouped("Today", "Older conversation");
                }
                case 3 -> {
                    awaitLatch(secondCallStarted, 2, TimeUnit.SECONDS);
                    releaseSecondCall.countDown();
                    yield grouped("Today", "Latest conversation");
                }
                default -> grouped("Today", "Latest conversation");
            };
        }
    }

    private static void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            if (latch.await(timeout, unit)) {
                return;
            }
            throw new IllegalStateException("Timed out waiting for latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
