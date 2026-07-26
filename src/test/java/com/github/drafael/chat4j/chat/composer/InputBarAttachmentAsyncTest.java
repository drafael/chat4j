package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.chat.ComposerState;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class InputBarAttachmentAsyncTest {

    @Test
    @DisplayName("Attachment metadata prepared in the background is applied on the EDT")
    void addAttachments_whenFileIsValid_appliesPreparedAttachment(@TempDir Path tempDir) throws Exception {
        Path markdown = Files.writeString(tempDir.resolve("notes.md"), "# hello");
        InputBar subject = callOnEdt(InputBar::new);

        runOnEdt(() -> invokeAddAttachments(subject, List.of(markdown)));

        awaitCondition(() -> callOnEdt(() -> !subject.getComposerState().attachments().isEmpty()));
        ComposerState state = callOnEdt(subject::getComposerState);
        assertThat(state.attachments()).singleElement().extracting(ComposerAttachment::path).isEqualTo(markdown);
    }

    @Test
    @DisplayName("Concurrent attachment selections are merged when both complete")
    void addAttachments_whenSelectionsOverlap_retainsBothSelections(@TempDir Path tempDir) throws Exception {
        Path first = Files.writeString(tempDir.resolve("first.md"), "first");
        Path second = Files.writeString(tempDir.resolve("second.md"), "second");
        InputBar subject = callOnEdt(InputBar::new);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var applied = new CountDownLatch(2);
        var delegate = new AttachmentSelectionPolicy();
        callOnEdt(() -> {
            subject.setAttachmentSelectionAppliedListenerForTests(applied::countDown);
            subject.setAttachmentSelectionPolicyForTests(new AttachmentSelectionPolicy() {
                @Override
                ComposerAttachment create(Path path) throws IOException {
                    if (path.equals(first)) {
                        firstStarted.countDown();
                        try {
                            releaseFirst.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted", e);
                        }
                    }
                    return delegate.create(path);
                }
            });
            return null;
        });

        runOnEdt(() -> invokeAddAttachments(subject, List.of(first)));
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(() -> invokeAddAttachments(subject, List.of(second)));
        releaseFirst.countDown();
        assertThat(applied.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(callOnEdt(subject::getComposerState).attachments())
                .extracting(ComposerAttachment::path)
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Clearing the composer invalidates an in-flight attachment selection")
    void clear_whenAttachmentSelectionIsPending_preventsLateApply(@TempDir Path tempDir) throws Exception {
        Path markdown = Files.writeString(tempDir.resolve("late.md"), "# hello");
        InputBar subject = callOnEdt(InputBar::new);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var applied = new CountDownLatch(1);
        var delegate = new AttachmentSelectionPolicy();
        callOnEdt(() -> {
            subject.setAttachmentSelectionAppliedListenerForTests(applied::countDown);
            subject.setAttachmentSelectionPolicyForTests(new AttachmentSelectionPolicy() {
                @Override
                ComposerAttachment create(Path path) throws IOException {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted", e);
                    }
                    return delegate.create(path);
                }
            });
            return null;
        });

        runOnEdt(() -> invokeAddAttachments(subject, List.of(markdown)));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::clear);
        release.countDown();
        assertThat(applied.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(callOnEdt(subject::getComposerState).attachments()).isEmpty();
    }

    private static void invokeAddAttachments(InputBar subject, List<Path> paths) throws Exception {
        Method method = InputBar.class.getDeclaredMethod("addAttachments", List.class);
        method.setAccessible(true);
        method.invoke(subject, paths);
    }

    private static void awaitCondition(CheckedBooleanSupplier condition) throws Exception {
        var interval = new CountDownLatch(1);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadlineNanos) {
            interval.await(10, TimeUnit.MILLISECONDS);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void runOnEdt(ThrowingRunnable action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callOnEdt(ThrowingSupplier<T> action) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.get());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
