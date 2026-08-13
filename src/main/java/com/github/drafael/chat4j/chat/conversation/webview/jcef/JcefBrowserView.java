package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import com.github.drafael.chat4j.chat.conversation.ConversationActionListener;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.ConversationEntryKind;
import com.github.drafael.chat4j.chat.conversation.ConversationTurnFingerprint;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptAssetMode;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRenderer;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptDocumentRequest;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSnapshot;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptRenderSupport;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptUpdateScripts;
import com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptBrowserAssets;
import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.chat.export.pdf.PdfPageFormat;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.render.RenderMode;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.swing.*;
import lombok.NonNull;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.CefPdfPrintSettings;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

public final class JcefBrowserView {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MERMAID_SCRIPT_URL = "https://chat4j.local/assets/mermaid/mermaid.min.js";
    private static final String SMILES_DRAWER_SCRIPT_URL = "https://chat4j.local/assets/smilesdrawer/smiles-drawer.min.js";

    private final JPanel browserPanel = new JPanel(new BorderLayout());
    private final Map<String, String> htmlByUrl = new ConcurrentHashMap<>();
    private final Map<String, BinaryResource> pdfImageResources = new ConcurrentHashMap<>();
    private CefClient cefClient;
    private CefMessageRouter messageRouter;
    private CefBrowser browser;
    private final TranscriptDocumentRenderer transcriptDocumentRenderer = new TranscriptDocumentRenderer();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-transcript-render-", 0).factory()
    );
    private final AtomicLong renderRequestCounter = new AtomicLong();
    private final AtomicLong pdfExportCounter = new AtomicLong();
    private List<ConversationEntry> entries = emptyList();
    private RenderMode renderMode = RenderMode.PREVIEW;
    private boolean dark;
    private boolean jumpButtonVisible;
    private boolean readAloudAvailable;
    private Set<Integer> readAloudMessageIndexes = emptySet();
    private boolean pdfExportAvailable;
    private int activeReadAloudMessageIndex = -1;
    private boolean documentInitialized;
    private boolean documentLoadPending;
    private String pendingDocumentUrl = "";
    private long pendingDocumentRequestId;
    private boolean pendingDocumentScrollToBottom;
    private long loadingDocumentRequestId;
    private boolean loadingDocumentScrollToBottom;
    private String loadingDocumentUrl = "";
    private String currentDocumentUrl = "";
    private String loadedDocumentUrl = "";
    private String initialBrowserUrl = "";
    private boolean nativeBrowserCreated;
    private volatile boolean disposed;
    private volatile long pendingTranscriptRenderRequestId;
    private volatile CompletableFuture<Void> transcriptSettlement = CompletableFuture.completedFuture(null);
    private volatile PendingPdfExport pendingPdfExport;
    @Setter
    private ConversationActionListener actionListener;

    public JcefBrowserView() {
        browserPanel.setPreferredSize(new Dimension(800, 600));
        browserPanel.setMinimumSize(new Dimension(320, 220));
        browserPanel.add(new JLabel("JCEF transcript not loaded", SwingConstants.CENTER), BorderLayout.CENTER);
        installBrowserResizeWorkaround();
        browserPanel.addHierarchyListener(event -> applyPendingDocumentUrl());
    }

    public JComponent component() {
        return browserPanel;
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible
    ) {
        setTranscript(entries, renderMode, dark, scrollToBottom, jumpButtonVisible, false);
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible,
            boolean readAloudAvailable
    ) {
        setTranscript(
                entries,
                renderMode,
                dark,
                scrollToBottom,
                jumpButtonVisible,
                readAloudAvailable,
                emptySet(),
                -1
        );
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible,
            boolean readAloudAvailable,
            int activeReadAloudMessageIndex
    ) {
        setTranscript(
                entries,
                renderMode,
                dark,
                scrollToBottom,
                jumpButtonVisible,
                readAloudAvailable,
                emptySet(),
                activeReadAloudMessageIndex
        );
    }

    public void setTranscript(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean scrollToBottom,
            boolean jumpButtonVisible,
            boolean readAloudAvailable,
            Set<Integer> readAloudMessageIndexes,
            int activeReadAloudMessageIndex
    ) {
        RenderMode nextRenderMode = renderMode == null ? RenderMode.PREVIEW : renderMode;
        boolean styleChanged = this.renderMode != nextRenderMode || this.dark != dark;
        boolean jumpButtonChanged = this.jumpButtonVisible != jumpButtonVisible;
        boolean readAloudChanged = this.readAloudAvailable != readAloudAvailable;
        Set<Integer> nextReadAloudMessageIndexes = readAloudMessageIndexes == null
                ? emptySet()
                : Set.copyOf(readAloudMessageIndexes);
        boolean readAloudMessagesChanged = !this.readAloudMessageIndexes.equals(nextReadAloudMessageIndexes);
        boolean activeReadAloudChanged = this.activeReadAloudMessageIndex != activeReadAloudMessageIndex;
        this.entries = List.copyOf(entries == null ? emptyList() : entries);
        this.renderMode = nextRenderMode;
        this.dark = dark;
        this.jumpButtonVisible = jumpButtonVisible;
        this.readAloudAvailable = readAloudAvailable;
        this.readAloudMessageIndexes = nextReadAloudMessageIndexes;
        this.activeReadAloudMessageIndex = activeReadAloudMessageIndex;

        if (jumpButtonChanged) {
            updateJumpButtonChrome();
            SwingUtilities.invokeLater(this::updateJumpButtonChrome);
        }
        if (styleChanged || readAloudChanged) {
            reload(scrollToBottom);
            return;
        }
        if (documentLoadPending) {
            if (readAloudMessagesChanged || activeReadAloudChanged) {
                reload(scrollToBottom);
            }
            return;
        }
        if (!documentInitialized) {
            reload(scrollToBottom);
            return;
        }

        scheduleTranscriptHtmlUpdate(scrollToBottom, transcriptRenderSnapshot());
    }

    public void reload(boolean scrollToBottom) {
        if (disposed) {
            return;
        }
        long requestId = renderRequestCounter.incrementAndGet();
        transcriptSettlement = new CompletableFuture<>();
        documentInitialized = false;
        documentLoadPending = true;
        TranscriptRenderSnapshot snapshot = transcriptRenderSnapshot();
        renderExecutor.execute(() -> {
            if (disposed || requestId != renderRequestCounter.get()) {
                return;
            }
            String document = injectJcefBridge(TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> renderDocument(scrollToBottom, snapshot)));
            if (disposed || requestId != renderRequestCounter.get()) {
                return;
            }
            String documentUrl = toDocumentUrl(document);
            SwingUtilities.invokeLater(() -> applyDocumentUrl(requestId, documentUrl, scrollToBottom));
        });
    }

    public void scrollToBottom() {
        executeJavaScript(TranscriptUpdateScripts.scrollToBottom());
    }

    public void setPdfExportAvailable(boolean available) {
        pdfExportAvailable = available;
        applyPdfExportAvailability();
    }

    private void applyPdfExportAvailability() {
        executeJavaScript("window.chat4jPdfExportAvailable = %s;".formatted(pdfExportAvailable));
    }

    public boolean canExportPdf() {
        return canAttemptPdfExport()
                && documentInitialized
                && !documentLoadPending
                && pendingTranscriptRenderRequestId == 0L;
    }

    public boolean canAttemptPdfExport() {
        return SwingUtilities.isEventDispatchThread()
                && !disposed
                && browser != null
                && renderMode == RenderMode.PREVIEW
                && browserPanel.isShowing();
    }

    public void printToPdf(
            @NonNull Path destination,
            String title,
            String metadata,
            @NonNull List<PdfTurnMetadata> turns,
            @NonNull List<AttachmentRef> imageReferences,
            @NonNull PdfPageFormat pageFormat,
            @NonNull BooleanSupplier cancelled
    ) throws Exception {
        if (!waitForPdfAdmission(turns, cancelled)) {
            return;
        }
        long requestId = pdfExportCounter.incrementAndGet();
        Path nativeOutput = Files.createTempFile(destination.toAbsolutePath().normalize().getParent(), ".chat4j-jcef-pdf-", ".pdf");
        Map<String, String> imageUrls = preparePdfImages(requestId, imageReferences);
        var pending = new PendingPdfExport(requestId, nativeOutput, pageFormat);
        boolean detachedNativeWrite = false;
        try {
            runOnEdt(() -> beginPdfExport(pending, title, metadata, turns, imageUrls));
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(130);
            while (!pending.completion().isDone()) {
                if (cancelled.getAsBoolean() && !pending.nativePrintScheduled()) {
                    runOnEdt(() -> abortPendingPdfExport(pending));
                }
                if (System.nanoTime() >= deadlineNanos) {
                    detachedNativeWrite = pending.nativePrintScheduled();
                    if (detachedNativeWrite) {
                        retainNativeOutputForLateCallback(pending, nativeOutput);
                    }
                    throw new IllegalStateException("Chromium PDF rendering timed out.");
                }
                try {
                    pending.completion().get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Recheck cancellation and the bounded deadline.
                } catch (InterruptedException e) {
                    detachedNativeWrite = pending.nativePrintScheduled();
                    if (detachedNativeWrite) {
                        retainNativeOutputForLateCallback(pending, nativeOutput);
                    } else {
                        runOnEdt(() -> abortPendingPdfExport(pending));
                    }
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
            boolean printed = pending.completion().join();
            if (cancelled.getAsBoolean()) {
                return;
            }
            if (!printed || !Files.isRegularFile(nativeOutput) || Files.size(nativeOutput) == 0) {
                throw new IllegalStateException("Chromium could not write the PDF document.");
            }
            Files.move(nativeOutput, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            cleanupPdfExportAfterWorker(pending);
            if (!detachedNativeWrite) {
                Files.deleteIfExists(nativeOutput);
            }
        }
    }

    private boolean waitForPdfAdmission(List<PdfTurnMetadata> turns, BooleanSupplier cancelled) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!cancelled.getAsBoolean() && System.nanoTime() < deadlineNanos) {
            CompletableFuture<Void> settlement = transcriptSettlement;
            var ready = new AtomicBoolean();
            runOnEdt(() -> ready.set(canExportPdf() && matchesDurableTurns(turns)));
            if (ready.get()) {
                return true;
            }
            if (settlement.isDone()) {
                break;
            }
            try {
                settlement.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Recheck the concrete browser revision and durable turn state.
            }
        }
        if (cancelled.getAsBoolean()) {
            return false;
        }
        throw new IllegalStateException("The active Chromium conversation did not become ready for PDF export.");
    }

    private void beginPdfExport(
            PendingPdfExport pending,
            String title,
            String metadata,
            List<PdfTurnMetadata> turns,
            Map<String, String> imageUrls
    ) {
        if (!canExportPdf() || pendingPdfExport != null || !matchesDurableTurns(turns)) {
            pending.completion().completeExceptionally(
                    new IllegalStateException("The active Chromium conversation is not ready for PDF export.")
            );
            return;
        }
        pendingPdfExport = pending;
        try {
            executeJavaScript(pdfPreparationScript(
                    pending.requestId(),
                    title,
                    metadata,
                    turns,
                    imageUrls,
                    pending.pageFormat()
            ));
        } catch (Exception e) {
            pending.completion().completeExceptionally(e);
        }
    }

    private boolean matchesDurableTurns(List<PdfTurnMetadata> expectedTurns) {
        List<ConversationEntry> messageEntries = entries.stream()
                .filter(entry -> entry.kind() == ConversationEntryKind.MESSAGE)
                .filter(entry -> entry.role() == Role.USER || entry.role() == Role.ASSISTANT)
                .toList();
        if (messageEntries.size() != expectedTurns.size()) {
            return false;
        }
        return IntStream.range(0, messageEntries.size()).allMatch(index -> {
            ConversationEntry entry = messageEntries.get(index);
            PdfTurnMetadata expected = expectedTurns.get(index);
            String fingerprint = ConversationTurnFingerprint.create(
                    entry.role(),
                    entry.parts(),
                    entry.meta().fallbackNotices(),
                    entry.meta().cancelled(),
                    entry.meta().error(),
                    entry.meta().assistantWebSearch(),
                    entry.meta().citations()
            );
            return Strings.CS.equals(fingerprint, expected.fingerprint());
        });
    }

    private Map<String, String> preparePdfImages(long requestId, List<AttachmentRef> references) {
        Map<String, String> urlsByPath = new LinkedHashMap<>();
        for (int index = 0; index < references.size(); index++) {
            AttachmentRef reference = references.get(index);
            if (reference == null || StringUtils.isBlank(reference.storagePath())) {
                continue;
            }
            try {
                Path path = Path.of(reference.storagePath());
                if (!Files.isRegularFile(path) || ImageIO.read(path.toFile()) == null) {
                    continue;
                }
                String url = "https://chat4j.local/pdf-image/%d/%d".formatted(requestId, index);
                String mimeType = safeImageMimeType(StringUtils.defaultIfBlank(reference.mimeType(), Files.probeContentType(path)));
                pdfImageResources.put(url, new BinaryResource(Files.readAllBytes(path), mimeType));
                urlsByPath.put(reference.storagePath(), url);
            } catch (Exception ignored) {
                // Keep the displayed image when the persisted original is unavailable.
            }
        }
        return Map.copyOf(urlsByPath);
    }

    private String safeImageMimeType(String mimeType) {
        return switch (StringUtils.defaultString(mimeType).trim().toLowerCase(Locale.ROOT)) {
            case "image/png" -> "image/png";
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            case "image/bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    private void startNativePdfPrint(PendingPdfExport pending) {
        if (pending != pendingPdfExport || pending.completion().isDone() || disposed || browser == null) {
            return;
        }
        CefPdfPrintSettings settings = pdfPrintSettings(pending.pageFormat());
        try {
            browser.printToPDF(
                    pending.nativeOutput().toString(),
                    settings,
                    (path, ok) -> {
                        if (!pending.completion().complete(ok)) {
                            deleteQuietly(pending.nativeOutput());
                        }
                    }
            );
            pending.markNativePrintStarted();
        } catch (Throwable t) {
            pending.completion().completeExceptionally(t);
        }
    }

    static CefPdfPrintSettings pdfPrintSettings(PdfPageFormat pageFormat) {
        CefPdfPrintSettings settings = new CefPdfPrintSettings();
        settings.landscape = false;
        settings.print_background = true;
        settings.scale = 1.0;
        settings.paper_width = pageFormat.widthInches();
        settings.paper_height = pageFormat.heightInches();
        settings.prefer_css_page_size = true;
        settings.margin_type = CefPdfPrintSettings.MarginType.NONE;
        settings.display_header_footer = false;
        settings.generate_tagged_pdf = true;
        settings.generate_document_outline = true;
        return settings;
    }

    private void abortPendingPdfExport(PendingPdfExport pending) {
        if (pending == pendingPdfExport && !pending.nativePrintScheduled()) {
            pending.completion().complete(false);
        }
    }

    private void cleanupPdfExport(PendingPdfExport pending) {
        if (pending == pendingPdfExport) {
            pendingPdfExport = null;
        }
        if (pending.nativePrintScheduled() && !pending.nativePrintStarted()) {
            pending.completion().complete(false);
        }
        pdfImageResources.keySet().removeIf(url -> url.contains("/pdf-image/%d/".formatted(pending.requestId())));
        executeJavaScript("""
                (function() {
                  var header = document.getElementById('chat4j-pdf-export-header');
                  if (header) { header.remove(); }
                  var pageStyle = document.getElementById('chat4j-pdf-page-format');
                  if (pageStyle) { pageStyle.remove(); }
                  Array.prototype.forEach.call(document.querySelectorAll('.chat4j-pdf-turn-heading'), function(node) { node.remove(); });
                  var links = window.__chat4jPdfOriginalLinks || [];
                  links.forEach(function(item) {
                    if (item.href === null) { item.node.removeAttribute('href'); }
                    else { item.node.setAttribute('href', item.href); }
                  });
                  delete window.__chat4jPdfOriginalLinks;
                  var images = window.__chat4jPdfOriginalImages || [];
                  images.forEach(function(item) {
                    if (item.src === null) { item.node.removeAttribute('src'); }
                    else { item.node.setAttribute('src', item.src); }
                  });
                  delete window.__chat4jPdfOriginalImages;
                })();
                """);
    }

    private void runOnEdt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        SwingUtilities.invokeAndWait(action);
    }

    private void cleanupPdfExportAfterWorker(PendingPdfExport pending) {
        if (SwingUtilities.isEventDispatchThread()) {
            cleanupPdfExport(pending);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> cleanupPdfExport(pending));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> cleanupPdfExport(pending));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> cleanupPdfExport(pending));
        }
    }

    private void retainNativeOutputForLateCallback(PendingPdfExport pending, Path nativeOutput) {
        nativeOutput.toFile().deleteOnExit();
        pending.completion().whenComplete((ignored, error) -> deleteQuietly(nativeOutput));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    public void dispose() {
        disposed = true;
        actionListener = null;
        PendingPdfExport pending = pendingPdfExport;
        pendingPdfExport = null;
        if (pending != null) {
            pending.completion().completeExceptionally(new IllegalStateException("JCEF view was disposed during PDF export."));
        }
        pdfImageResources.clear();
        transcriptSettlement.completeExceptionally(new IllegalStateException("JCEF view was disposed."));
        renderRequestCounter.incrementAndGet();
        renderExecutor.shutdownNow();
        deletePendingDocumentUrl();
        closeBrowser();
    }

    public boolean isDisposed() {
        return disposed;
    }

    private String renderDocument(boolean scrollToBottom, TranscriptRenderSnapshot snapshot) {
        return transcriptDocumentRenderer.renderDocument(new TranscriptDocumentRequest(
                scrollToBottom,
                snapshot,
                TranscriptAssetMode.INTERNAL_URL_FOR_LARGE_LIBRARIES,
                MERMAID_SCRIPT_URL,
                SMILES_DRAWER_SCRIPT_URL
        ));
    }

    private void updateJumpButtonChrome() {
        String script = TranscriptUpdateScripts.jumpButtonChrome(jumpButtonVisible);
        executeJavaScript(script);
    }

    private void applyDocumentUrl(long requestId, String documentUrl, boolean scrollToBottom) {
        if (disposed || requestId != renderRequestCounter.get()) {
            removeDocumentUrl(documentUrl);
            return;
        }
        if (!isBrowserPanelReadyForDocumentLoad()) {
            storePendingDocumentUrl(requestId, documentUrl, scrollToBottom);
            return;
        }

        pendingDocumentUrl = "";
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
        documentInitialized = false;
        documentLoadPending = true;
        loadingDocumentRequestId = requestId;
        loadingDocumentScrollToBottom = scrollToBottom;
        loadingDocumentUrl = documentUrl;
        if (!loadUrl(documentUrl)) {
            clearLoadingDocumentState(requestId, documentUrl);
            removeDocumentUrl(documentUrl);
            documentInitialized = false;
            documentLoadPending = false;
            transcriptSettlement.complete(null);
            return;
        }
        replaceCurrentDocumentUrl(documentUrl);
    }

    private void applyPendingDocumentUrl() {
        if (disposed || StringUtils.isBlank(pendingDocumentUrl) || !isBrowserPanelReadyForDocumentLoad()) {
            return;
        }
        applyDocumentUrl(pendingDocumentRequestId, pendingDocumentUrl, pendingDocumentScrollToBottom);
    }

    private boolean isBrowserPanelReadyForDocumentLoad() {
        return browserPanel.isShowing() && browserPanel.getWidth() > 0 && browserPanel.getHeight() > 0;
    }

    private void storePendingDocumentUrl(long requestId, String documentUrl, boolean scrollToBottom) {
        deletePendingDocumentUrl();
        pendingDocumentUrl = documentUrl;
        pendingDocumentRequestId = requestId;
        pendingDocumentScrollToBottom = scrollToBottom;
    }

    private void deletePendingDocumentUrl() {
        if (StringUtils.isNotBlank(pendingDocumentUrl)) {
            removeDocumentUrl(pendingDocumentUrl);
        }
        pendingDocumentUrl = "";
        pendingDocumentRequestId = 0L;
        pendingDocumentScrollToBottom = false;
    }

    private void removeDocumentUrl(String documentUrl) {
        htmlByUrl.remove(documentUrl);
    }

    private void replaceCurrentDocumentUrl(String nextUrl) {
        currentDocumentUrl = StringUtils.defaultString(nextUrl);
    }

    private void retainOnlyDocumentUrl(String retainedUrl) {
        htmlByUrl.keySet().removeIf(url -> !Strings.CS.equals(url, retainedUrl));
    }

    private void handleDocumentLoadEnd(String loadedUrl) {
        if (disposed || !isActiveLoadingDocument(loadedUrl)) {
            return;
        }
        long requestId = loadingDocumentRequestId;
        boolean scrollToBottom = loadingDocumentScrollToBottom;
        clearLoadingDocumentState(requestId, loadedUrl);
        loadedDocumentUrl = loadedUrl;
        retainOnlyDocumentUrl(loadedUrl);
        documentInitialized = true;
        documentLoadPending = false;
        scheduleTranscriptHtmlUpdate(scrollToBottom, transcriptRenderSnapshot());
    }

    private void handleDocumentLoadError(String failedUrl) {
        if (disposed || !isActiveLoadingDocument(failedUrl)) {
            return;
        }
        clearLoadingDocumentState(loadingDocumentRequestId, failedUrl);
        removeDocumentUrl(failedUrl);
        if (StringUtils.isNotBlank(loadedDocumentUrl)) {
            retainOnlyDocumentUrl(loadedDocumentUrl);
        }
        if (Strings.CS.equals(currentDocumentUrl, failedUrl)) {
            replaceCurrentDocumentUrl(loadedDocumentUrl);
        }
        documentInitialized = false;
        documentLoadPending = false;
        transcriptSettlement.complete(null);
    }

    private boolean isActiveLoadingDocument(String url) {
        return loadingDocumentRequestId == renderRequestCounter.get()
                && StringUtils.isNotBlank(loadingDocumentUrl)
                && Strings.CS.equals(loadingDocumentUrl, url);
    }

    private void clearLoadingDocumentState(long requestId, String url) {
        if (loadingDocumentRequestId != requestId || !Strings.CS.equals(loadingDocumentUrl, url)) {
            return;
        }
        loadingDocumentRequestId = 0L;
        loadingDocumentScrollToBottom = false;
        loadingDocumentUrl = "";
    }

    private void scheduleTranscriptHtmlUpdate(boolean scrollToBottom, TranscriptRenderSnapshot snapshot) {
        if (disposed) {
            return;
        }
        long requestId = renderRequestCounter.incrementAndGet();
        pendingTranscriptRenderRequestId = requestId;
        transcriptSettlement = new CompletableFuture<>();
        renderExecutor.execute(() -> {
            if (disposed || requestId != renderRequestCounter.get()) {
                clearPendingTranscriptRender(requestId);
                return;
            }
            String entriesHtml = TranscriptRenderSupport.withSnapshotFonts(snapshot, () -> renderEntriesHtml(snapshot, requestId));
            if (entriesHtml == null) {
                clearPendingTranscriptRender(requestId);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (disposed || requestId != renderRequestCounter.get() || !documentInitialized) {
                    clearPendingTranscriptRender(requestId);
                    return;
                }
                updateTranscriptHtml(requestId, scrollToBottom, snapshot, entriesHtml);
            });
        });
    }

    public void updateReadAloudChrome(int messageIndex, boolean active) {
        if (!disposed) {
            executeJavaScript(TranscriptUpdateScripts.readAloudChrome(messageIndex, active));
        }
    }

    private void clearPendingTranscriptRender(long requestId) {
        if (pendingTranscriptRenderRequestId == requestId) {
            pendingTranscriptRenderRequestId = 0L;
            transcriptSettlement.complete(null);
        }
    }

    private TranscriptRenderSnapshot transcriptRenderSnapshot() {
        return TranscriptRenderSupport.snapshot(
                entries,
                renderMode,
                dark,
                jumpButtonVisible,
                readAloudAvailable,
                readAloudMessageIndexes,
                activeReadAloudMessageIndex
        );
    }


    private void updateTranscriptHtml(long requestId, boolean scrollToBottom, TranscriptRenderSnapshot snapshot, String entriesHtml) {
        String script = TranscriptUpdateScripts.transcriptHtmlUpdate(
                encodeSupplementaryCodePoints(entriesHtml),
                snapshot.jumpButtonVisible(),
                scrollToBottom
        ) + """
                if (window.chat4jJcefQuery) {
                  window.chat4jJcefQuery({request: JSON.stringify({type: 'transcript-revision-applied', args: [%d]})});
                }
                """.formatted(requestId);
        executeJavaScript(script);
        applyPdfExportAvailability();
    }

    private String renderEntriesHtml(TranscriptRenderSnapshot snapshot, long requestId) {
        return transcriptDocumentRenderer.renderEntriesHtml(
                snapshot,
                () -> !disposed && requestId == renderRequestCounter.get()
        );
    }


    private String toDocumentUrl(String html) {
        String url = "https://chat4j.local/transcript/%s.html".formatted(UUID.randomUUID());
        htmlByUrl.put(url, encodeSupplementaryCodePoints(html));
        return url;
    }

    private String htmlForUrl(String url) {
        return htmlByUrl.get(url);
    }

    private static String scriptForUrl(String url) {
        if (Strings.CS.equals(url, MERMAID_SCRIPT_URL)) {
            return TranscriptBrowserAssets.mermaidScript();
        }
        if (Strings.CS.equals(url, SMILES_DRAWER_SCRIPT_URL)) {
            return TranscriptBrowserAssets.smilesDrawerScript();
        }
        return null;
    }

    static NavigationDecision navigationDecision(String url, boolean userGesture) {
        if (isInternalUrl(url)) {
            return userGesture ? NavigationDecision.BLOCK : NavigationDecision.ALLOW;
        }
        return userGesture && ExternalLinkSupport.isAllowedExternalLink(url)
                ? NavigationDecision.OPEN_EXTERNAL
                : NavigationDecision.BLOCK;
    }

    private static boolean isInternalUrl(String url) {
        try {
            URI uri = new URI(StringUtils.defaultString(url));
            return Strings.CI.equals(uri.getScheme(), "https") && Strings.CI.equals(uri.getHost(), "chat4j.local");
        } catch (Exception e) {
            return false;
        }
    }

    String pdfPreparationScript(
            long requestId,
            String title,
            String metadata,
            List<PdfTurnMetadata> turns,
            Map<String, String> imageUrls,
            PdfPageFormat pageFormat
    ) throws Exception {
        String titleJson = OBJECT_MAPPER.writeValueAsString(StringUtils.defaultIfBlank(title, "Conversation"));
        String metadataJson = OBJECT_MAPPER.writeValueAsString(StringUtils.defaultString(metadata));
        String turnsJson = OBJECT_MAPPER.writeValueAsString(turns);
        String imageUrlsJson = OBJECT_MAPPER.writeValueAsString(imageUrls);
        String pageSizeCssJson = OBJECT_MAPPER.writeValueAsString(
                "@page { size: %s portrait; }".formatted(pageFormat.cssPageSize())
        );
        return """
                (function() {
                  var requestId = %d;
                  function notify(ready) {
                    if (window.chat4jJcefQuery) {
                      window.chat4jJcefQuery({request: JSON.stringify({type: 'pdf-export-ready', args: [requestId, Boolean(ready)]})});
                    }
                  }
                  var existing = document.getElementById('chat4j-pdf-export-header');
                  if (existing) { existing.remove(); }
                  var existingPageStyle = document.getElementById('chat4j-pdf-page-format');
                  if (existingPageStyle) { existingPageStyle.remove(); }
                  var pageStyle = document.createElement('style');
                  pageStyle.id = 'chat4j-pdf-page-format';
                  pageStyle.textContent = %s;
                  document.head.appendChild(pageStyle);
                  Array.prototype.forEach.call(document.querySelectorAll('.chat4j-pdf-turn-heading'), function(node) { node.remove(); });

                  var rows = Array.prototype.slice.call(document.querySelectorAll('.row.user, .row.assistant'));
                  var turns = %s;
                  if (rows.length !== turns.length) {
                    notify(false);
                    return;
                  }
                  for (var index = 0; index < rows.length; index++) {
                    var turn = turns[index];
                    if (!rows[index].classList.contains(turn.role)) {
                      notify(false);
                      return;
                    }
                    var heading = document.createElement('div');
                    heading.className = 'chat4j-pdf-turn-heading';
                    heading.appendChild(document.createTextNode(turn.label));
                    if (turn.timestamp) {
                      var time = document.createElement('time');
                      time.textContent = turn.timestamp;
                      heading.appendChild(time);
                    }
                    rows[index].insertBefore(heading, rows[index].firstChild);
                  }

                  window.__chat4jPdfOriginalLinks = [];
                  Array.prototype.forEach.call(document.querySelectorAll('a[href]'), function(link) {
                    var original = link.getAttribute('href');
                    window.__chat4jPdfOriginalLinks.push({node: link, href: original});
                    try {
                      var parsed = new URL(String(original || '').trim(), document.baseURI);
                      if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname) {
                        link.removeAttribute('href');
                      }
                    } catch (ignored) {
                      link.removeAttribute('href');
                    }
                  });

                  window.__chat4jPdfOriginalImages = [];
                  var imageUrls = %s;
                  Array.prototype.forEach.call(document.querySelectorAll('[data-attachment-path]'), function(container) {
                    var image = container.querySelector('img');
                    var replacement = imageUrls[container.getAttribute('data-attachment-path')];
                    if (image && replacement) {
                      window.__chat4jPdfOriginalImages.push({node: image, src: image.hasAttribute('src') ? image.getAttribute('src') : null});
                      image.setAttribute('src', replacement);
                    }
                  });

                  var header = document.createElement('header');
                  header.id = 'chat4j-pdf-export-header';
                  header.className = 'chat4j-pdf-export-header';
                  var heading = document.createElement('h1');
                  heading.textContent = %s;
                  header.appendChild(heading);
                  var detail = document.createElement('div');
                  detail.textContent = %s;
                  header.appendChild(detail);
                  document.body.insertBefore(header, document.body.firstChild);

                  if (window.chat4jRenderDiagrams) {
                    window.chat4jRenderDiagrams(document);
                  }
                  var deadline = Date.now() + 10000;
                  function diagramState(table) {
                    var shell = table.parentNode && table.parentNode.classList && table.parentNode.classList.contains('code-block-shell')
                      ? table.parentNode
                      : null;
                    return (shell && shell.getAttribute('data-chat4j-diagram-rendered'))
                      || table.getAttribute('data-chat4j-diagram-rendered')
                      || '';
                  }
                  function settled() {
                    var diagrams = document.querySelectorAll('table.md-diagram-block');
                    for (var i = 0; i < diagrams.length; i++) {
                      var state = diagramState(diagrams[i]);
                      if (!state || state === 'pending') { return false; }
                    }
                    var images = document.images || [];
                    for (var j = 0; j < images.length; j++) {
                      if (!images[j].complete) { return false; }
                    }
                    return true;
                  }
                  function notifyWhenReady() {
                    if (settled()) {
                      notify(true);
                      return;
                    }
                    if (Date.now() >= deadline) {
                      notify(false);
                      return;
                    }
                    window.setTimeout(notifyWhenReady, 60);
                  }
                  var fontsReady = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
                  Promise.resolve(fontsReady).catch(function() {}).then(notifyWhenReady);
                })();
                """.formatted(requestId, pageSizeCssJson, turnsJson, imageUrlsJson, titleJson, metadataJson);
    }

    private String injectJcefBridge(String html) {
        return html.replace(
                "</head>",
                "<script>" + jcefCallbackScript() + TranscriptBrowserAssets.transcriptActionsScript() + "</script>\n</head>"
        );
    }

    private String jcefCallbackScript() {
        return """
                (function() {
                    window.chat4jOpenExternalLink = function(link) {
                        if (window.chat4jJcefQuery) {
                            window.chat4jJcefQuery({request: JSON.stringify({type: 'open-link', args: [String(link || '')]})});
                        }
                    };
                    window.chat4jTranscriptAction = function(action, messageIndex, text) {
                        if (!window.chat4jJcefQuery) {
                            return;
                        }
                        var args = null;
                        if (arguments.length === 1) {
                            try {
                                var payload = JSON.parse(String(action || ''));
                                if (payload && Array.isArray(payload.args)) {
                                    args = payload.args;
                                }
                            } catch (ignored) {
                            }
                        }
                        if (!args) {
                            var normalizedMessageIndex = Number(messageIndex);
                            if (!isFinite(normalizedMessageIndex)) {
                                normalizedMessageIndex = -1;
                            }
                            args = [String(action || ''), normalizedMessageIndex, String(text || '')];
                        }
                        window.chat4jJcefQuery({request: JSON.stringify({type: 'transcript-action', args: args})});
                    };
                })();
                """;
    }

    private boolean loadUrl(String url) {
        boolean createdBrowser = ensureBrowser(url);
        if (browser == null) {
            return false;
        }
        if (!createdBrowser && nativeBrowserCreated) {
            browser.loadURL(url);
        }
        return true;
    }

    private boolean ensureBrowser(String initialUrl) {
        if (browser != null || disposed) {
            return false;
        }
        try {
            cefClient = JcefRuntime.getInstance().createClient();
            messageRouter = createMessageRouter();
            cefClient.addMessageRouter(messageRouter);
            cefClient.addRequestHandler(new TranscriptRequestHandler());
            cefClient.addLoadHandler(new TranscriptLoadHandler());
            cefClient.addLifeSpanHandler(new TranscriptLifeSpanHandler());
            initialBrowserUrl = initialUrl;
            nativeBrowserCreated = false;
            browser = cefClient.createBrowser(initialUrl, false, false);
            Component browserComponent = browser.getUIComponent();
            browserComponent.setPreferredSize(new Dimension(800, 600));
            browserComponent.setMinimumSize(new Dimension(320, 220));
            browserPanel.removeAll();
            browserPanel.add(browserComponent, BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
            repairBrowserLayout();
            return true;
        } catch (Throwable t) {
            closeBrowser();
            browserPanel.removeAll();
            browserPanel.add(new JLabel("JCEF transcript failed to start", SwingConstants.CENTER), BorderLayout.CENTER);
            browserPanel.revalidate();
            browserPanel.repaint();
            return false;
        }
    }

    private CefMessageRouter createMessageRouter() {
        CefMessageRouter.CefMessageRouterConfig config = new CefMessageRouter.CefMessageRouterConfig(
                "chat4jJcefQuery",
                "chat4jJcefQueryCancel"
        );
        messageRouter = CefMessageRouter.create(config);
        messageRouter.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request, boolean persistent, CefQueryCallback callback) {
                handleBridgeQuery(request);
                callback.success("");
                return true;
            }
        }, true);
        return messageRouter;
    }

    private void handleBridgeQuery(String request) {
        if (disposed) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(StringUtils.defaultString(request));
            String type = node.path("type").asText("");
            JsonNode args = node.path("args");
            if (Strings.CS.equals(type, "transcript-revision-applied")) {
                long requestId = args.isArray() && !args.isEmpty() ? args.get(0).asLong(-1L) : -1L;
                clearPendingTranscriptRender(requestId);
                return;
            }
            if (Strings.CS.equals(type, "pdf-export-ready")) {
                long requestId = args.isArray() && !args.isEmpty() ? args.get(0).asLong(-1L) : -1L;
                boolean ready = args.isArray() && args.size() >= 2 && args.get(1).asBoolean(false);
                PendingPdfExport pending = pendingPdfExport;
                if (pending != null && pending.requestId() == requestId) {
                    if (ready) {
                        pending.markNativePrintScheduled();
                        SwingUtilities.invokeLater(() -> startNativePdfPrint(pending));
                    } else {
                        pending.completion().complete(false);
                    }
                }
                return;
            }
            if (Strings.CS.equals(type, "open-link")) {
                String link = args.isArray() && !args.isEmpty() ? args.get(0).asText("") : "";
                ExternalLinkSupport.openExternalLink(link);
                return;
            }
            if (Strings.CS.equals(type, "transcript-action") && actionListener != null && args.isArray() && args.size() >= 2) {
                actionListener.handle(
                        args.get(0).asText(""),
                        args.get(1).asInt(-1),
                        args.size() >= 3 ? args.get(2).asText("") : ""
                );
            }
        } catch (Exception ignored) {
            // Ignore malformed browser bridge payloads.
        }
    }

    private void executeJavaScript(String script) {
        if (browser == null || disposed) {
            return;
        }
        browser.executeJavaScript(script, browser.getURL(), 0);
    }

    public record PdfTurnMetadata(String role, String label, String timestamp, String fingerprint) {
        public PdfTurnMetadata {
            role = StringUtils.defaultString(role);
            label = StringUtils.defaultString(label);
            timestamp = StringUtils.defaultString(timestamp);
            fingerprint = StringUtils.defaultString(fingerprint);
        }
    }

    private static final class PendingPdfExport {
        private final long requestId;
        private final Path nativeOutput;
        private final PdfPageFormat pageFormat;
        private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        private volatile boolean nativePrintScheduled;
        private volatile boolean nativePrintStarted;

        private PendingPdfExport(long requestId, Path nativeOutput, PdfPageFormat pageFormat) {
            this.requestId = requestId;
            this.nativeOutput = nativeOutput.toAbsolutePath().normalize();
            this.pageFormat = pageFormat;
        }

        private long requestId() {
            return requestId;
        }

        private Path nativeOutput() {
            return nativeOutput;
        }

        private PdfPageFormat pageFormat() {
            return pageFormat;
        }

        private CompletableFuture<Boolean> completion() {
            return completion;
        }

        private boolean nativePrintScheduled() {
            return nativePrintScheduled;
        }

        private void markNativePrintScheduled() {
            nativePrintScheduled = true;
        }

        private boolean nativePrintStarted() {
            return nativePrintStarted;
        }

        private void markNativePrintStarted() {
            nativePrintStarted = true;
        }
    }

    private void installBrowserResizeWorkaround() {
        Timer repairTimer = new Timer(180, event -> repairBrowserLayout());
        repairTimer.setRepeats(false);
        browserPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyPendingDocumentUrl();
                repairTimer.restart();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                repairTimer.restart();
            }
        });
    }

    private void repairBrowserLayout() {
        if (browser == null || !browserPanel.isShowing()) {
            return;
        }
        Component uiComponent = browser.getUIComponent();
        uiComponent.setBounds(0, 0, Math.max(0, browserPanel.getWidth()), Math.max(0, browserPanel.getHeight()));
        uiComponent.invalidate();
        browserPanel.revalidate();
        browserPanel.repaint();
        if (SystemInfo.isMacOS) {
            SwingUtilities.invokeLater(() -> {
                uiComponent.setBounds(0, 0, Math.max(0, browserPanel.getWidth()), Math.max(0, browserPanel.getHeight()));
                browserPanel.revalidate();
                browserPanel.repaint();
            });
        }
    }

    private void closeBrowser() {
        CefBrowser browserToClose = browser;
        CefClient clientToClose = cefClient;
        CefMessageRouter routerToClose = messageRouter;
        browser = null;
        cefClient = null;
        messageRouter = null;

        if (browserToClose != null) {
            runNativeCleanup(browserToClose::stopLoad);
            runNativeCleanup(browserToClose::setCloseAllowed);
            runNativeCleanup(() -> removeBrowserComponent(browserToClose));
            runNativeCleanup(() -> browserToClose.close(true));
        }
        if (clientToClose != null && routerToClose != null) {
            runNativeCleanup(() -> clientToClose.removeMessageRouter(routerToClose));
        }
        if (routerToClose != null) {
            runNativeCleanup(routerToClose::dispose);
        }
        if (clientToClose != null) {
            runNativeCleanup(clientToClose::dispose);
        }
        currentDocumentUrl = "";
        loadedDocumentUrl = "";
        initialBrowserUrl = "";
        nativeBrowserCreated = false;
        htmlByUrl.clear();
    }

    private void removeBrowserComponent(CefBrowser browserToClose) {
        Component uiComponent = browserToClose.getUIComponent();
        Container parent = uiComponent.getParent();
        if (parent != null) {
            parent.remove(uiComponent);
            parent.revalidate();
            parent.repaint();
        }
    }

    private void runNativeCleanup(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable ignored) {
            // Continue releasing the remaining native resources.
        }
    }


    enum NavigationDecision {
        ALLOW,
        BLOCK,
        OPEN_EXTERNAL
    }


    private static String encodeSupplementaryCodePoints(String text) {
        String value = StringUtils.defaultString(text);
        StringBuilder encoded = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isSupplementaryCodePoint(codePoint)) {
                encoded.append("&#x").append(Integer.toHexString(codePoint)).append(";");
            } else {
                encoded.appendCodePoint(codePoint);
            }
        });
        return encoded.toString();
    }


    private final class TranscriptLifeSpanHandler extends CefLifeSpanHandlerAdapter {
        @Override
        public void onAfterCreated(CefBrowser createdBrowser) {
            SwingUtilities.invokeLater(() -> handleBrowserCreated(createdBrowser));
        }
    }

    private void handleBrowserCreated(CefBrowser createdBrowser) {
        if (disposed || browser != createdBrowser) {
            return;
        }
        nativeBrowserCreated = true;
        if (StringUtils.isNotBlank(loadingDocumentUrl) && !Strings.CS.equals(loadingDocumentUrl, initialBrowserUrl)) {
            browser.loadURL(loadingDocumentUrl);
        }
    }

    private final class TranscriptLoadHandler extends CefLoadHandlerAdapter {
        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame == null || !frame.isMain()) {
                return;
            }
            String loadedUrl = frame.getURL();
            SwingUtilities.invokeLater(() -> handleDocumentLoadEnd(loadedUrl));
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
            if (frame == null || !frame.isMain()) {
                return;
            }
            SwingUtilities.invokeLater(() -> handleDocumentLoadError(failedUrl));
        }
    }

    private final class TranscriptRequestHandler extends CefRequestHandlerAdapter {
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture, boolean isRedirect) {
            String url = request == null ? "" : request.getURL();
            NavigationDecision decision = navigationDecision(url, userGesture);
            if (decision == NavigationDecision.OPEN_EXTERNAL) {
                ExternalLinkSupport.openExternalLink(url);
            }
            return decision != NavigationDecision.ALLOW;
        }

        @Override
        public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String targetUrl, boolean userGesture) {
            if (navigationDecision(targetUrl, userGesture) == NavigationDecision.OPEN_EXTERNAL) {
                ExternalLinkSupport.openExternalLink(targetUrl);
            }
            return true;
        }

        @Override
        public CefResourceRequestHandler getResourceRequestHandler(
                CefBrowser browser,
                CefFrame frame,
                CefRequest request,
                boolean isNavigation,
                boolean isDownload,
                String requestInitiator,
                BoolRef disableDefaultHandling
        ) {
            String url = request == null ? "" : request.getURL();
            String html = htmlForUrl(url);
            if (html != null) {
                return new DirectResourceRequestHandler(html, "text/html", "text/html; charset=utf-8");
            }
            String script = scriptForUrl(url);
            if (script != null) {
                return new DirectResourceRequestHandler(script, "application/javascript", "application/javascript; charset=utf-8");
            }
            BinaryResource image = pdfImageResources.get(url);
            return image == null
                    ? null
                    : new DirectResourceRequestHandler(image.bytes(), image.mimeType(), image.mimeType());
        }
    }

    private record BinaryResource(byte[] bytes, String mimeType) {
        private BinaryResource {
            bytes = bytes.clone();
            mimeType = StringUtils.defaultIfBlank(mimeType, "application/octet-stream");
        }
    }

    private static final class DirectResourceRequestHandler extends CefResourceRequestHandlerAdapter {
        private final byte[] bytes;
        private final String mimeType;
        private final String contentType;

        private DirectResourceRequestHandler(String content, String mimeType, String contentType) {
            this(content.getBytes(StandardCharsets.UTF_8), mimeType, contentType);
        }

        private DirectResourceRequestHandler(byte[] bytes, String mimeType, String contentType) {
            this.bytes = bytes.clone();
            this.mimeType = mimeType;
            this.contentType = contentType;
        }

        @Override
        public DirectResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new DirectResourceHandler(bytes, mimeType, contentType);
        }
    }

    private static final class DirectResourceHandler extends CefResourceHandlerAdapter {
        private final byte[] bytes;
        private final String mimeType;
        private final String contentType;
        private int offset;

        private DirectResourceHandler(byte[] bytes, String mimeType, String contentType) {
            this.bytes = bytes.clone();
            this.mimeType = mimeType;
            this.contentType = contentType;
        }

        @Override
        public boolean open(CefRequest request, BoolRef handleRequest, org.cef.callback.CefCallback callback) {
            handleRequest.set(true);
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(200);
            response.setStatusText("OK");
            response.setMimeType(mimeType);
            response.setHeaderByName("Content-Type", contentType, true);
            response.setHeaderByName("Cache-Control", "no-store", true);
            if (Strings.CS.equals(mimeType, "text/html")) {
                response.setHeaderByName("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src data:;", true);
            }
            responseLength.set(bytes.length);
        }

        @Override
        public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            int remaining = bytes.length - offset;
            if (remaining <= 0) {
                bytesRead.set(0);
                return false;
            }
            int count = Math.min(bytesToRead, remaining);
            System.arraycopy(bytes, offset, dataOut, 0, count);
            offset += count;
            bytesRead.set(count);
            return true;
        }
    }

}
