package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderDef;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSelectorModelInfoEdtTest {

    @TempDir
    Path tempDir;
    private ModelSelectorPopup subject;
    private JDialog owner;
    private ProviderRegistry registry;
    private ProviderDef provider;
    private final AtomicReference<Thread> metadataWorker = new AtomicReference<>();
    private final CountDownLatch releaseMetadata = new CountDownLatch(1);
    private final CountDownLatch metadataStarted = new CountDownLatch(1);
    private final AtomicReference<String> selected = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required.");
        provider = new ProviderDef("GitHub Copilot", null, "https://example.invalid", "https://example.invalid",
                List.of("first", "second"), ProviderCapabilities.chatAndModels(), model -> null, List::of);
        registry = mock(ProviderRegistry.class);
        var providerWorker = new AtomicReference<Thread>();
        var providersStarted = new CountDownLatch(1);
        when(registry.availableProviders()).thenAnswer(invocation -> {
            providerWorker.set(Thread.currentThread());
            providersStarted.countDown();
            return List.of(provider);
        });
        var cache = new ProviderModelCacheService(new ProviderModelCache(StoragePaths.ofConfigHome(tempDir)));
        cache.synchronizeScope(provider.name(), provider.baseUrl(), cache.nextScopeVersion());
        var attempt = cache.tryBeginRefreshIfNeeded(provider.name(), provider.baseUrl(), Duration.ZERO).orElseThrow();
        assertThat(cache.update(attempt, provider.seedModels())).isTrue();
        callOnEdt(() -> {
            owner = new JDialog();
            subject = new ModelSelectorPopup(owner, cache, ModelFavoritesService.createInMemory(), registry,
                    (name, model) -> selected.set(model), (providers, scope) -> true, () -> { }, () -> { },
                    mock(CredentialResolver.class));
            subject.showCentered(provider.name(), "first");
            return null;
        });
        assertThat(providersStarted.await(3, TimeUnit.SECONDS)).isTrue();
        joinAndFlush(providerWorker.get());
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseMetadata.countDown();
        callOnEdt(() -> {
            if (subject != null) {
                subject.dispose();
            }
            if (owner != null) {
                owner.dispose();
            }
            return null;
        });
        if (metadataWorker.get() != null) {
            joinAndFlush(metadataWorker.get());
        }
        callOnEdt(() -> null);
    }

    @Test
    @DisplayName("Blocked metadata does not block Swing, and completion shows the latest highlighted model")
    void showModelInfo_highlightChangesDuringRequest_showsLatestModelWithoutBlockingEdt() throws Exception {
        blockMetadata();
        highlightAndFireDelay();
        assertThat(metadataStarted.await(3, TimeUnit.SECONDS)).isTrue();
        var edtResponded = new CountDownLatch(1);
        SwingUtilities.invokeLater(edtResponded::countDown);
        assertThat(edtResponded.await(1, TimeUnit.SECONDS)).isTrue();
        highlightAndFireDelay();
        releaseMetadata.countDown();
        joinAndFlush(metadataWorker.get());

        callOnEdt(() -> {
            ModelInfoPopup card = field(subject, "modelInfoPopup");
            assertThat(card.isVisible()).isTrue();
            assertThat(card.getFocusableWindowState()).isFalse();
            assertThat(card.isAutoRequestFocus()).isFalse();
            JPanel content = (JPanel) ((JScrollPane) card.getContentPane()).getViewport().getView();
            JLabel providerIcon = (JLabel) ((JPanel) content.getComponent(0)).getComponent(0);
            assertThat(providerIcon.getIcon()).isNotNull();
            assertThat(Arrays.stream(content.getComponents())
                    .flatMap(component -> component instanceof JPanel panel ? Arrays.stream(panel.getComponents()) : Stream.of(component))
                    .filter(JTextArea.class::isInstance).map(JTextArea.class::cast).map(JTextArea::getText).toList())
                    .contains("first", "First model").doesNotContain("Second model");
            ModelRowComponent row = field(subject, "modelInfoRow");
            assertThat(row.modelId()).isEqualTo("first");
            row.panel().dispatchEvent(new MouseEvent(row.panel(), MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1));
            assertThat(subject.isVisible()).isFalse();
            assertThat(card.isVisible()).isFalse();
            return null;
        });
        assertThat(selected).hasValue("first");
        verify(registry, times(1)).fetchModelInfos(provider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Closing or invalidating the selector rejects metadata completion even if cancellation is ignored")
    void hidePopup_metadataCompletesAfterDismissalOrInvalidation_doesNotDisplayOrCacheStaleResult(boolean invalidate) throws Exception {
        blockMetadata();
        highlightAndFireDelay();
        assertThat(metadataStarted.await(3, TimeUnit.SECONDS)).isTrue();
        callOnEdt(() -> {
            if (invalidate) {
                subject.invalidateModelList();
            } else {
                subject.hidePopup();
            }
            return null;
        });
        releaseMetadata.countDown();
        joinAndFlush(metadataWorker.get());

        callOnEdt(() -> {
            assertThat((Object) field(subject, "modelInfoPopup")).isNull();
            Map<?, ?> cache = field(subject, "modelInfoCache");
            assertThat(cache).isEmpty();
            Timer timer = field(subject, "modelInfoTimer");
            assertThat(timer.isRunning()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Preparing the selector again retries metadata that previously failed")
    void showCentered_previousMetadataFailure_retriesOnNextPreparation() throws Exception {
        when(registry.fetchModelInfos(provider)).thenAnswer(invocation -> {
            metadataWorker.set(Thread.currentThread());
            metadataStarted.countDown();
            throw new IllegalStateException("Unavailable");
        });
        highlightAndFireDelay();
        assertThat(metadataStarted.await(3, TimeUnit.SECONDS)).isTrue();
        joinAndFlush(metadataWorker.get());
        callOnEdt(() -> {
            assertThat((Object) field(subject, "modelInfoPopup")).isNull();
            subject.showCentered(provider.name(), "first");
            return null;
        });
        var retryStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            metadataWorker.set(Thread.currentThread());
            retryStarted.countDown();
            return infos();
        }).when(registry).fetchModelInfos(provider);
        highlightAndFireDelay();
        assertThat(retryStarted.await(3, TimeUnit.SECONDS)).isTrue();
        joinAndFlush(metadataWorker.get());
        callOnEdt(() -> {
            ModelInfoPopup card = field(subject, "modelInfoPopup");
            assertThat(card.isVisible()).isTrue();
            return null;
        });
        verify(registry, times(2)).fetchModelInfos(provider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Moving from a model row into its card allows scrolling without changing selection")
    void showModelInfo_pointerEntersCard_keepsOverflowContentScrollable(boolean enterScrollbar) throws Exception {
        ModelInfoPopup card = showScrollableModelInfoAndExitRow();
        callOnEdt(() -> {
            Timer hideTimer = field(subject, "modelInfoHideTimer");
            assertThat(hideTimer.isRunning()).isTrue();
            assertThat(card.isVisible()).isTrue();
            JScrollPane scroll = (JScrollPane) card.getContentPane();
            var target = enterScrollbar ? scroll.getVerticalScrollBar() : scroll.getViewport();
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_ENTERED,
                    System.currentTimeMillis(), 0, 5, 5, 0, false));
            assertThat(hideTimer.isRunning()).isFalse();
            int previousPosition = scroll.getVerticalScrollBar().getValue();
            scroll.dispatchEvent(new MouseWheelEvent(scroll, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 5, 5, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
            assertThat(scroll.getVerticalScrollBar().getValue()).isGreaterThan(previousPosition);
            assertThat(card.isVisible()).isTrue();
            assertThat(subject.isVisible()).isTrue();
            assertThat(selected).hasNullValue();
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_EXITED,
                    System.currentTimeMillis(), 0, -1, -1, 0, false));
            assertThat(hideTimer.isRunning()).isTrue();
            fireTimer(hideTimer);
            assertThat(card.isVisible()).isFalse();
            assertThat(subject.isVisible()).isTrue();
            assertThat(hideTimer.isRunning()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Closing the selector cancels pending hover-card dismissal")
    void hidePopup_cardDismissalPending_stopsTimerAndHidesCard() throws Exception {
        ModelInfoPopup card = showScrollableModelInfoAndExitRow();
        callOnEdt(() -> {
            Timer hideTimer = field(subject, "modelInfoHideTimer");
            assertThat(hideTimer.isRunning()).isTrue();
            subject.hidePopup();
            assertThat(hideTimer.isRunning()).isFalse();
            assertThat(card.isVisible()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Leaving a row before metadata arrives does not display a delayed card")
    void showModelInfo_pointerLeavesBeforeCompletion_doesNotDisplayCard() throws Exception {
        moveSelectorAwayFromPointer();
        blockMetadata();
        highlightAndFireDelay();
        assertThat(metadataStarted.await(3, TimeUnit.SECONDS)).isTrue();
        callOnEdt(() -> {
            ModelRowComponent row = field(subject, "modelInfoRow");
            row.panel().dispatchEvent(new MouseEvent(row.panel(), MouseEvent.MOUSE_EXITED,
                    System.currentTimeMillis(), 0, -1, -1, 0, false));
            return null;
        });
        callOnEdt(() -> null);
        releaseMetadata.countDown();
        joinAndFlush(metadataWorker.get());
        callOnEdt(() -> {
            assertThat((Object) field(subject, "modelInfoPopup")).isNull();
            Timer hideTimer = field(subject, "modelInfoHideTimer");
            assertThat(hideTimer.isRunning()).isFalse();
            return null;
        });
    }

    private ModelInfoPopup showScrollableModelInfoAndExitRow() throws Exception {
        when(registry.fetchModelInfos(provider)).thenAnswer(invocation -> {
            metadataWorker.set(Thread.currentThread());
            metadataStarted.countDown();
            String description = "A longer synthetic model description that requires vertical scrolling. ".repeat(200);
            return Map.of("first", ProviderModelInfo.builder().modelId("first").description(description).build(),
                    "second", ProviderModelInfo.builder().modelId("second").description(description).build());
        });
        moveSelectorAwayFromPointer();
        highlightAndFireDelay();
        assertThat(metadataStarted.await(3, TimeUnit.SECONDS)).isTrue();
        joinAndFlush(metadataWorker.get());
        ModelInfoPopup card = callOnEdt(() -> {
            ModelInfoPopup popup = field(subject, "modelInfoPopup");
            assertThat(popup.isVisible()).isTrue();
            Timer hideTimer = field(subject, "modelInfoHideTimer");
            assertThat(hideTimer.getInitialDelay()).isEqualTo(200);
            // Fire dismissal explicitly rather than depending on wall-clock scheduling.
            hideTimer.setInitialDelay(Integer.MAX_VALUE);
            // A full-height card can cover the stationary cursor on a small CI display.
            Rectangle screen = popup.getGraphicsConfiguration().getBounds();
            Point pointer = MouseInfo.getPointerInfo().getLocation();
            popup.setSize(popup.getWidth(), Math.min(popup.getHeight(), screen.height / 3));
            int y = pointer.y < screen.getCenterY() ? screen.y + screen.height - popup.getHeight() : screen.y;
            popup.setLocation(popup.getX(), y);
            popup.validate();
            JScrollPane scroll = (JScrollPane) popup.getContentPane();
            assertThat(scroll.getVerticalScrollBar().isVisible()).isTrue();
            assertThat(popup.getMousePosition(true)).isNull();
            ModelRowComponent row = field(subject, "modelInfoRow");
            assertThat(row.panel().getMousePosition(true)).isNull();
            row.panel().dispatchEvent(new MouseEvent(row.panel(), MouseEvent.MOUSE_EXITED,
                    System.currentTimeMillis(), 0, -1, -1, 0, false));
            return popup;
        });
        callOnEdt(() -> null);
        return card;
    }

    private void moveSelectorAwayFromPointer() throws Exception {
        callOnEdt(() -> {
            // Keep the real pointer outside these windows; synthetic events own the transition under test.
            Rectangle screen = subject.getGraphicsConfiguration().getBounds();
            Point pointer = MouseInfo.getPointerInfo().getLocation();
            int x = pointer.x < screen.getCenterX() ? screen.x + screen.width - subject.getWidth() - 10 : screen.x + 10;
            subject.setLocation(x, screen.y + 40);
            return null;
        });
        callOnEdt(() -> null);
    }

    private void blockMetadata() throws Exception {
        when(registry.fetchModelInfos(provider)).thenAnswer(invocation -> {
            metadataWorker.set(Thread.currentThread());
            metadataStarted.countDown();
            // Model a provider that cannot immediately cancel its request.
            boolean released = false;
            while (!released) {
                try {
                    released = releaseMetadata.await(5, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Test metadata release timed out");
                    }
                } catch (InterruptedException ignored) {
                }
            }
            return infos();
        });
    }

    private Map<String, ProviderModelInfo> infos() {
        return Map.of("first", ProviderModelInfo.builder().modelId("first").description("First model").build(),
                "second", ProviderModelInfo.builder().modelId("second").description("Second model").build());
    }

    private void highlightAndFireDelay() throws Exception {
        callOnEdt(() -> {
            assertThat(subject.isVisible()).as("selector remains visible before keyboard navigation").isTrue();
            JTextField search = field(subject, "searchField");
            var event = new KeyEvent(search, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_DOWN, '\0');
            Arrays.stream(search.getKeyListeners()).forEach(listener -> listener.keyPressed(event));
            Timer timer = field(subject, "modelInfoTimer");
            assertThat(timer.isRunning()).isTrue();
            assertThat(timer.getInitialDelay()).isEqualTo(350);
            fireTimer(timer);
            return null;
        });
    }

    private void fireTimer(Timer timer) {
        timer.stop();
        Arrays.stream(timer.getActionListeners())
                .forEach(listener -> listener.actionPerformed(new ActionEvent(timer, 0, "test-delay")));
    }

    private void joinAndFlush(Thread worker) throws Exception {
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();
        callOnEdt(() -> null);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(instance);
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
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
}
