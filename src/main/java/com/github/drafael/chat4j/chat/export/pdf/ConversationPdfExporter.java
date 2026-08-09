package com.github.drafael.chat4j.chat.export.pdf;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

public interface ConversationPdfExporter {

    void export(
            @NonNull ConversationPdfDocument document,
            @NonNull Path destination,
            @NonNull PdfPageFormat pageFormat,
            @NonNull BooleanSupplier cancelled
    ) throws Exception;

    default void export(
            @NonNull ConversationPdfDocument document,
            @NonNull Path destination,
            @NonNull BooleanSupplier cancelled
    ) throws Exception {
        export(document, destination, PdfPageFormat.A4, cancelled);
    }
}
