package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import com.github.drafael.chat4j.chat.export.pdf.PdfPageFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefMessageRouter;
import org.cef.misc.CefPdfPrintSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JcefBrowserViewTest {

    @Test
    @DisplayName("Programmatic internal Chat4J document navigation is allowed")
    void navigationDecision_whenProgrammaticInternalUrl_returnsAllow() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
    }

    @Test
    @DisplayName("User-gesture internal Chat4J document navigation is blocked")
    void navigationDecision_whenUserGestureInternalUrl_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Safe user links open externally")
    void navigationDecision_whenSafeUserLink_returnsOpenExternal() {
        assertThat(JcefBrowserView.navigationDecision("https://example.com/path", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
    }

    @Test
    @DisplayName("Safe non-user external navigation is blocked")
    void navigationDecision_whenSafeExternalNavigationWithoutUserGesture_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("https://example.com/path", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Unsafe user links are blocked")
    void navigationDecision_whenUnsafeUserLink_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("javascript:alert(1)", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("file:///Users/example/secrets.txt", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("data:text/html;base64,PHNjcmlwdD4=", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Safe mail links open externally only for user gestures")
    void navigationDecision_whenMailtoLink_returnsExternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("mailto:hello@example.com", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
        assertThat(JcefBrowserView.navigationDecision("mailto:hello@example.com", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Programmatic internal asset navigation is allowed")
    void navigationDecision_whenProgrammaticInternalAssetUrl_returnsAllow() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local/assets/mermaid/mermaid.min.js", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
    }

    @Test
    @DisplayName("Internal URL matching ignores scheme and host case")
    void navigationDecision_whenInternalUrlHasDifferentCase_returnsInternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("HTTPS://CHAT4J.LOCAL/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.ALLOW);
        assertThat(JcefBrowserView.navigationDecision("HTTPS://CHAT4J.LOCAL/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Lookalike internal hosts are not treated as internal")
    void navigationDecision_whenInternalHostLookalike_returnsExternalPolicy() {
        assertThat(JcefBrowserView.navigationDecision("https://chat4j.local.evil.example/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.OPEN_EXTERNAL);
        assertThat(JcefBrowserView.navigationDecision("http://chat4j.local/transcript/page.html", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Blank malformed and relative URLs are blocked")
    void navigationDecision_whenUrlIsBlankMalformedOrRelative_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("not a url", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
        assertThat(JcefBrowserView.navigationDecision("/transcript/page.html", true))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Unsafe non-user navigation is blocked")
    void navigationDecision_whenUnsafeNavigationWithoutUserGesture_returnsBlock() {
        assertThat(JcefBrowserView.navigationDecision("data:text/html;base64,PHNjcmlwdD4=", false))
                .isEqualTo(JcefBrowserView.NavigationDecision.BLOCK);
    }

    @Test
    @DisplayName("Releasing transcript focus clears Chromium keyboard focus")
    void releaseFocus_whenBrowserIsActive_clearsNativeKeyboardFocus() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        CefBrowser browser = mock(CefBrowser.class);
        try {
            runOnEdt(() -> {
                setField(subject, "browser", browser);
                subject.releaseFocus();
            });

            verify(browser).setFocus(false);
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("PDF preparation waits for rendered diagrams and verifies the durable turn shape")
    void pdfPreparationScript_whenBuilt_requiresSettledDiagramsAndMatchingTurns() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        try {
            String script = callOnEdt(() -> subject.pdfPreparationScript(
                    7L,
                    "Conversation",
                    "Provider · model",
                    List.of(
                            new JcefBrowserView.PdfTurnMetadata("user", "You", "10:00 AM", "first"),
                            new JcefBrowserView.PdfTurnMetadata("assistant", "Assistant", "10:01 AM", "second")
                    ),
                    Map.of("/managed/image.png", "https://chat4j.local/pdf-image/7/0"),
                    PdfPageFormat.US_LETTER
            ));

            assertThat(script)
                    .contains("window.chat4jRenderDiagrams(document)")
                    .contains("@page { size: Letter portrait; }")
                    .contains("chat4j-pdf-page-format")
                    .contains("rows.length !== turns.length")
                    .contains("state === 'pending'")
                    .contains("type: 'pdf-export-ready'")
                    .contains("notify(false)")
                    .contains("Provider · model")
                    .contains("parsed.protocol !== 'http:'")
                    .contains("https://chat4j.local/pdf-image/7/0")
                    .contains("__chat4jPdfOriginalLinks")
                    .contains("__chat4jPdfOriginalImages")
                    .doesNotContain("outerHTML")
                    .doesNotContain("XMLSerializer");
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("Chromium print settings use US Letter dimensions when selected")
    void pdfPrintSettings_whenUsLetterSelected_usesLetterDimensions() {
        CefPdfPrintSettings settings = JcefBrowserView.pdfPrintSettings(PdfPageFormat.US_LETTER);

        assertThat(settings.paper_width).isCloseTo(8.5, within(0.001));
        assertThat(settings.paper_height).isCloseTo(11.0, within(0.001));
        assertThat(settings.landscape).isFalse();
        assertThat(settings.prefer_css_page_size).isTrue();
    }

    @Test
    @DisplayName("Transcript render admission remains pending until Chromium acknowledges the DOM revision")
    void handleBridgeQuery_whenTranscriptRevisionIsApplied_clearsPendingRevision() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        try {
            runOnEdt(() -> {
                setField(subject, "pendingTranscriptRenderRequestId", 11L);
                Method method = JcefBrowserView.class.getDeclaredMethod("handleBridgeQuery", String.class);
                method.setAccessible(true);
                method.invoke(subject, "{\"type\":\"transcript-revision-applied\",\"args\":[11]}");

                assertThat(fieldValue(subject, "pendingTranscriptRenderRequestId")).isEqualTo(0L);
            });
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("Disposed JCEF views ignore late transcript bridge callbacks")
    void handleBridgeQuery_whenViewIsDisposed_ignoresTranscriptAction() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        var actionCalled = new AtomicBoolean();
        runOnEdt(() -> {
            subject.setActionListener((action, messageIndex, text) -> actionCalled.set(true));
            subject.dispose();
            Method method = JcefBrowserView.class.getDeclaredMethod("handleBridgeQuery", String.class);
            method.setAccessible(true);
            method.invoke(subject, "{\"type\":\"transcript-action\",\"args\":[\"copy\",0,\"text\"]}");
        });

        assertThat(actionCalled).isFalse();
    }

    @Test
    @DisplayName("A failed JCEF document load releases its retained HTML")
    void handleDocumentLoadError_whenActiveDocumentFails_removesRetainedHtml() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        String documentUrl = "https://chat4j.local/transcript/failed.html";
        try {
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                Map<String, String> htmlByUrl = (Map<String, String>) fieldValue(subject, "htmlByUrl");
                AtomicLong requestCounter = (AtomicLong) fieldValue(subject, "renderRequestCounter");
                requestCounter.set(7L);
                htmlByUrl.put(documentUrl, "<html></html>");
                setField(subject, "loadingDocumentRequestId", 7L);
                setField(subject, "loadingDocumentUrl", documentUrl);
                setField(subject, "currentDocumentUrl", documentUrl);
                setField(subject, "documentLoadPending", true);

                Method method = JcefBrowserView.class.getDeclaredMethod("handleDocumentLoadError", String.class);
                method.setAccessible(true);
                method.invoke(subject, documentUrl);

                assertThat(htmlByUrl).doesNotContainKey(documentUrl);
                assertThat(fieldValue(subject, "currentDocumentUrl")).isEqualTo("");
                assertThat(fieldValue(subject, "documentLoadPending")).isEqualTo(false);
            });
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("A failed replacement discards superseded documents after a prior document loaded")
    void handleDocumentLoadError_whenPriorDocumentLoaded_retainsOnlyLoadedDocument() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        String loadedUrl = "https://chat4j.local/transcript/loaded.html";
        String supersededUrl = "https://chat4j.local/transcript/superseded.html";
        String failedUrl = "https://chat4j.local/transcript/failed.html";
        try {
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                Map<String, String> htmlByUrl = (Map<String, String>) fieldValue(subject, "htmlByUrl");
                AtomicLong requestCounter = (AtomicLong) fieldValue(subject, "renderRequestCounter");
                requestCounter.set(9L);
                htmlByUrl.put(loadedUrl, "<html>loaded</html>");
                htmlByUrl.put(supersededUrl, "<html>superseded</html>");
                htmlByUrl.put(failedUrl, "<html>failed</html>");
                setField(subject, "loadedDocumentUrl", loadedUrl);
                setField(subject, "currentDocumentUrl", failedUrl);
                setField(subject, "loadingDocumentRequestId", 9L);
                setField(subject, "loadingDocumentUrl", failedUrl);

                invoke(subject, "handleDocumentLoadError", failedUrl);

                assertThat(htmlByUrl).containsOnlyKeys(loadedUrl);
                assertThat(fieldValue(subject, "currentDocumentUrl")).isEqualTo(loadedUrl);
            });
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("Superseded JCEF documents remain available until the replacement finishes loading")
    void replaceCurrentDocumentUrl_whenReplacementIsStillLoading_retainsBothDocuments() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        String firstUrl = "https://chat4j.local/transcript/first.html";
        String secondUrl = "https://chat4j.local/transcript/second.html";
        try {
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                Map<String, String> htmlByUrl = (Map<String, String>) fieldValue(subject, "htmlByUrl");
                htmlByUrl.put(firstUrl, "<html>first</html>");
                htmlByUrl.put(secondUrl, "<html>second</html>");

                invoke(subject, "replaceCurrentDocumentUrl", firstUrl);
                invoke(subject, "replaceCurrentDocumentUrl", secondUrl);

                assertThat(htmlByUrl).containsKeys(firstUrl, secondUrl);

                invoke(subject, "retainOnlyDocumentUrl", secondUrl);

                assertThat(htmlByUrl).containsOnlyKeys(secondUrl);
            });
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("The latest document is loaded after the lazy native JCEF browser is created")
    void handleBrowserCreated_whenDocumentChangedDuringLazyCreation_loadsLatestDocument() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        CefBrowser browser = mock(CefBrowser.class);
        String initialUrl = "https://chat4j.local/transcript/initial.html";
        String latestUrl = "https://chat4j.local/transcript/latest.html";
        try {
            runOnEdt(() -> {
                setField(subject, "browser", browser);
                setField(subject, "initialBrowserUrl", initialUrl);
                setField(subject, "loadingDocumentUrl", latestUrl);
                setField(subject, "nativeBrowserCreated", false);

                Method loadUrl = JcefBrowserView.class.getDeclaredMethod("loadUrl", String.class);
                loadUrl.setAccessible(true);
                assertThat(loadUrl.invoke(subject, latestUrl)).isEqualTo(true);
                verify(browser, never()).loadURL(latestUrl);

                Method browserCreated = JcefBrowserView.class.getDeclaredMethod("handleBrowserCreated", CefBrowser.class);
                browserCreated.setAccessible(true);
                browserCreated.invoke(subject, browser);

                assertThat(fieldValue(subject, "nativeBrowserCreated")).isEqualTo(true);
                verify(browser).loadURL(latestUrl);
            });
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    @Test
    @DisplayName("A native cleanup failure does not skip the remaining JCEF resources")
    void dispose_whenNativeCleanupStepFails_releasesRemainingResources() throws Exception {
        JcefBrowserView subject = callOnEdt(JcefBrowserView::new);
        CefBrowser browser = mock(CefBrowser.class);
        CefClient client = mock(CefClient.class);
        CefMessageRouter router = mock(CefMessageRouter.class);
        doThrow(new InternalError("stop failed")).when(browser).stopLoad();
        when(browser.getUIComponent()).thenThrow(new IllegalStateException("component failed"));
        doThrow(new IllegalStateException("remove failed")).when(client).removeMessageRouter(router);
        setField(subject, "browser", browser);
        setField(subject, "cefClient", client);
        setField(subject, "messageRouter", router);

        try {
            runOnEdt(subject::dispose);

            verify(browser).setCloseAllowed();
            verify(browser).close(true);
            verify(router).dispose();
            verify(client).dispose();
        } finally {
            runOnEdt(subject::dispose);
        }
    }

    private void invoke(JcefBrowserView subject, String methodName, String value) throws Exception {
        Method method = JcefBrowserView.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        method.invoke(subject, value);
    }

    private void setField(JcefBrowserView subject, String name, Object value) throws Exception {
        Field field = JcefBrowserView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(subject, value);
    }

    private Object fieldValue(JcefBrowserView subject, String name) throws Exception {
        Field field = JcefBrowserView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(subject);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
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
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
