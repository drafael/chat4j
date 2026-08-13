package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.chat.conversation.ConversationTurnFingerprint;
import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefBrowserView;
import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefBrowserView.PdfTurnMetadata;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.joining;

public final class JcefConversationPdfExporter implements ConversationPdfExporter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);
    private final JcefBrowserView browserView;

    public JcefConversationPdfExporter(@NonNull JcefBrowserView browserView) {
        this.browserView = browserView;
    }

    @Override
    public void export(
            @NonNull ConversationPdfDocument document,
            @NonNull Path destination,
            @NonNull PdfPageFormat pageFormat,
            @NonNull BooleanSupplier cancelled
    ) throws Exception {
        String providerModel = java.util.stream.Stream.of(document.provider(), document.model())
                .filter(StringUtils::isNotBlank)
                .collect(joining(" · "));
        String exportedAt = DATE_FORMATTER.format(document.exportedAt().atZone(ZoneId.systemDefault()));
        String metadata = StringUtils.isBlank(providerModel)
                ? "Exported %s".formatted(exportedAt)
                : "%s · Exported %s".formatted(providerModel, exportedAt);
        List<PdfTurnMetadata> turns = document.turns().stream()
                .map(turn -> new PdfTurnMetadata(
                        turn.role() == Role.USER ? "user" : "assistant",
                        turn.role() == Role.USER ? "You" : "Assistant",
                        DATE_FORMATTER.format(turn.timestamp().atZone(ZoneId.systemDefault())),
                        ConversationTurnFingerprint.create(
                                turn.role(),
                                turn.parts(),
                                turn.fallbackNotices(),
                                turn.cancelled(),
                                turn.error(),
                                turn.assistantWebSearch(),
                                turn.citations()
                        )
                ))
                .toList();
        List<AttachmentRef> imageReferences = document.turns().stream()
                .flatMap(turn -> turn.parts().stream())
                .map(part -> switch (part) {
                    case ImagePart image -> image.attachmentRef();
                    case GeneratedImagePart image -> image.attachmentRef();
                    default -> null;
                })
                .filter(Objects::nonNull)
                .toList();
        browserView.printToPdf(
                destination,
                document.title(),
                metadata,
                turns,
                imageReferences,
                pageFormat,
                cancelled
        );
    }
}
