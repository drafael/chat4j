package com.github.drafael.chat4j.prompts;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class PromptCommandCenterTest {

    private static final Path UNUSED_PROMPTS_FILE = Path.of("unused-prompt-command-center.json");

    @BeforeEach
    void requireGraphicsEnvironment() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Prompt command center requires a graphics environment");
    }

    @Test
    @DisplayName("A completion from before hiding cannot replace prompts loaded after reopening")
    void openNear_whenOlderLoadCompletesAfterReopen_retainsNewerPrompts() throws Exception {
        var repo = new ReorderedPromptCatalogRepo(UNUSED_PROMPTS_FILE);
        try (CommandCenterFixture fixture = createFixture(repo)) {
            TestPromptCommandCenter subject = fixture.subject();
            JButton trigger = trigger();
            try {
                runOnEdt(() -> subject.openNear(trigger));
                assertThat(repo.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

                runOnEdt(() -> {
                    subject.hidePopup();
                    subject.openNear(trigger);
                });
                awaitWorker(repo.secondStarted, repo.secondWorker);

                repo.releaseFirst.countDown();
                assertThat(repo.firstFinished.await(5, TimeUnit.SECONDS)).isTrue();
                joinWorker(repo.firstWorker);

                assertThat(callOnEdt(() -> modelHasTitle(subject, "Second"))).isTrue();
                assertThat(callOnEdt(() -> modelHasTitle(subject, "First"))).isFalse();
            } finally {
                repo.releaseFirst.countDown();
                joinWorkerIfStarted(repo.firstWorker);
                joinWorkerIfStarted(repo.secondWorker);
            }
        }
    }

    @Test
    @DisplayName("A completion after disposal cannot update the prompt list")
    void dispose_whenLoadCompletesLater_suppressesPromptDelivery() throws Exception {
        var repo = new ReorderedPromptCatalogRepo(UNUSED_PROMPTS_FILE);
        try (CommandCenterFixture fixture = createFixture(repo)) {
            TestPromptCommandCenter subject = fixture.subject();
            JButton trigger = trigger();
            try {
                runOnEdt(() -> subject.openNear(trigger));
                assertThat(repo.firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

                runOnEdt(subject::dispose);
                repo.releaseFirst.countDown();
                assertThat(repo.firstFinished.await(5, TimeUnit.SECONDS)).isTrue();
                joinWorker(repo.firstWorker);

                assertThat(callOnEdt(() -> modelHasTitle(subject, "First"))).isFalse();
            } finally {
                repo.releaseFirst.countDown();
                joinWorkerIfStarted(repo.firstWorker);
                joinWorkerIfStarted(repo.secondWorker);
            }
        }
    }

    @Test
    @DisplayName("Hiding before deferred focus runs prevents a stale focus request")
    void hidePopup_whenFocusContinuationIsQueued_suppressesFocusRequest() throws Exception {
        var repo = new SuccessfulThenFailedPromptCatalogRepo(UNUSED_PROMPTS_FILE);
        try (CommandCenterFixture fixture = createFixture(repo)) {
            TestPromptCommandCenter subject = fixture.subject();
            JButton trigger = trigger();
            try {
                runOnEdt(() -> {
                    subject.openNear(trigger);
                    subject.hidePopup();
                });
                awaitWorker(repo.firstStarted, repo.firstWorker);

                assertThat(callOnEdt(subject::toFrontCalls)).isZero();
            } finally {
                joinWorkerIfStarted(repo.firstWorker);
                joinWorkerIfStarted(repo.secondWorker);
            }
        }
    }

    @Test
    @DisplayName("A failed refresh retains the last usable prompts and shows a concise error")
    void openNear_whenRefreshFails_retainsLastUsablePrompts() throws Exception {
        var repo = new SuccessfulThenFailedPromptCatalogRepo(UNUSED_PROMPTS_FILE);
        try (CommandCenterFixture fixture = createFixture(repo)) {
            TestPromptCommandCenter subject = fixture.subject();
            JButton trigger = trigger();
            try {
                runOnEdt(() -> subject.openNear(trigger));
                awaitWorker(repo.firstStarted, repo.firstWorker);
                assertThat(callOnEdt(() -> modelHasTitle(subject, "Custom"))).isTrue();

                runOnEdt(() -> {
                    subject.hidePopup();
                    subject.openNear(trigger);
                });
                awaitWorker(repo.secondStarted, repo.secondWorker);

                assertThat(callOnEdt(() -> statusLabel(subject).getText()))
                        .isEqualTo("Could not refresh prompts");
                assertThat(callOnEdt(() -> modelHasTitle(subject, "Custom"))).isTrue();
            } finally {
                joinWorkerIfStarted(repo.firstWorker);
                joinWorkerIfStarted(repo.secondWorker);
            }
        }
    }

    private CommandCenterFixture createFixture(PromptCatalogRepo repo) throws Exception {
        JFrame owner = callOnEdt(JFrame::new);
        try {
            TestPromptCommandCenter subject = callOnEdt(() -> new TestPromptCommandCenter(owner, repo));
            return new CommandCenterFixture(owner, subject);
        } catch (Exception | Error e) {
            try {
                runOnEdt(owner::dispose);
            } catch (Exception cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    private JButton trigger() throws Exception {
        return callOnEdt(() -> new JButton() {
            @Override
            public Point getLocationOnScreen() {
                return new Point(100, 500);
            }
        });
    }

    private boolean modelHasTitle(PromptCommandCenter subject, String title) {
        return modelTitles(subject).stream().anyMatch(value -> value.contains("title=%s]".formatted(title)));
    }

    private void awaitWorker(CountDownLatch started, AtomicReference<Thread> worker) throws Exception {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        joinWorker(worker);
    }

    private void joinWorker(AtomicReference<Thread> worker) throws Exception {
        Thread thread = worker.get();
        assertThat(thread).isNotNull();
        joinWorker(thread);
    }

    private void joinWorkerIfStarted(AtomicReference<Thread> worker) throws Exception {
        Thread thread = worker.get();
        if (thread != null) {
            joinWorker(thread);
        }
    }

    private void joinWorker(Thread thread) throws Exception {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(thread.isAlive()).isFalse();
        flushEdt();
    }

    private List<String> modelTitles(PromptCommandCenter subject) {
        DefaultListModel<?> model = (DefaultListModel<?>) promptList(subject).getModel();
        return IntStream.range(0, model.size())
                .mapToObj(model::get)
                .map(Object::toString)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private JList<Object> promptList(PromptCommandCenter subject) {
        return (JList<Object>) readField(subject, "promptList");
    }

    private JLabel statusLabel(PromptCommandCenter subject) {
        return (JLabel) readField(subject, "loadStatus");
    }

    private Object readField(PromptCommandCenter subject, String name) {
        try {
            Field field = PromptCommandCenter.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(subject);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void runOnEdt(Runnable action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private void flushEdt() throws Exception {
        runOnEdt(() -> {
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var value = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return value.get();
    }

    private PromptTemplate prompt(String id, String title) {
        return new PromptTemplate(id, title, "Text", PromptTemplate.DEFAULT_MODEL, List.of());
    }

    private final class ReorderedPromptCatalogRepo extends PromptCatalogRepo {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> firstWorker = new AtomicReference<>();
        private final AtomicReference<Thread> secondWorker = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        private ReorderedPromptCatalogRepo(Path promptsFile) {
            super(promptsFile);
        }

        @Override
        public PromptCatalogLoadResult loadResult() {
            if (calls.incrementAndGet() > 1) {
                secondWorker.set(Thread.currentThread());
                secondStarted.countDown();
                return new PromptCatalogLoadResult(List.of(prompt("second", "Second")), false);
            }
            firstWorker.set(Thread.currentThread());
            firstStarted.countDown();
            try {
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the first prompt load");
                }
                return new PromptCatalogLoadResult(List.of(prompt("first", "First")), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                firstFinished.countDown();
            }
        }
    }

    private final class SuccessfulThenFailedPromptCatalogRepo extends PromptCatalogRepo {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> firstWorker = new AtomicReference<>();
        private final AtomicReference<Thread> secondWorker = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        private SuccessfulThenFailedPromptCatalogRepo(Path promptsFile) {
            super(promptsFile);
        }

        @Override
        public PromptCatalogLoadResult loadResult() {
            if (calls.incrementAndGet() == 1) {
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                return new PromptCatalogLoadResult(List.of(prompt("custom", "Custom")), false);
            }
            secondWorker.set(Thread.currentThread());
            secondStarted.countDown();
            return new PromptCatalogLoadResult(BuiltInPromptCatalog.prompts(), true);
        }
    }

    private final class CommandCenterFixture implements AutoCloseable {
        private final JFrame owner;
        private final TestPromptCommandCenter subject;

        private CommandCenterFixture(JFrame owner, TestPromptCommandCenter subject) {
            this.owner = owner;
            this.subject = subject;
        }

        private TestPromptCommandCenter subject() {
            return subject;
        }

        @Override
        public void close() throws Exception {
            runOnEdt(() -> {
                subject.dispose();
                owner.dispose();
            });
            flushEdt();
        }
    }

    private static final class TestPromptCommandCenter extends PromptCommandCenter {
        private boolean visible;
        private int toFrontCalls;

        private TestPromptCommandCenter(Window owner, PromptCatalogRepo repo) {
            super(owner, repo, List::of, ignored -> {
            });
        }

        @Override
        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public void toFront() {
            toFrontCalls++;
        }

        private int toFrontCalls() {
            return toFrontCalls;
        }
    }
}
