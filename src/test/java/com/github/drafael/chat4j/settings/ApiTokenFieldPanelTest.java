package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFieldPanelTest {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("API token field saves entered token and notifies listeners")
    void savePendingChangesAsync_whenTokenEntered_savesTokenAndNotifiesListener() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        var change = new AtomicReference<ApiTokenChange>();
        var refreshes = new AtomicInteger();
        String envVar = "OPENAI_API_KEY";
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                envVar,
                registry,
                change::set,
                refreshes::incrementAndGet
        ));
        JPasswordField tokenField = findPasswordField(subject);
        assertThat(tokenField.isEnabled()).isTrue();

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get()).isTrue();
        assertThat(CredentialResolver.resolveRequiredApiKey(envVar, null)).isEqualTo("saved-token");
        assertThat(CredentialResolver.resolveCredentialStatus(envVar, null).source())
                .isEqualTo(ApiCredentialSource.SAVED_TOKEN);
        assertThat(subject.dirty()).isFalse();
        assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
        assertThat(change.get())
                .extracting(ApiTokenChange::canonicalTokenId, ApiTokenChange::cleared, ApiTokenChange::savedOverride)
                .containsExactly(envVar, false, true);
        assertThat(refreshes).hasValue(1);
    }

    @Test
    @DisplayName("API token field runs credential-change listener off the EDT before local refresh")
    void savePendingChangesAsync_whenListenerIsSlow_doesNotBlockEdtAndWaitsBeforeRefresh() throws Exception {
        var listenerRanOnEdt = new AtomicReference<Boolean>();
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var refreshes = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change -> {
                    listenerRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    listenerStarted.countDown();
                    try {
                        releaseListener.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                },
                refreshes::incrementAndGet
        ));
        JPasswordField tokenField = findPasswordField(subject);

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerRanOnEdt.get()).isFalse();
        assertThat(save).isNotDone();
        assertThat(refreshes).hasValue(0);
        onEdt(() -> {
            assertTokenControlsEnabled(subject, false);
            return null;
        });

        releaseListener.countDown();

        assertThat(save.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(refreshes).hasValue(1);
        onEdt(() -> {
            assertTokenControlsEnabled(subject, true);
            return null;
        });
    }

    @Test
    @DisplayName("API token field clears saved override when blank dirty field loses focus")
    void focusLost_whenBlankAndSavedTokenExists_deletesOverrideAndNotifiesListener() throws Exception {
        char[] savedToken = "saved-token".toCharArray();
        try {
            CredentialResolver.saveTokenOverride("OPENAI_API_KEY", savedToken);
        } finally {
            Arrays.fill(savedToken, '\0');
        }
        var change = new AtomicReference<ApiTokenChange>();
        var saved = new CountDownLatch(1);
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change::set,
                saved::countDown
        ));
        JPasswordField tokenField = findPasswordField(subject);

        onEdt(() -> {
            tokenField.setText("");
            fireFocusLost(tokenField);
            return null;
        });

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
        assertThat(change.get())
                .extracting(ApiTokenChange::canonicalTokenId, ApiTokenChange::cleared, ApiTokenChange::savedOverride)
                .containsExactly("OPENAI_API_KEY", true, false);
    }

    @Test
    @DisplayName("Blank dirty env-backed field reverts without writing a vault record")
    void savePendingChangesAsync_whenBlankWithoutSavedOverride_revertsToEffectiveCredential() throws Exception {
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "env-token"));
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                null
        ));
        JPasswordField tokenField = findPasswordField(subject);

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get()).isTrue();
        assertThat(CredentialResolver.hasSavedTokenRecord("OPENAI_API_KEY")).isFalse();
        assertThat(String.valueOf(tokenField.getPassword())).isEqualTo("env-token");
        assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
    }

    @Test
    @DisplayName("API token field saves dirty value when focus is lost")
    void focusLost_whenTokenDirty_savesToken() throws Exception {
        var saved = new CountDownLatch(1);
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                saved::countDown
        ));
        JPasswordField tokenField = findPasswordField(subject);

        onEdt(() -> {
            tokenField.setText("saved-on-blur");
            fireFocusLost(tokenField);
            return null;
        });

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(CredentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", null)).isEqualTo("saved-on-blur");
    }


    @Test
    @DisplayName("API token field enables FlatLaf in-field paste, clear, and reveal affordances")
    void constructor_enablesFlatLafInFieldTokenAffordances() throws Exception {
        LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
        Object originalRevealSetting = UIManager.get("PasswordField.showRevealButton");
        Object originalPasteIcon = UIManager.get("Actions.PasteIcon");
        Icon pasteIcon = new ImageIcon(new byte[] { 1 });
        UIManager.setLookAndFeel(new FlatLightLaf());
        UIManager.put("PasswordField.showRevealButton", Boolean.FALSE);
        UIManager.put("Actions.PasteIcon", pasteIcon);
        try {
            ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel("OPENAI_API_KEY", new ApiTokenFieldRegistry(), null, null));
            try {
                JPasswordField tokenField = findPasswordField(subject);
                assertThat(UIManager.get("PasswordField.showRevealButton")).isEqualTo(Boolean.FALSE);
                assertThat(tokenField.getClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON))
                        .isEqualTo(Boolean.TRUE);
                assertThat(tokenField.getClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT))
                        .isInstanceOf(JButton.class)
                        .satisfies(component -> {
                            JButton button = (JButton) component;
                            assertThat(button.getToolTipText()).isEqualTo("Paste API token");
                            assertThat(button.getIcon()).isSameAs(pasteIcon);
                        });
                assertThat(countChildrenNamed(tokenField, "PasswordField.revealButton")).isEqualTo(1);

                onEdt(() -> {
                    SwingUtilities.updateComponentTreeUI(subject);
                    return null;
                });

                assertThat(countChildrenNamed(tokenField, "PasswordField.revealButton")).isEqualTo(1);
                assertThat(countChildrenNamed(tokenField, "TextField.clearButton")).isEqualTo(1);
            } finally {
                onEdt(() -> {
                    subject.removeNotify();
                    return null;
                });
            }
        } finally {
            UIManager.put("PasswordField.showRevealButton", originalRevealSetting);
            UIManager.put("Actions.PasteIcon", originalPasteIcon);
            UIManager.setLookAndFeel(originalLookAndFeel);
        }
    }

    @Test
    @DisplayName("API token field reports pre-invalidation callback failures instead of continuing")
    void savePendingChangesAsync_whenPreInvalidationCallbackFails_completesFalse() throws Exception {
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                () -> {
                    throw new IllegalStateException("cancel failed");
                },
                () -> {
                }
        ));
        JPasswordField tokenField = findPasswordField(subject);

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(subject.lastSaveError()).isEqualTo("cancel failed");
        assertThat(subject.dirty()).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
    }

    @Test
    @DisplayName("API token field retries invalidation after a saved token deletion callback fails")
    void savePendingChangesAsync_whenDeletionInvalidationFails_retriesCredentialChange() throws Exception {
        char[] savedToken = "saved-token".toCharArray();
        try {
            CredentialResolver.saveTokenOverride("OPENAI_API_KEY", savedToken);
        } finally {
            Arrays.fill(savedToken, '\0');
        }
        var invalidations = new AtomicInteger();
        var refreshes = new AtomicInteger();
        var peerRefreshes = new AtomicInteger();
        var registry = new ApiTokenFieldRegistry();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                change -> {
                    if (invalidations.incrementAndGet() == 1) {
                        throw new IllegalStateException("invalidation failed");
                    }
                },
                refreshes::incrementAndGet
        ));
        onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                null,
                peerRefreshes::incrementAndGet
        ));
        JPasswordField tokenField = findPasswordField(subject);

        CompletableFuture<Boolean> firstSave = onEdt(() -> {
            tokenField.setText("");
            return subject.savePendingChangesAsync();
        });

        assertThat(firstSave.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(subject.lastSaveError()).isEqualTo("invalidation failed");
        assertThat(subject.dirty()).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        assertThat(refreshes).hasValue(0);
        assertThat(peerRefreshes).hasValue(0);

        CompletableFuture<Boolean> retry = onEdt(subject::savePendingChangesAsync);

        assertThat(retry.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(invalidations).hasValue(2);
        assertThat(refreshes).hasValue(1);
        assertThat(peerRefreshes).hasValue(1);
        assertThat(subject.dirty()).isFalse();
    }

    @Test
    @DisplayName("API token field reports callback failures instead of leaving saves pending")
    void savePendingChangesAsync_whenRefreshCallbackFails_completesFalse() throws Exception {
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                () -> {
                    throw new IllegalStateException("refresh failed");
                }
        ));
        JPasswordField tokenField = findPasswordField(subject);

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(subject.lastSaveError()).isEqualTo("refresh failed");
        assertThat(subject.dirty()).isTrue();
    }

    @Test
    @DisplayName("API token field does not recreate the vault when confirmation is cancelled")
    void requestVaultRecreation_whenConfirmationCancelled_doesNotRecreateVault() throws Exception {
        makeVaultCorrupt();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                null,
                () -> false
        ));
        assertThat(findRecreateVaultLink(subject).isVisible()).isTrue();

        CompletableFuture<Boolean> result = onEdt(subject::requestVaultRecreation);

        assertThat(result.get()).isFalse();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Vault recreation reports pre-invalidation callback failures instead of recreating")
    void requestVaultRecreation_whenPreInvalidationCallbackFails_doesNotRecreateVault() throws Exception {
        makeVaultCorrupt();
        var registry = new ApiTokenFieldRegistry();
        ApiTokenFieldPanel source = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                null,
                () -> {
                },
                () -> true
        ));
        onEdt(() -> new ApiTokenFieldPanel(
                "ANTHROPIC_API_KEY",
                registry,
                null,
                () -> {
                    throw new IllegalStateException("cancel failed");
                },
                () -> {
                }
        ));
        assertThat(findRecreateVaultLink(source).isVisible()).isTrue();

        CompletableFuture<Boolean> result = onEdt(source::requestVaultRecreation);

        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(source.lastSaveError()).isEqualTo("cancel failed");
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("API token field broadcasts credential changes after vault recreation")
    void requestVaultRecreation_whenConfirmed_notifiesAllCredentialBackedProviders() throws Exception {
        makeVaultCorrupt();
        var changes = new CopyOnWriteArrayList<ApiTokenChange>();
        var refreshes = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                changes::add,
                refreshes::incrementAndGet,
                () -> true
        ));
        assertThat(findRecreateVaultLink(subject).isVisible()).isTrue();

        CompletableFuture<Boolean> result = onEdt(subject::requestVaultRecreation);

        assertThat(result.get()).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        assertThat(refreshes).hasValue(1);
        assertThat(changes)
                .extracting(ApiTokenChange::canonicalTokenId)
                .containsAll(CredentialResolver.supportedProviderEnvVars());
        assertThat(changes)
                .allSatisfy(change -> assertThat(change)
                        .extracting(ApiTokenChange::cleared, ApiTokenChange::savedOverride)
                        .containsExactly(true, false));
    }

    @Test
    @DisplayName("Vault recreation keeps token controls disabled until credential notifications finish")
    void requestVaultRecreation_whenListenerIsSlow_keepsControlsDisabledUntilRefreshCompletes() throws Exception {
        makeVaultCorrupt();
        var listenerRanOnEdt = new AtomicReference<Boolean>();
        var listenerCalls = new AtomicInteger();
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var refreshes = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change -> {
                    listenerRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    if (listenerCalls.incrementAndGet() == 1) {
                        listenerStarted.countDown();
                        try {
                            releaseListener.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                },
                refreshes::incrementAndGet,
                () -> true
        ));
        assertThat(findRecreateVaultLink(subject).isVisible()).isTrue();

        CompletableFuture<Boolean> recreation = onEdt(subject::requestVaultRecreation);

        assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerRanOnEdt.get()).isFalse();
        assertThat(recreation).isNotDone();
        assertThat(refreshes).hasValue(0);
        onEdt(() -> {
            assertTokenControlsEnabled(subject, false);
            return null;
        });

        releaseListener.countDown();

        assertThat(recreation.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(refreshes).hasValue(1);
        onEdt(() -> {
            assertTokenControlsEnabled(subject, true);
            return null;
        });
    }

    @Test
    @DisplayName("Vault recreation refreshes clean peer token fields")
    void requestVaultRecreation_whenConfirmed_refreshesCleanPeerFields() throws Exception {
        makeVaultCorrupt();
        var registry = new ApiTokenFieldRegistry();
        var sourceRefreshes = new AtomicInteger();
        var peerRefreshes = new AtomicInteger();
        ApiTokenFieldPanel source = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                null,
                sourceRefreshes::incrementAndGet,
                () -> true
        ));
        ApiTokenFieldPanel peer = onEdt(() -> new ApiTokenFieldPanel(
                "ANTHROPIC_API_KEY",
                registry,
                null,
                peerRefreshes::incrementAndGet
        ));
        assertThat(findRecreateVaultLink(source).isVisible()).isTrue();
        assertThat(findPasswordField(peer).isEnabled()).isTrue();

        CompletableFuture<Boolean> result = onEdt(source::requestVaultRecreation);

        assertThat(result.get()).isTrue();
        assertThat(sourceRefreshes).hasValue(1);
        assertThat(peerRefreshes).hasValue(1);
    }

    @Test
    @DisplayName("Saving a token refreshes clean peer fields for the same token id")
    void savePendingChangesAsync_whenSameTokenPeerIsClean_refreshesPeerUi() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        var peerRefreshes = new AtomicInteger();
        ApiTokenFieldPanel source = onEdt(() -> new ApiTokenFieldPanel("OPENAI_API_KEY", registry, null, null));
        ApiTokenFieldPanel peer = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                null,
                peerRefreshes::incrementAndGet
        ));
        JPasswordField sourceField = findPasswordField(source);
        assertThat(sourceField.isEnabled()).isTrue();
        assertThat(findPasswordField(peer).isEnabled()).isTrue();

        CompletableFuture<Boolean> save = onEdt(() -> {
            sourceField.setText("saved-token");
            return source.savePendingChangesAsync();
        });

        assertThat(save.get()).isTrue();
        assertThat(peerRefreshes).hasValue(1);
    }

    @Test
    @DisplayName("Unregistered token fields no longer participate in conflict checks")
    void unregisterFromRegistry_whenFieldRemoved_excludesFieldFromConflicts() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        ApiTokenFieldPanel removed = onEdt(() -> new ApiTokenFieldPanel("OPENAI_API_KEY", registry, null, null));
        ApiTokenFieldPanel active = onEdt(() -> new ApiTokenFieldPanel("OPENAI_API_KEY", registry, null, null));
        JPasswordField removedField = findPasswordField(removed);
        JPasswordField activeField = findPasswordField(active);

        onEdt(() -> {
            removedField.setText("removed-token");
            activeField.setText("active-token");
            removed.unregisterFromRegistry();
            return null;
        });

        assertThat(registry.conflictMessage(active)).isBlank();
    }

    @Test
    @DisplayName("API token field rejects conflicting dirty values for the same token id")
    void savePendingChangesAsync_whenSameTokenHasConflictingDirtyValues_returnsFalse() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        String envVar = "OPENAI_API_KEY";
        ApiTokenFieldPanel first = onEdt(() -> new ApiTokenFieldPanel(envVar, registry, null, null));
        ApiTokenFieldPanel second = onEdt(() -> new ApiTokenFieldPanel(envVar, registry, null, null));
        JPasswordField firstField = findPasswordField(first);
        JPasswordField secondField = findPasswordField(second);
        assertThat(firstField.isEnabled()).isTrue();
        assertThat(secondField.isEnabled()).isTrue();

        CompletableFuture<Boolean> save = onEdt(() -> {
            firstField.setText("first-token");
            secondField.setText("second-token");
            return first.savePendingChangesAsync();
        });

        assertThat(save.get()).isFalse();
        assertThat(first.lastSaveError()).contains("Another settings tab has unsaved changes for OPENAI_API_KEY");
    }

    private void makeVaultCorrupt() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "not-json");
        CredentialResolver.configureTokenVault(new ApiTokenVault(storagePaths));
    }

    private static JPasswordField findPasswordField(ApiTokenFieldPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JPasswordField passwordField) {
                return passwordField;
            }
        }
        throw new AssertionError("No JPasswordField found");
    }

    private static void assertTokenControlsEnabled(ApiTokenFieldPanel panel, boolean enabled) {
        JPasswordField tokenField = findPasswordField(panel);
        assertThat(tokenField.isEnabled()).isEqualTo(enabled);
        assertThat(findPasteButton(tokenField).isEnabled()).isEqualTo(enabled);
        assertThat(findRecreateVaultLink(panel).isEnabled()).isEqualTo(enabled);
    }

    private static JButton findPasteButton(JPasswordField tokenField) {
        Object leadingComponent = tokenField.getClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT);
        assertThat(leadingComponent).isInstanceOf(JButton.class);
        return (JButton) leadingComponent;
    }

    private static JLabel findRecreateVaultLink(ApiTokenFieldPanel panel) {
        return findLabel(panel, "Recreate token vault…");
    }

    private static JLabel findStatusLabel(ApiTokenFieldPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof java.awt.Container container) {
                for (Component child : container.getComponents()) {
                    if (child instanceof JLabel label && !"Recreate token vault…".equals(label.getText())) {
                        return label;
                    }
                }
            }
        }
        throw new AssertionError("No status label found");
    }

    private static void fireFocusLost(JPasswordField tokenField) {
        for (java.awt.event.FocusListener listener : tokenField.getFocusListeners()) {
            listener.focusLost(new java.awt.event.FocusEvent(tokenField, java.awt.event.FocusEvent.FOCUS_LOST));
        }
    }

    private static long countChildrenNamed(Component component, String name) {
        long self = name.equals(component.getName()) ? 1 : 0;
        if (!(component instanceof java.awt.Container container)) {
            return self;
        }
        long children = Arrays.stream(container.getComponents())
                .mapToLong(child -> countChildrenNamed(child, name))
                .sum();
        return self + children;
    }

    private static JLabel findLabel(ApiTokenFieldPanel panel, String text) {
        for (Component component : panel.getComponents()) {
            if (component instanceof java.awt.Container container) {
                for (Component child : container.getComponents()) {
                    if (child instanceof JLabel label && text.equals(label.getText())) {
                        return label;
                    }
                }
            }
        }
        throw new AssertionError("No label found: " + text);
    }

    private static <T> T onEdt(ThrowingSupplier<T> supplier) throws InvocationTargetException, InterruptedException {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(supplier.get());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
