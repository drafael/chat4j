package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository.LoadedConversation;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import static com.github.drafael.chat4j.chat.export.pdf.ChromiumExecutableResolver.withExecutable;
import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.findDirectExecutable;
import static java.util.stream.Collectors.joining;

public final class ConversationPdfExportService implements AutoCloseable {

    private final ConversationRepository conversationRepository;
    private final PdfExportSettings settings;
    private final Map<String, String> subprocessEnvironment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-pdf-export-", 0).factory()
    );
    private final AtomicBoolean exporting = new AtomicBoolean();
    private final AtomicBoolean validatingPublication = new AtomicBoolean();
    private final AtomicReference<ExportRequest> activeRequest = new AtomicReference<>();

    public ConversationPdfExportService(
            @NonNull ConversationRepository conversationRepository,
            @NonNull SettingsRepository settingsRepository,
            @NonNull Map<String, String> subprocessEnvironment
    ) {
        this.conversationRepository = conversationRepository;
        this.settings = new PdfExportSettings(settingsRepository);
        this.subprocessEnvironment = Map.copyOf(subprocessEnvironment);
    }

    public ExportHandle export(
            @NonNull UUID conversationId,
            @NonNull Path destination,
            @NonNull Consumer<ExportStage> stageConsumer
    ) {
        return export(conversationId, destination, stageConsumer, null);
    }

    public ExportHandle export(
            @NonNull UUID conversationId,
            @NonNull Path destination,
            @NonNull Consumer<ExportStage> stageConsumer,
            ConversationPdfExporter enhancedAutoExporter
    ) {
        if (!exporting.compareAndSet(false, true)) {
            return ExportHandle.failed(new IllegalStateException("A PDF export is already running."));
        }
        var request = new ExportRequest();
        activeRequest.set(request);
        try {
            CompletableFuture<ExportResult> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    return exportBlocking(conversationId, destination, request, stageConsumer, enhancedAutoExporter);
                } catch (Exception e) {
                    throw new CompletionException(e);
                } finally {
                    activeRequest.compareAndSet(request, null);
                    request.finish();
                    exporting.set(false);
                }
            }, executor);
            return new ExportHandle(completion, request);
        } catch (RuntimeException e) {
            activeRequest.compareAndSet(request, null);
            request.finish();
            exporting.set(false);
            return ExportHandle.failed(e);
        }
    }

    public CompletableFuture<Optional<String>> validatePublicationBackend(@NonNull BooleanSupplier cancelled) {
        if (settings.mode() != PdfExportMode.PUBLICATION) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (!validatingPublication.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Optional.of("Publication tools are already being checked."));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return publicationExporter().unavailableReason(cancelled);
                } finally {
                    validatingPublication.set(false);
                }
            }, executor);
        } catch (RuntimeException e) {
            validatingPublication.set(false);
            return CompletableFuture.failedFuture(e);
        }
    }

    public boolean isExporting() {
        return exporting.get() || validatingPublication.get();
    }

    private ExportResult exportBlocking(
            UUID conversationId,
            Path destination,
            ExportRequest request,
            Consumer<ExportStage> stageConsumer,
            ConversationPdfExporter enhancedAutoExporter
    ) throws Exception {
        stageConsumer.accept(ExportStage.PREPARING_CONVERSATION);
        LoadedConversation loadedConversation = conversationRepository.loadConversation(conversationId)
                .orElseThrow(() -> new IOException("The conversation no longer exists."));
        ConversationPdfDocument document = ConversationPdfDocument.from(loadedConversation, Instant.now());
        if (request.isCancelled()) {
            return ExportResult.cancelledResult();
        }

        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("The PDF destination must have a parent directory.");
        }
        Files.createDirectories(parent);
        Path temporary = createTemporaryPdf(parent);
        try {
            stageConsumer.accept(ExportStage.RENDERING_DOCUMENT);
            String backend = exportDocument(
                    document,
                    temporary,
                    settings.pageFormat(),
                    request::isCancelled,
                    enhancedAutoExporter
            );
            if (request.isCancelled()) {
                return ExportResult.cancelledResult();
            }
            stageConsumer.accept(ExportStage.FINALIZING);
            validateAndApplyMetadata(temporary, document);
            if (!request.beginCommit()) {
                return ExportResult.cancelledResult();
            }
            replaceDestination(temporary, target);
            return new ExportResult(false, backend);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path createTemporaryPdf(Path parent) throws IOException {
        return Files.createTempFile(parent, ".chat4j-pdf-", ".pdf");
    }

    private String exportDocument(
            ConversationPdfDocument document,
            Path temporary,
            PdfPageFormat pageFormat,
            BooleanSupplier cancelled,
            ConversationPdfExporter enhancedAutoExporter
    ) throws Exception {
        PdfExportMode mode = settings.mode();
        if (mode == PdfExportMode.PUBLICATION) {
            publicationExporter().export(document, temporary, pageFormat, cancelled);
            return "Publication";
        }
        if (mode == PdfExportMode.AUTO && enhancedAutoExporter != null) {
            try {
                enhancedAutoExporter.export(document, temporary, pageFormat, cancelled);
                return "Chromium Enhanced";
            } catch (Exception e) {
                if (cancelled.getAsBoolean()) {
                    return "Chromium Enhanced";
                }
                Files.deleteIfExists(temporary);
                standardExporter().export(document, temporary, pageFormat, cancelled);
                return "Built-in Standard (Chromium unavailable; diagrams shown as source)";
            }
        }
        standardExporter().export(document, temporary, pageFormat, cancelled);
        return "Built-in Standard";
    }

    private ConversationPdfExporter standardExporter() {
        return new OpenHtmlConversationPdfExporter();
    }

    private PandocConversationPdfExporter publicationExporter() {
        Map<String, String> publicationEnvironment = withExecutable(
                subprocessEnvironment,
                settings.chromiumPathOverride()
        );
        return new PandocConversationPdfExporter(
                settings.pandocExecutable(),
                settings.latexExecutable(),
                StringUtils.defaultIfBlank(
                        settings.mermaidCliPath(),
                        findDirectExecutable("mmdc", publicationEnvironment).orElse("")
                ),
                publicationEnvironment
        );
    }

    private void validateAndApplyMetadata(Path path, ConversationPdfDocument document) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            throw new IOException("The PDF renderer did not produce a document.");
        }
        Path metadataOutput = Files.createTempFile(path.getParent(), ".chat4j-pdf-metadata-", ".tmp");
        try {
            try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
                if (pdf.getNumberOfPages() == 0) {
                    throw new IOException("The PDF renderer produced a document without pages.");
                }
                pdf.getDocumentInformation().setTitle(document.title());
                pdf.getDocumentInformation().setAuthor(providerModel(document));
                pdf.getDocumentInformation().setCreator("Chat4J");
                pdf.getDocumentInformation().setProducer("Chat4J");
                pdf.save(metadataOutput.toFile());
            }
            Files.move(metadataOutput, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(metadataOutput);
        }
    }

    private String providerModel(ConversationPdfDocument document) {
        return Stream.of(document.provider(), document.model())
                .filter(StringUtils::isNotBlank)
                .collect(joining(" · "));
    }

    private void replaceDestination(Path temporary, Path destination) throws IOException {
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            replaceDestinationWithRollback(temporary, destination);
        }
    }

    private void replaceDestinationWithRollback(Path temporary, Path destination) throws IOException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(temporary, destination);
            return;
        }

        Path backup = Files.createTempFile(destination.getParent(), ".chat4j-pdf-backup-", ".tmp");
        Files.deleteIfExists(backup);
        Files.move(destination, backup);
        try {
            Files.move(temporary, destination);
            try {
                Files.deleteIfExists(backup);
            } catch (IOException e) {
                backup.toFile().deleteOnExit();
            }
        } catch (IOException e) {
            boolean restored = false;
            try {
                Files.deleteIfExists(destination);
                Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
                restored = true;
            } catch (IOException restoreFailure) {
                e.addSuppressed(restoreFailure);
            } finally {
                if (restored) {
                    Files.deleteIfExists(backup);
                }
            }
            throw e;
        }
    }

    public CompletableFuture<Void> closeAsync() {
        close();
        return CompletableFuture.runAsync(() -> {
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, command -> Thread.ofVirtual().name("chat4j-pdf-close").start(command));
    }

    @Override
    public void close() {
        ExportRequest request = activeRequest.get();
        if (request != null) {
            request.cancel();
        }
        executor.shutdownNow();
    }

    public enum ExportStage {
        CHECKING_PUBLICATION_TOOLS("Checking Publication tools"),
        PREPARING_CONVERSATION("Preparing conversation"),
        RENDERING_DOCUMENT("Rendering document"),
        FINALIZING("Finalizing");

        private final String displayName;

        ExportStage(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record ExportResult(boolean cancelled, String backend) {

        private static ExportResult cancelledResult() {
            return new ExportResult(true, "");
        }
    }

    public static final class ExportHandle {

        private final CompletableFuture<ExportResult> completion;
        private final ExportRequest request;

        private ExportHandle(CompletableFuture<ExportResult> completion, ExportRequest request) {
            this.completion = completion;
            this.request = request;
        }

        private static ExportHandle failed(Throwable failure) {
            return new ExportHandle(CompletableFuture.failedFuture(failure), null);
        }

        public CompletableFuture<ExportResult> completion() {
            return completion;
        }

        public boolean cancel() {
            return request != null && request.cancel();
        }
    }

    private static final class ExportRequest {

        private final AtomicReference<ExportRequestState> state = new AtomicReference<>(ExportRequestState.RUNNING);

        private boolean isCancelled() {
            return state.get() == ExportRequestState.CANCELLED;
        }

        private boolean cancel() {
            ExportRequestState current = state.get();
            if (current == ExportRequestState.CANCELLED) {
                return true;
            }
            return state.compareAndSet(ExportRequestState.RUNNING, ExportRequestState.CANCELLED);
        }

        private boolean beginCommit() {
            return state.compareAndSet(ExportRequestState.RUNNING, ExportRequestState.COMMITTING);
        }

        private void finish() {
            state.compareAndSet(ExportRequestState.RUNNING, ExportRequestState.FINISHED);
            state.compareAndSet(ExportRequestState.COMMITTING, ExportRequestState.FINISHED);
        }
    }

    private enum ExportRequestState {
        RUNNING,
        CANCELLED,
        COMMITTING,
        FINISHED
    }
}
