package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComboBox;
import javax.swing.LookAndFeel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AppearancePanelTest {

    @TempDir
    Path tempDir;

    private Font originalDefaultFont;
    private Font originalMonospacedFont;
    private LookAndFeel originalLookAndFeel;

    @BeforeEach
    void setUp() throws Exception {
        runOnEdt(() -> {
            originalDefaultFont = UIManager.getFont("defaultFont");
            originalMonospacedFont = UIManager.getFont("monospaced.font");
            originalLookAndFeel = UIManager.getLookAndFeel();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        callOnEdt(() -> {
            if (originalLookAndFeel != null) {
                UIManager.setLookAndFeel(originalLookAndFeel);
            }
            UIManager.put("defaultFont", originalDefaultFont);
            UIManager.put("monospaced.font", originalMonospacedFont);
            AppearancePanel.restoreAccentColor(settingsRepo("appearance-panel-reset-accent"));
            return null;
        });
        flushEdt();
    }

    @Test
    @DisplayName("Saved fonts are applied when all repository reads succeed")
    void applySavedFonts_whenReadsSucceed_appliesSavedAppAndCodeFonts() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-fonts-success");
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, Font.SERIF);
        settingsRepo.put(FontSettings.APP_FONT_SIZE_KEY, "16");
        settingsRepo.put(FontSettings.CODE_FONT_FAMILY_KEY, Font.DIALOG_INPUT);

        runOnEdt(() -> AppearancePanel.applySavedFonts(settingsRepo));

        assertThat(callOnEdt(() -> UIManager.getFont("defaultFont").getFamily())).isEqualTo(Font.SERIF);
        assertThat(callOnEdt(() -> UIManager.getFont("defaultFont").getSize())).isEqualTo(16);
        assertThat(callOnEdt(() -> UIManager.getFont("monospaced.font").getFamily())).isEqualTo(Font.DIALOG_INPUT);
    }

    @Test
    @DisplayName("Saved fonts fall back as a unit when any repository read fails")
    void applySavedFonts_whenAnyReadFails_fallsBackAsUnit() throws Exception {
        int fallbackSize = AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize());
        int savedSize = differentFontSize(fallbackSize);

        runOnEdt(() -> AppearancePanel.applySavedFonts(new ThrowingFontSettingsRepo(
                tempDir.resolve("appearance-panel-font-read-failure.properties"),
                savedSize
        )));

        assertThat(callOnEdt(() -> UIManager.getFont("defaultFont").getSize())).isEqualTo(fallbackSize);
    }

    @Test
    @DisplayName("Restore accent color falls back to default when stored hex is invalid")
    void restoreAccentColor_whenStoredHexIsInvalid_clearsAccentColor() {
        var settingsRepo = settingsRepo("appearance-panel-accent-invalid");
        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "#007AFF");
        AppearancePanel.restoreAccentColor(settingsRepo);
        assertThat(AppearancePanel.currentAccentColor()).isEqualTo(Color.decode("#007AFF"));

        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "not-a-color");
        AppearancePanel.restoreAccentColor(settingsRepo);

        assertThat(AppearancePanel.currentAccentColor()).isNull();
    }

    @Test
    @DisplayName("Restore accent color falls back to default when repository access fails")
    void restoreAccentColor_whenRepositoryFails_clearsAccentColor() {
        var settingsRepo = settingsRepo("appearance-panel-accent-read-failure");
        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "#007AFF");
        AppearancePanel.restoreAccentColor(settingsRepo);
        assertThat(AppearancePanel.currentAccentColor()).isNotNull();

        AppearancePanel.restoreAccentColor(new ThrowingSettingsRepo(
                tempDir.resolve("appearance-panel-read-failure.properties")
        ));

        assertThat(AppearancePanel.currentAccentColor()).isNull();
    }

    @Test
    @DisplayName("Unsupported saved accents are reset to the selected default")
    void constructor_whenStoredAccentIsUnsupported_repairsSettingAndSelectsDefault() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-invalid-accent");
        settingsRepo.put(ThemeSettings.THEME_ACCENT_KEY, "not-a-supported-accent");
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.completedFuture(fallbackFontCatalog())
        ));
        try {
            JToggleButton defaultAccent = callOnEdt(
                    () -> findFirstComponentByType(subject, JToggleButton.class)
            );

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(ThemeSettings.THEME_ACCENT_KEY)).isEmpty();
            assertThat(callOnEdt(defaultAccent::isSelected)).isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Accent selection writes concrete colors and removes the default accent")
    void applyAccentSelection_whenColorSelected_writesOrRemovesAccentKey() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-accent-selection");
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(settingsRepo, systemRuntimeStatus()));
        try {
            runOnEdt(() -> subject.applyAccentSelection(Color.decode("#007AFF"), "#007AFF"));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(ThemeSettings.THEME_ACCENT_KEY)).contains("#007AFF");

            runOnEdt(() -> subject.applyAccentSelection(null, null));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(ThemeSettings.THEME_ACCENT_KEY)).isEmpty();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Accent persistence failures use a user-facing setting name")
    void applyAccentSelection_whenWriteFails_reportsFriendlyTarget() throws Exception {
        var settingsRepo = new FailingAccentSettingsRepo(
                tempDir.resolve("appearance-panel-accent-failure.properties")
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.completedFuture(fallbackFontCatalog())
        ));
        try {
            runOnEdt(() -> subject.applyAccentSelection(Color.BLUE, "#0000FF"));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save accent color setting")
                    .doesNotContain(ThemeSettings.THEME_ACCENT_KEY);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Canceling a WebView engine change restores the active engine")
    void webViewEngineSelection_whenRestartPromptCanceled_restoresActiveEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-cancel");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        var promptMessage = new AtomicReference<String>();
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(settingsRepo, systemRuntimeStatus(),
                () -> exitCalled.set(true), (parent, message) -> {
                    promptMessage.set(message);
                    return RestartRequiredDialog.Choice.CANCEL;
                }));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class));
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.SYSTEM.settingValue());
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.SYSTEM.settingValue());
            assertThat(exitCalled).isFalse();
            assertThat(promptMessage.get()).contains("System WebView", "Chromium Embedded Framework");
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("Cancel restores the immediately previous WebView intent after an older write settles")
    void webViewEngineSelection_whenOlderWriteSettlesLater_cancelsToPreviousVisibleIntent() throws Exception {
        var settingsRepo = new BlockingWebViewSettingsRepo(
                tempDir.resolve("appearance-panel-webview-stale-write-cancel.properties")
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.CANCEL,
                () -> CompletableFuture.completedFuture(fallbackFontCatalog())
        ));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class)
            );
            runOnEdt(() -> {
                engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue());
                engineComboBox.setSelectedItem(WebViewEngine.JEDITOR_PANE.settingValue());
            });
            assertThat(settingsRepo.writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            settingsRepo.releaseWrite.countDown();

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.JCEF.settingValue());
        } finally {
            settingsRepo.releaseWrite.countDown();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Cancel restores the visible WebView engine while its failed rollback remains pending")
    void webViewEngineSelection_whenCancelWriteFails_restoresVisibleIntentAndRetries() throws Exception {
        var settingsRepo = new FailingNthWebViewSettingsRepo(
                tempDir.resolve("appearance-panel-webview-cancel-retry.properties"),
                2
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.CANCEL
        ));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class)
            );
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.SYSTEM.settingValue());
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());

            settingsRepo.failWrites = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.SYSTEM.settingValue());
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Canceling a WebView engine change during fallback preserves the configured engine")
    void webViewEngineSelection_whenFallbackPromptCanceled_restoresConfiguredEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-fallback-cancel");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var promptCount = new AtomicInteger();
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(settingsRepo, fallbackRuntimeStatus(), () -> {
        }, (parent, message) -> {
            promptCount.incrementAndGet();
            return RestartRequiredDialog.Choice.CANCEL;
        }));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class));
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.SYSTEM.settingValue());
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.SYSTEM.settingValue());
            assertThat(promptCount).hasValue(1);
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("A failed WebView selection remains visible and retries the same intent")
    void savePendingChangesAsync_whenWebViewWriteFailed_retriesVisibleSelection() throws Exception {
        var settingsRepo = new RetryingWebViewSettingsRepo(
                tempDir.resolve("appearance-panel-webview-retry.properties")
        );
        settingsRepo.failWrites = false;
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        settingsRepo.failWrites = true;
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER
        ));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class)
            );
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.JCEF.settingValue());
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save WebView engine setting")
                    .doesNotContain(WebViewSettings.ENGINE_KEY);

            settingsRepo.failWrites = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A newer app-font size save supersedes a failed family-and-size batch")
    void savePendingChangesAsync_whenFontPairChangesAgain_doesNotRetryObsoletePair() throws Exception {
        var settingsRepo = new ToggleFontBatchFailureSettingsRepo(
                tempDir.resolve("appearance-panel-font-pair-retry.properties")
        );
        AppearancePanel.FontCatalog catalog = new AppearancePanel.FontCatalog(
                new String[]{FontSettings.DEFAULT_APP_FONT, Font.SERIF},
                new String[]{FontSettings.DEFAULT_CODE_FONT}
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.completedFuture(catalog)
        ));
        try {
            flushEdt();
            JComboBox<?> appFont = callOnEdt(
                    () -> findComponentByName(subject, "appFontComboBox", JComboBox.class)
            );
            JComboBox<?> appFontSize = callOnEdt(
                    () -> findComponentByName(subject, "appFontSizeComboBox", JComboBox.class)
            );
            runOnEdt(() -> appFont.setSelectedItem(Font.SERIF));
            assertThat(awaitSave(subject)).isFalse();

            settingsRepo.failFontBatches = false;
            String newerSize = String.valueOf(differentFontSize(AppearancePanel.defaultAppFontSize()));
            runOnEdt(() -> appFontSize.setSelectedItem(newerSize));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(awaitSave(subject)).isTrue();

            assertThat(settingsRepo.get(FontSettings.APP_FONT_FAMILY_KEY)).contains(Font.SERIF);
            assertThat(settingsRepo.get(FontSettings.APP_FONT_SIZE_KEY)).contains(newerSize);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Unrelated theme work cannot apply a font selection whose persistence failed")
    void applyAccentSelection_whenFontWriteIsPendingFailure_keepsDurableFontApplied() throws Exception {
        var settingsRepo = new ToggleFontBatchFailureSettingsRepo(
                tempDir.resolve("appearance-panel-failed-font-application.properties")
        );
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, Font.MONOSPACED);
        AppearancePanel.FontCatalog catalog = new AppearancePanel.FontCatalog(
                new String[]{Font.MONOSPACED, Font.SERIF},
                new String[]{FontSettings.DEFAULT_CODE_FONT}
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.completedFuture(catalog)
        ));
        try {
            flushEdt();
            JComboBox<?> appFont = callOnEdt(
                    () -> findComponentByName(subject, "appFontComboBox", JComboBox.class)
            );
            runOnEdt(() -> appFont.setSelectedItem(Font.SERIF));
            assertThat(awaitSave(subject)).isFalse();

            JComboBox<?> theme = callOnEdt(
                    () -> findComponentByName(subject, "themeComboBox", JComboBox.class)
            );
            runOnEdt(() -> theme.setSelectedItem("FlatLaf Dark"));
            assertThat(awaitSave(subject)).isFalse();

            assertThat(settingsRepo.get(FontSettings.APP_FONT_FAMILY_KEY)).contains(Font.MONOSPACED);
            assertThat(callOnEdt(() -> UIManager.getFont("defaultFont").getFamily())).isEqualTo(Font.MONOSPACED);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Failed font discovery leaves fallback selectors usable")
    void constructor_whenFontDiscoveryFails_enablesFallbackSelectors() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-font-discovery-failure");
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, Font.SERIF);
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.failedFuture(new IllegalStateException("forced failure"))
        ));
        try {
            flushEdt();
            JComboBox<?> appFont = callOnEdt(
                    () -> findComponentByName(subject, "appFontComboBox", JComboBox.class)
            );

            assertThat(callOnEdt(appFont::isEnabled)).isTrue();
            assertThat(callOnEdt(() -> appFont.getClientProperty("fontCatalogLoading"))).isEqualTo(false);

            runOnEdt(() -> appFont.setSelectedItem(FontSettings.DEFAULT_APP_FONT));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(FontSettings.APP_FONT_FAMILY_KEY)).contains(FontSettings.DEFAULT_APP_FONT);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Font discovery completion cannot update a permanently disposed panel")
    void disposePanel_whenFontDiscoveryCompletesLater_suppressesUiCompletion() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-late-font-discovery");
        var fontCatalog = new CompletableFuture<AppearancePanel.FontCatalog>();
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> fontCatalog
        ));
        JComboBox<?> appFont = callOnEdt(
                () -> findComponentByName(subject, "appFontComboBox", JComboBox.class)
        );
        try {
            assertThat(callOnEdt(appFont::isEnabled)).isFalse();
            runOnEdt(subject::disposePanel);

            fontCatalog.complete(new AppearancePanel.FontCatalog(
                    new String[]{FontSettings.DEFAULT_APP_FONT, Font.SERIF},
                    new String[]{FontSettings.DEFAULT_CODE_FONT}
            ));
            flushEdt();

            assertThat(callOnEdt(appFont::isEnabled)).isFalse();
            assertThat(callOnEdt(() -> appFont.getClientProperty("fontCatalogLoading"))).isEqualTo(true);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Appearance persistence remains off the event dispatch thread")
    void webViewEngineSelection_whenWriteBlocks_keepsEdtResponsive() throws Exception {
        var settingsRepo = new BlockingWebViewSettingsRepo(
                tempDir.resolve("appearance-panel-blocking-write.properties")
        );
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> RestartRequiredDialog.Choice.LATER,
                () -> CompletableFuture.completedFuture(fallbackFontCatalog())
        ));
        try {
            flushEdt();
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class)
            );
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));
            assertThat(settingsRepo.writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var sentinelRan = new AtomicBoolean();
            runOnEdt(() -> sentinelRan.set(true));
            assertThat(sentinelRan).isTrue();

            settingsRepo.releaseWrite.countDown();
            assertThat(awaitSave(subject)).isTrue();
        } finally {
            settingsRepo.releaseWrite.countDown();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A failing restart callback does not turn a durable Appearance write into a failed save")
    void webViewEngineSelection_whenRestartCallbackFails_reportsFollowUpFailure() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-restart-callback-failure");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var promptCalls = new AtomicInteger();
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(
                settingsRepo,
                systemRuntimeStatus(),
                () -> {
                },
                (parent, message) -> {
                    if (promptCalls.incrementAndGet() == 1) {
                        throw new IllegalStateException("forced callback failure");
                    }
                    return RestartRequiredDialog.Choice.CANCEL;
                },
                () -> CompletableFuture.completedFuture(fallbackFontCatalog())
        ));
        try {
            flushEdt();
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class)
            );
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("WebView engine was saved, but the follow-up action failed")
                    .doesNotContain(WebViewSettings.ENGINE_KEY);

            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JEDITOR_PANE.settingValue()));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.JCEF.settingValue());
            assertThat(promptCalls).hasValue(2);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Choosing later keeps the WebView engine change for the next launch")
    void webViewEngineSelection_whenRestartPromptDeferred_keepsSelectedEngine() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-later");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(settingsRepo, systemRuntimeStatus(), () -> {
        }, (parent, message) -> RestartRequiredDialog.Choice.LATER));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class));
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
            assertThat(callOnEdt(engineComboBox::getSelectedItem)).isEqualTo(WebViewEngine.JCEF.settingValue());
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("Choosing exit now saves the WebView engine change and exits")
    void webViewEngineSelection_whenRestartPromptAccepted_savesSelectedEngineAndRunsExitAction() throws Exception {
        var settingsRepo = settingsRepo("appearance-panel-webview-exit-now");
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var exitCalled = new AtomicBoolean(false);
        AppearancePanel subject = callOnEdt(() -> new AppearancePanel(settingsRepo, systemRuntimeStatus(),
                () -> exitCalled.set(true), (parent, message) -> RestartRequiredDialog.Choice.EXIT_NOW));
        try {
            JComboBox<?> engineComboBox = callOnEdt(
                    () -> findComponentByName(subject, "chatWebViewEngineComboBox", JComboBox.class));
            runOnEdt(() -> engineComboBox.setSelectedItem(WebViewEngine.JCEF.settingValue()));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get(WebViewSettings.ENGINE_KEY)).contains(WebViewEngine.JCEF.settingValue());
            assertThat(exitCalled).isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    private boolean awaitSave(AppearancePanel subject) throws Exception {
        boolean saved = callOnEdt(subject::savePendingChangesAsync).get(20, TimeUnit.SECONDS);
        flushEdt();
        return saved;
    }

    private int differentFontSize(int fallbackSize) {
        return java.util.Arrays.stream(AppearancePanel.appFontSizeOptions())
                .filter(size -> size != fallbackSize).findFirst().orElse(fallbackSize + 4);
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private WebViewRuntimeStatus systemRuntimeStatus() {
        return new WebViewRuntimeStatus(WebViewEngine.SYSTEM, WebViewEngine.SYSTEM, true, "HEAVYWEIGHT", true,
                "Windowed/native", "");
    }

    private WebViewRuntimeStatus fallbackRuntimeStatus() {
        return new WebViewRuntimeStatus(WebViewEngine.SYSTEM, WebViewEngine.JEDITOR_PANE, false, "Unavailable", false,
                "Unavailable", "System WebView unavailable: missing runtime");
    }

    private AppearancePanel.FontCatalog fallbackFontCatalog() {
        return new AppearancePanel.FontCatalog(
                new String[]{FontSettings.DEFAULT_APP_FONT},
                new String[]{FontSettings.DEFAULT_CODE_FONT}
        );
    }

    private <T extends Component> T findComponentByName(Container root, String name, Class<T> type) {
        T found = findComponentByNameOrNull(root, name, type);
        assertThat(found).isNotNull();
        return found;
    }

    private <T extends Component> T findFirstComponentByType(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                T found = findFirstComponentByType(container, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T extends Component> T findComponentByNameOrNull(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                T found = findComponentByNameOrNull(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        return value.get();
    }

    private static final class FailingAccentSettingsRepo extends SettingsRepository {
        private FailingAccentSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (ThemeSettings.THEME_ACCENT_KEY.equals(key)) {
                throw new IllegalStateException("forced failure");
            }
            super.put(key, value);
        }
    }

    private static final class BlockingWebViewSettingsRepo extends SettingsRepository {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);

        private BlockingWebViewSettingsRepo(Path settingsFile) {
            super(settingsFile);
            super.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        }

        @Override
        public void put(String key, String value) {
            if (WebViewSettings.ENGINE_KEY.equals(key)) {
                writeStarted.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release WebView settings write");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to release WebView settings write", e);
                }
            }
            super.put(key, value);
        }
    }

    private static final class ToggleFontBatchFailureSettingsRepo extends SettingsRepository {
        private volatile boolean failFontBatches = true;

        private ToggleFontBatchFailureSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void updateBatch(Consumer<BatchUpdate> updates) {
            if (failFontBatches) {
                throw new IllegalStateException("forced failure");
            }
            super.updateBatch(updates);
        }
    }

    private static final class FailingNthWebViewSettingsRepo extends SettingsRepository {
        private final AtomicInteger engineWrites = new AtomicInteger();
        private final int failedWrite;
        private volatile boolean failWrites = true;

        private FailingNthWebViewSettingsRepo(Path settingsFile, int failedWrite) {
            super(settingsFile);
            this.failedWrite = failedWrite;
            super.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        }

        @Override
        public void put(String key, String value) {
            if (WebViewSettings.ENGINE_KEY.equals(key)
                    && failWrites
                    && engineWrites.incrementAndGet() >= failedWrite
            ) {
                throw new IllegalStateException("forced failure");
            }
            super.put(key, value);
        }
    }

    private static final class RetryingWebViewSettingsRepo extends SettingsRepository {
        private volatile boolean failWrites;

        private RetryingWebViewSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (failWrites && WebViewSettings.ENGINE_KEY.equals(key)) {
                throw new IllegalStateException("forced failure");
            }
            super.put(key, value);
        }
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {
        private ThrowingSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }

    private static class ThrowingFontSettingsRepo extends SettingsRepository {
        private final int savedSize;

        private ThrowingFontSettingsRepo(Path settingsFile, int savedSize) {
            super(settingsFile);
            this.savedSize = savedSize;
        }

        @Override
        public String get(String key, String defaultValue) {
            if (FontSettings.APP_FONT_FAMILY_KEY.equals(key)) {
                return Font.SERIF;
            }
            if (FontSettings.APP_FONT_SIZE_KEY.equals(key)) {
                return String.valueOf(savedSize);
            }
            if (FontSettings.CODE_FONT_FAMILY_KEY.equals(key)) {
                throw new IllegalStateException("forced failure");
            }
            return defaultValue;
        }
    }
}
