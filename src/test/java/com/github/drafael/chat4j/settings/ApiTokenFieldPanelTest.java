package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CredentialTestSupport;
import com.github.drafael.chat4j.provider.support.CredentialTokenIds;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFieldPanelTest {

    private static final int TOKEN_BYTE_LIMIT = 64 * 1024;

    @TempDir
    private Path tempDir;

    private final CopyOnWriteArrayList<ApiTokenFieldPanel> panels = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        CredentialTestSupport.configureVault(StoragePaths.ofConfigHome(tempDir));
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {
            panels.forEach(ApiTokenFieldPanel::removeNotify);
            panels.clear();
            return null;
        });
        onEdt(() -> null);
        CredentialTestSupport.reset();
    }

    @Test
    @DisplayName("API token field saves entered token and notifies listeners")
    void savePendingChangesAsync_whenTokenEntered_savesTokenAndNotifiesListener() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        var change = new AtomicReference<String>();
        var refreshes = new AtomicInteger();
        String envVar = "OPENAI_API_KEY";
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                envVar,
                registry,
                change::set,
                refreshes::incrementAndGet
        ));
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));
        onEdt(() -> {
            assertThat(tokenField.isEnabled()).isTrue();
            return null;
        });

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get()).isTrue();
        assertThat(CredentialResolver.resolveRequiredApiKey(envVar, null)).isEqualTo("saved-token");
        assertThat(CredentialResolver.resolveCredentialStatus(envVar, null).source())
                .isEqualTo(ApiCredentialSource.SAVED_TOKEN);
        onEdt(() -> {
            assertThat(subject.dirty()).isFalse();
            assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
            return null;
        });
        assertThat(change.get()).isEqualTo(envVar);
        assertThat(refreshes).hasValue(1);
    }

    @Test
    @DisplayName("Oversized token validation fails without stranding controls or completion")
    void savePendingChangesAsync_whenTokenExceedsLimit_completesFalseAndReenablesControls() throws Exception {
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                null,
                null
        ));
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));
        char[] oversized = new char[TOKEN_BYTE_LIMIT + 1];
        fill(oversized, 'a');

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText(new String(oversized));
            return subject.savePendingChangesAsync();
        });
        fill(oversized, '\0');

        assertThat(save.get(2, TimeUnit.SECONDS)).isFalse();
        onEdt(() -> {
            assertTokenControlsEnabled(subject, true);
            assertThat(subject.lastSaveError()).isEqualTo("API token exceeds the 64 KiB limit.");
            assertThat(subject.dirty()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Repeated save requests share the current credential mutation")
    void savePendingChangesAsync_whenSaveIsInFlight_returnsExistingFuture() throws Exception {
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var listenerCalls = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change -> {
                    listenerCalls.incrementAndGet();
                    listenerStarted.countDown();
                    try {
                        releaseListener.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                },
                null
        ));
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));
        CompletableFuture<Boolean> first = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });
        try {
            assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> second = onEdt(subject::savePendingChangesAsync);

            assertThat(second).isSameAs(first);
            assertThat(listenerCalls).hasValue(1);
        } finally {
            releaseListener.countDown();
        }
        assertThat(first.get(2, TimeUnit.SECONDS)).isTrue();
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

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
    @DisplayName("Removed token fields skip local completion and refresh active peers")
    void savePendingChangesAsync_whenFieldIsRemoved_refreshesActivePeers() throws Exception {
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var sourceRefreshes = new AtomicInteger();
        var peerRefreshes = new AtomicInteger();
        var registry = new ApiTokenFieldRegistry();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                change -> {
                    listenerStarted.countDown();
                    try {
                        releaseListener.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                },
                sourceRefreshes::incrementAndGet
        ));
        onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                null,
                peerRefreshes::incrementAndGet
        ));
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));
        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });
        try {
            assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            onEdt(() -> {
                subject.removeNotify();
                assertThat(tokenField.getPassword()).isEmpty();
                return null;
            });
        } finally {
            releaseListener.countDown();
        }

        assertThat(save.get(2, TimeUnit.SECONDS)).isTrue();
        onEdt(() -> null);
        assertThat(sourceRefreshes).hasValue(0);
        assertThat(peerRefreshes).hasValue(1);
        onEdt(() -> {
            subject.addNotify();
            assertTokenControlsEnabled(subject, true);
            assertThat(subject.dirty()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Re-added token fields reconcile queued save completion")
    void savePendingChangesAsync_whenFieldIsRemovedAndReadded_restoresCurrentUi() throws Exception {
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var refreshes = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change -> {
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));
        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });
        try {
            assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            onEdt(() -> {
                subject.removeNotify();
                subject.addNotify();
                return null;
            });
        } finally {
            releaseListener.countDown();
        }

        assertThat(save.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(refreshes).hasValue(1);
        onEdt(() -> {
            assertTokenControlsEnabled(subject, true);
            assertThat(subject.dirty()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("API token field clears saved override when blank dirty field loses focus")
    void focusLost_whenBlankAndSavedTokenExists_deletesOverrideAndNotifiesListener() throws Exception {
        char[] savedToken = "saved-token".toCharArray();
        try {
            CredentialTestSupport.saveToken("OPENAI_API_KEY", savedToken);
        } finally {
            fill(savedToken, '\0');
        }
        var change = new AtomicReference<String>();
        var saved = new CountDownLatch(1);
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                change::set,
                saved::countDown
        ));
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

        onEdt(() -> {
            tokenField.setText("");
            fireFocusLost(tokenField);
            return null;
        });

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        onEdt(() -> {
            assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
            return null;
        });
        assertThat(change.get()).isEqualTo("OPENAI_API_KEY");
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get()).isTrue();
        assertThat(CredentialResolver.hasSavedTokenRecord("OPENAI_API_KEY")).isFalse();
        onEdt(() -> {
            assertThat(String.valueOf(tokenField.getPassword())).isEqualTo("env-token");
            assertThat(findStatusLabel(subject).getParent().isVisible()).isFalse();
            return null;
        });
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

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
        onEdt(() -> {
            LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
            Object originalRevealSetting = UIManager.get("PasswordField.showRevealButton");
            Object originalPasteIcon = UIManager.get("Actions.PasteIcon");
            Icon pasteIcon = new ImageIcon(new byte[] { 1 });
            try {
                UIManager.setLookAndFeel(new FlatLightLaf());
                UIManager.put("PasswordField.showRevealButton", Boolean.FALSE);
                UIManager.put("Actions.PasteIcon", pasteIcon);
                ApiTokenFieldPanel subject = new ApiTokenFieldPanel(
                        "OPENAI_API_KEY",
                        new ApiTokenFieldRegistry(),
                        null,
                        null
                );
                panels.addIfAbsent(subject);
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

                SwingUtilities.updateComponentTreeUI(subject);

                assertThat(countChildrenNamed(tokenField, "PasswordField.revealButton")).isEqualTo(1);
                assertThat(countChildrenNamed(tokenField, "TextField.clearButton")).isEqualTo(1);
            } finally {
                UIManager.put("PasswordField.showRevealButton", originalRevealSetting);
                UIManager.put("Actions.PasteIcon", originalPasteIcon);
                UIManager.setLookAndFeel(originalLookAndFeel);
            }
            return null;
        });
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get(2, TimeUnit.SECONDS)).isFalse();
        onEdt(() -> {
            assertThat(subject.lastSaveError()).isEqualTo("cancel failed");
            assertThat(subject.dirty()).isTrue();
            return null;
        });
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
    }

    @Test
    @DisplayName("API token field reports invalidation failure without retaining an applied deletion")
    void savePendingChangesAsync_whenDeletionInvalidationFails_doesNotRetryAppliedChange() throws Exception {
        char[] savedToken = "saved-token".toCharArray();
        try {
            CredentialTestSupport.saveToken("OPENAI_API_KEY", savedToken);
        } finally {
            fill(savedToken, '\0');
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

        CompletableFuture<Boolean> firstSave = onEdt(() -> {
            tokenField.setText("");
            return subject.savePendingChangesAsync();
        });

        assertThat(firstSave.get(2, TimeUnit.SECONDS)).isTrue();
        onEdt(() -> {
            assertThat(subject.lastSaveError())
                    .isEqualTo("Credential change completed, but dependent refresh failed.");
            assertThat(subject.dirty()).isFalse();
            return null;
        });
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        assertThat(invalidations).hasValue(1);
        assertThat(refreshes).hasValue(1);
        assertThat(peerRefreshes).hasValue(1);

        CompletableFuture<Boolean> secondSave = onEdt(subject::savePendingChangesAsync);

        assertThat(secondSave.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(invalidations).hasValue(1);
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
        JPasswordField tokenField = onEdt(() -> findPasswordField(subject));

        CompletableFuture<Boolean> save = onEdt(() -> {
            tokenField.setText("saved-token");
            return subject.savePendingChangesAsync();
        });

        assertThat(save.get(2, TimeUnit.SECONDS)).isFalse();
        onEdt(() -> {
            assertThat(subject.lastSaveError()).isEqualTo("refresh failed");
            assertThat(subject.dirty()).isFalse();
            return null;
        });
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
        assertRecreateVaultLinkVisible(subject);

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
        assertRecreateVaultLinkVisible(source);

        CompletableFuture<Boolean> result = onEdt(source::requestVaultRecreation);

        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
        onEdt(() -> {
            assertThat(source.lastSaveError()).isEqualTo("cancel failed");
            return null;
        });
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("API token field broadcasts credential changes after vault recreation")
    void requestVaultRecreation_whenConfirmed_notifiesAllCredentialBackedProviders() throws Exception {
        makeVaultCorrupt();
        var changes = new CopyOnWriteArrayList<String>();
        var refreshes = new AtomicInteger();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                new ApiTokenFieldRegistry(),
                changes::add,
                refreshes::incrementAndGet,
                () -> true
        ));
        assertRecreateVaultLinkVisible(subject);

        CompletableFuture<Boolean> result = onEdt(subject::requestVaultRecreation);

        assertThat(result.get()).isTrue();
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.MISSING);
        assertThat(refreshes).hasValue(1);
        assertThat(changes)
                .containsExactlyInAnyOrderElementsOf(CredentialTokenIds.supportedCanonicalTokenIds());
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
        assertRecreateVaultLinkVisible(subject);

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
    @DisplayName("Removed token fields propagate completed vault recreation to active peers")
    void requestVaultRecreation_whenFieldIsRemoved_refreshesActivePeers() throws Exception {
        makeVaultCorrupt();
        var listenerStarted = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        var listenerCalls = new AtomicInteger();
        var sourceRefreshes = new AtomicInteger();
        var peerRefreshes = new AtomicInteger();
        var registry = new ApiTokenFieldRegistry();
        ApiTokenFieldPanel subject = onEdt(() -> new ApiTokenFieldPanel(
                "OPENAI_API_KEY",
                registry,
                change -> {
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
                sourceRefreshes::incrementAndGet,
                () -> true
        ));
        onEdt(() -> new ApiTokenFieldPanel(
                "ANTHROPIC_API_KEY",
                registry,
                null,
                peerRefreshes::incrementAndGet
        ));
        assertRecreateVaultLinkVisible(subject);
        CompletableFuture<Boolean> recreation = onEdt(subject::requestVaultRecreation);
        try {
            assertThat(listenerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            onEdt(() -> {
                subject.removeNotify();
                return null;
            });
        } finally {
            releaseListener.countDown();
        }

        assertThat(recreation.get(2, TimeUnit.SECONDS)).isTrue();
        onEdt(() -> null);
        assertThat(sourceRefreshes).hasValue(0);
        assertThat(peerRefreshes).hasValue(1);
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
        assertRecreateVaultLinkVisible(source);
        assertPasswordFieldEnabled(peer);

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
        JPasswordField sourceField = onEdt(() -> findPasswordField(source));
        assertPasswordFieldEnabled(source);
        assertPasswordFieldEnabled(peer);

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
        JPasswordField removedField = onEdt(() -> findPasswordField(removed));
        JPasswordField activeField = onEdt(() -> findPasswordField(active));

        onEdt(() -> {
            removedField.setText("removed-token");
            activeField.setText("active-token");
            removed.unregisterFromRegistry();
            return null;
        });

        onEdt(() -> {
            assertThat(registry.conflictMessage(active)).isBlank();
            return null;
        });
    }

    @Test
    @DisplayName("API token field rejects conflicting dirty values for the same token id")
    void savePendingChangesAsync_whenSameTokenHasConflictingDirtyValues_returnsFalse() throws Exception {
        var registry = new ApiTokenFieldRegistry();
        String envVar = "OPENAI_API_KEY";
        ApiTokenFieldPanel first = onEdt(() -> new ApiTokenFieldPanel(envVar, registry, null, null));
        ApiTokenFieldPanel second = onEdt(() -> new ApiTokenFieldPanel(envVar, registry, null, null));
        JPasswordField firstField = onEdt(() -> findPasswordField(first));
        JPasswordField secondField = onEdt(() -> findPasswordField(second));
        assertPasswordFieldEnabled(first);
        assertPasswordFieldEnabled(second);

        CompletableFuture<Boolean> save = onEdt(() -> {
            firstField.setText("first-token");
            secondField.setText("second-token");
            return first.savePendingChangesAsync();
        });

        assertThat(save.get()).isFalse();
        onEdt(() -> {
            assertThat(first.lastSaveError())
                    .contains("Another settings tab has unsaved changes for OPENAI_API_KEY");
            return null;
        });
    }

    private void makeVaultCorrupt() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir);
        Files.createDirectories(storagePaths.secretsDirectory());
        Files.writeString(storagePaths.tokenVaultFile(), "not-json");
        CredentialTestSupport.configureVault(storagePaths);
    }

    private void assertRecreateVaultLinkVisible(ApiTokenFieldPanel panel) throws Exception {
        onEdt(() -> {
            assertThat(findRecreateVaultLink(panel).isVisible()).isTrue();
            return null;
        });
    }

    private void assertPasswordFieldEnabled(ApiTokenFieldPanel panel) throws Exception {
        onEdt(() -> {
            assertThat(findPasswordField(panel).isEnabled()).isTrue();
            return null;
        });
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
        long children = stream(container.getComponents())
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

    private <T> T onEdt(ThrowingSupplier<T> supplier) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return supplier.get();
        }
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(supplier.get());
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
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        T value = result.get();
        if (value instanceof ApiTokenFieldPanel panel) {
            panels.addIfAbsent(panel);
        }
        return value;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
