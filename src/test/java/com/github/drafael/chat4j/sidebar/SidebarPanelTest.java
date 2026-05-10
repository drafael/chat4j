package com.github.drafael.chat4j.sidebar;

import com.github.drafael.chat4j.storage.ConversationRepo;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarPanelTest {

    @Test
    @DisplayName("Sidebar refresh does not block EDT while repository call is in flight")
    void refresh_whenRepositoryIsSlow_doesNotBlockEdt() throws Exception {
        SwingUtilities.invokeAndWait(javax.swing.JPanel::new);
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
    @DisplayName("Agent project conversations render in project groups before date groups")
    void refresh_whenProjectConversationExists_rendersProjectGroup() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        var repo = new DelayedConversationRepo(0, grouped(
                "Today",
                conversation(projectId, "Project chat", false, "/Users/me/code/chat4j-pi"),
                conversation(regularId, "Regular chat")
        ));
        var panelRef = new AtomicReference<SidebarPanel>();

        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        SidebarPanel subject = panelRef.get();

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).contains("Project chat"));

        DefaultListModel<?> model = readListModel(subject);
        assertThat(groupHeaderName(model.get(0))).isEqualTo("Project · chat4j-pi");
        assertThat(((ConversationItem) model.get(1)).title()).isEqualTo("Project chat");
        assertThat(groupHeaderName(model.get(2))).isEqualTo("Today");
        assertThat(((ConversationItem) model.get(3)).title()).isEqualTo("Regular chat");
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

        SwingUtilities.invokeAndWait(() -> filterField.setText("beta"));

        awaitCondition(2, TimeUnit.SECONDS, () -> conversationTitles(subject).equals(List.of("Beta notes")));
    }

    @Test
    @DisplayName("Repository favorites group is not rendered twice")
    void refresh_whenRepositoryAlreadyGroupsFavorites_rendersSingleFavoritesSection() throws Exception {
        UUID favoriteId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        Map<String, List<ConversationRepo.ConversationRecord>> grouped = new java.util.LinkedHashMap<>();
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
    @DisplayName("Favorites render in a dedicated section before date groups")
    void refresh_whenFavoritesExist_rendersFavoritesSectionFirst() throws Exception {
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
        assertThat(groupHeaderName(model.get(0))).isEqualTo("Favorites");
        assertThat(((ConversationItem) model.get(1)).title()).isEqualTo("Favorite chat");
        assertThat(groupHeaderName(model.get(2))).isEqualTo("Today");
        assertThat(((ConversationItem) model.get(3)).title()).isEqualTo("Regular chat");
        assertThat(Collections.frequency(conversationTitles(subject), "Favorite chat")).isEqualTo(1);
    }

    @Test
    @DisplayName("Sidebar header action buttons dispatch callbacks")
    void headerActionButtons_whenClicked_dispatchCallbacks() throws Exception {
        var repo = new DelayedConversationRepo(0, Map.of());
        var panelRef = new AtomicReference<SidebarPanel>();
        var newChatInvoked = new AtomicInteger();
        var searchInvoked = new AtomicInteger();
        var projectsInvoked = new AtomicInteger();
        var settingsInvoked = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            SidebarPanel panel = new SidebarPanel(repo);
            panel.setOnNewChat(newChatInvoked::incrementAndGet);
            panel.setOnSearch(searchInvoked::incrementAndGet);
            panel.setOnProjects(projectsInvoked::incrementAndGet);
            panel.setOnSettings(settingsInvoked::incrementAndGet);
            panelRef.set(panel);
        });

        JButton newChatButton = findButton(panelRef.get(), "New chat");
        JButton searchButton = findButton(panelRef.get(), "Search");
        JButton projectsButton = findButton(panelRef.get(), "Projects");
        JButton settingsButton = findButton(panelRef.get(), "Settings");
        assertThat(newChatButton).isNotNull();
        assertThat(searchButton).isNotNull();
        assertThat(projectsButton).isNotNull();
        assertThat(settingsButton).isNotNull();

        SwingUtilities.invokeAndWait(() -> {
            newChatButton.doClick();
            searchButton.doClick();
            projectsButton.doClick();
            settingsButton.doClick();
        });

        assertThat(newChatInvoked).hasValue(1);
        assertThat(searchInvoked).hasValue(1);
        assertThat(projectsInvoked).hasValue(1);
        assertThat(settingsInvoked).hasValue(1);
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

    private Icon readConversationIcon(SidebarPanel panel, UUID conversationId) throws Exception {
        var iconRef = new AtomicReference<Icon>();
        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(panel);
            JList<?> list = readConversationList(panel);
            int conversationIndex = IntStream.range(0, model.size())
                    .filter(index -> model.get(index) instanceof ConversationItem conversation
                            && conversation.id().equals(conversationId))
                    .findFirst()
                    .orElseThrow();
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
        return conversation(id, title, favorite, null);
    }

    private static ConversationRepo.ConversationRecord conversation(UUID id, String title, boolean favorite, String agentProjectRoot) {
        return new ConversationRepo.ConversationRecord(
                id,
                title,
                "OpenAI",
                "gpt-4.1",
                favorite,
                "off",
                StringUtils.isNotBlank(agentProjectRoot),
                agentProjectRoot,
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
