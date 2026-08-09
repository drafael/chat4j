package com.github.drafael.chat4j.chat.export.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.PageSizeUnits;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

public final class OpenHtmlConversationPdfExporter implements ConversationPdfExporter {

    private static final String FONT_ROOT = "/web/export/pdf/fonts/";
    private final ConversationPrintHtmlRenderer htmlRenderer = new ConversationPrintHtmlRenderer();

    @Override
    public void export(
            @NonNull ConversationPdfDocument document,
            @NonNull Path destination,
            @NonNull PdfPageFormat pageFormat,
            @NonNull BooleanSupplier cancelled
    ) throws Exception {
        if (cancelled.getAsBoolean()) {
            return;
        }
        String html = htmlRenderer.render(document, cancelled);
        if (cancelled.getAsBoolean()) {
            return;
        }
        try (OutputStream output = Files.newOutputStream(destination)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.useHttpStreamImplementation(url -> {
                throw new IllegalStateException("Network resources are disabled during PDF export.");
            });
            builder.toStream(output);
            builder.useDefaultPageSize(
                    pageFormat.widthMillimeters(),
                    pageFormat.heightMillimeters(),
                    PageSizeUnits.MM
            );
            registerFonts(builder);
            builder.run();
        }
    }

    private void registerFonts(PdfRendererBuilder builder) {
        registerFont(builder, "LibertinusSerif-Regular.ttf", "Libertinus Serif", 400, FontStyle.NORMAL);
        registerFont(builder, "LibertinusSerif-Bold.ttf", "Libertinus Serif", 700, FontStyle.NORMAL);
        registerFont(builder, "LibertinusSerif-Italic.ttf", "Libertinus Serif", 400, FontStyle.ITALIC);
        registerFont(builder, "LibertinusSerif-BoldItalic.ttf", "Libertinus Serif", 700, FontStyle.ITALIC);
        registerFont(builder, "LibertinusSans-Regular.ttf", "Libertinus Sans", 400, FontStyle.NORMAL);
        registerFont(builder, "LibertinusSans-Bold.ttf", "Libertinus Sans", 700, FontStyle.NORMAL);
        registerFont(builder, "JetBrainsMono-Regular.ttf", "JetBrains Mono", 400, FontStyle.NORMAL);
        registerFont(builder, "JetBrainsMono-Bold.ttf", "JetBrains Mono", 700, FontStyle.NORMAL);
        registerFont(builder, "JetBrainsMono-Italic.ttf", "JetBrains Mono", 400, FontStyle.ITALIC);
        registerFont(builder, "JetBrainsMono-BoldItalic.ttf", "JetBrains Mono", 700, FontStyle.ITALIC);
        builder.useFont(() -> fontStream("NotoSans.ttf"), "Noto Sans");
        builder.useFont(() -> fontStream("NotoEmoji.ttf"), "Noto Emoji");
    }

    private void registerFont(
            PdfRendererBuilder builder,
            String resourceName,
            String family,
            int weight,
            FontStyle style
    ) {
        builder.useFont(() -> fontStream(resourceName), family, weight, style, true);
    }

    private InputStream fontStream(String resourceName) {
        InputStream input = OpenHtmlConversationPdfExporter.class.getResourceAsStream("%s%s".formatted(FONT_ROOT, resourceName));
        if (input == null) {
            throw new IllegalStateException("Missing PDF export font: %s".formatted(resourceName));
        }
        return input;
    }
}
