package com.github.drafael.chat4j.sidebar;

import com.github.drafael.chat4j.storage.ConversationRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SidebarPanelTest {

    @Test
    @DisplayName("Sidebar refresh does not block EDT while repository call is in flight")
    void refresh_whenRepositoryIsSlow_doesNotBlockEdt() throws Exception {
        var repo = new DelayedConversationRepo(350, grouped("Today", "Slow conversation"));
        var panelRef = new AtomicReference<SidebarPanel>();

        long started = System.nanoTime();
        SwingUtilities.invokeAndWait(() -> panelRef.set(new SidebarPanel(repo)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(250);

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

    private List<String> conversationTitles(SidebarPanel panel) throws Exception {
        var titlesRef = new AtomicReference<List<String>>();
        SwingUtilities.invokeAndWait(() -> {
            DefaultListModel<?> model = readListModel(panel);
            List<String> titles = java.util.Collections.list(model.elements()).stream()
                    .filter(ConversationItem.class::isInstance)
                    .map(ConversationItem.class::cast)
                    .map(ConversationItem::title)
                    .toList();
            titlesRef.set(titles);
        });
        return titlesRef.get();
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

    private static Map<String, List<ConversationRepo.ConversationRecord>> grouped(String groupName, String title) {
        return Map.of(groupName, List.of(new ConversationRepo.ConversationRecord(
                UUID.randomUUID(),
                title,
                "OpenAI",
                "gpt-4.1",
                false,
                LocalDateTime.now(),
                LocalDateTime.now()
        )));
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
