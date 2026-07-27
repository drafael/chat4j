package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;

/**
 * One request-local, SDK-neutral projection of message attachments.
 */
public final class AttachmentProjectionPlan {

    public static final int MAX_ATTACHMENT_OCCURRENCES = 256;
    public static final int MAX_BYTE_BEARING_OCCURRENCES = 32;
    public static final long MAX_SOURCE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_BASE64_CHARACTERS = 64L * 1024L * 1024L;
    public static final long MAX_EXTRACTED_TEXT_UTF8_BYTES = 1024L * 1024L;
    public static final String OMISSION_MARKER = "[Additional attachments omitted: request limit reached]";
    private static final String EXTRACTED_TEXT_WRAPPER = "\n\nExtracted attachment text:\n";

    private static final Set<String> OPENAI_IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final Set<String> ANTHROPIC_IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );
    private static final Set<String> GOOGLE_IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

    private final List<ProjectedMessage> messages;

    private AttachmentProjectionPlan(List<ProjectedMessage> messages) {
        this.messages = List.copyOf(messages);
    }

    public static AttachmentProjectionPlan create(
            List<Message> history,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull Profile profile,
            @NonNull BooleanSupplier isCancelled
    ) {
        List<Message> safeHistory = history == null ? emptyList() : history;
        Planner planner = new Planner(safeHistory, attachmentSupport, profile, isCancelled);
        return planner.create();
    }

    public List<ProjectedMessage> messages() {
        return messages;
    }

    public static String textFallback(ProjectedPart part) {
        return switch (part) {
            case PlainText text -> text.text();
            case Label label -> label.text();
            case ExtractedText extracted -> extracted.projection();
            case NativeTextDocument document -> document.text();
            case NativePdf pdf -> "[File attached: %s]".formatted(pdf.title());
            case NativeImage ignored -> "";
        };
    }

    public static Profile openAi(boolean nativeImages) {
        return new Profile(Provider.OPENAI, nativeImages, false);
    }

    public static Profile anthropic(boolean nativeImages, boolean nativeDocuments) {
        return new Profile(Provider.ANTHROPIC, nativeImages, nativeDocuments);
    }

    public static Profile google() {
        return new Profile(Provider.GOOGLE, true, false);
    }

    public static Profile textOnly() {
        return new Profile(Provider.TEXT_ONLY, false, false);
    }

    public static Profile metadataOnly() {
        return new Profile(Provider.METADATA_ONLY, false, false);
    }

    public record Profile(Provider provider, boolean nativeImages, boolean nativeDocuments) {
    }

    public enum Provider {
        OPENAI,
        ANTHROPIC,
        GOOGLE,
        TEXT_ONLY,
        METADATA_ONLY
    }

    public record ProjectedMessage(Role role, List<ProjectedPart> parts) {
        public ProjectedMessage {
            parts = List.copyOf(parts);
        }
    }

    public sealed interface ProjectedPart permits PlainText, Label, ExtractedText, NativeImage, NativePdf, NativeTextDocument {
    }

    /** Ordinary message text is retained outside attachment budgets. */
    public record PlainText(String text) implements ProjectedPart {
    }

    public record Label(String text) implements ProjectedPart {
    }

    public record ExtractedText(String label, String text) implements ProjectedPart {
        public String projection() {
            return "%s%s%s".formatted(label, EXTRACTED_TEXT_WRAPPER, text);
        }
    }

    public record NativeImage(String mediaType, String base64Data) implements ProjectedPart {
        @Override
        public String toString() {
            return "NativeImage[mediaType=%s, base64Data=<masked>]".formatted(mediaType);
        }
    }

    public record NativePdf(String title, String base64Data) implements ProjectedPart {
        @Override
        public String toString() {
            return "NativePdf[title=%s, base64Data=<masked>]".formatted(title);
        }
    }

    public record NativeTextDocument(String title, String text) implements ProjectedPart {
    }

    private static final class Planner {
        private final List<Message> history;
        private final ProviderAttachmentSupport attachmentSupport;
        private final Profile profile;
        private final BooleanSupplier isCancelled;
        private final Map<Coordinate, ProjectedPart> attachmentDecisions = new HashMap<>();
        private final Budget budget = new Budget();
        private Coordinate earliestOmitted;

        private Planner(
                List<Message> history,
                ProviderAttachmentSupport attachmentSupport,
                Profile profile,
                BooleanSupplier isCancelled
        ) {
            this.history = history;
            this.attachmentSupport = attachmentSupport;
            this.profile = profile;
            this.isCancelled = isCancelled;
        }

        private AttachmentProjectionPlan create() {
            scanNewestFirst();
            List<ProjectedMessage> projectedMessages = shouldStop() ? emptyList() : emitForward();
            return new AttachmentProjectionPlan(projectedMessages);
        }

        private void scanNewestFirst() {
            for (int messageIndex = history.size() - 1; messageIndex >= 0; messageIndex--) {
                if (shouldStop()) {
                    return;
                }
                Message message = history.get(messageIndex);
                List<ContentPart> parts = message.parts();
                for (int partIndex = parts.size() - 1; partIndex >= 0; partIndex--) {
                    if (shouldStop()) {
                        return;
                    }
                    ContentPart part = parts.get(partIndex);
                    if (!isAttachment(part)) {
                        continue;
                    }
                    Coordinate coordinate = new Coordinate(messageIndex, partIndex);
                    if (budget.inspectedOccurrences >= MAX_ATTACHMENT_OCCURRENCES) {
                        markOmitted(coordinate);
                        continue;
                    }
                    budget.inspectedOccurrences++;
                    projectAttachment(message.role(), part).ifPresentOrElse(
                            decision -> attachmentDecisions.put(coordinate, decision),
                            () -> markOmitted(coordinate)
                    );
                }
            }
        }

        private Optional<ProjectedPart> projectAttachment(Role role, ContentPart part) {
            if (shouldStop()) {
                return Optional.empty();
            }
            String label = attachmentSupport.safeLabel(part);
            if (role != Role.USER || part instanceof GeneratedImagePart || profile.provider() == Provider.METADATA_ONLY) {
                return label(label);
            }
            if (part instanceof ImagePart imagePart) {
                return projectImage(imagePart).or(() -> label(label));
            }
            if (part instanceof FilePart filePart) {
                return projectFile(filePart, label).or(() -> label(label));
            }
            return label(label);
        }

        private Optional<ProjectedPart> projectImage(ImagePart imagePart) {
            Set<String> allowedMimes = switch (profile.provider()) {
                case OPENAI -> profile.nativeImages() ? OPENAI_IMAGE_MIMES : Set.of();
                case ANTHROPIC -> profile.nativeImages() ? ANTHROPIC_IMAGE_MIMES : Set.of();
                case GOOGLE -> GOOGLE_IMAGE_MIMES;
                case TEXT_ONLY, METADATA_ONLY -> Set.of();
            };
            if (allowedMimes.isEmpty()) {
                return Optional.empty();
            }
            if (shouldStop()) {
                return Optional.empty();
            }
            return attachmentSupport.resolve(imagePart.attachmentRef(), true)
                    .filter(attachment -> attachment.kind() == ProviderAttachmentSupport.AttachmentKind.IMAGE)
                    .filter(attachment -> allowedMimes.contains(attachment.mimeType()))
                    .flatMap(attachment -> nativeImage(attachment, profile.provider() == Provider.OPENAI));
        }

        private Optional<ProjectedPart> nativeImage(
                ProviderAttachmentSupport.ResolvedAttachment attachment,
                boolean requireSingleFrameGif
        ) {
            if (!budget.canRead(attachment.actualSize(), ProviderAttachmentSupport.MAX_IMAGE_BYTES)) {
                return Optional.empty();
            }
            long expectedBase64 = base64Length(attachment.actualSize());
            if (!budget.canUseBase64(expectedBase64) || shouldStop()) {
                return Optional.empty();
            }
            budget.startRead();
            return attachmentSupport.readBytes(attachment, Math.min(
                            ProviderAttachmentSupport.MAX_IMAGE_BYTES,
                            budget.remainingSourceBytes()
                    ))
                    .flatMap(bytes -> {
                        budget.chargeSourceBytes(bytes.actualBytes());
                        if (!bytes.complete()) {
                            return Optional.empty();
                        }
                        boolean unsupportedAnimatedGif = requireSingleFrameGif
                                && "image/gif".equals(attachment.mimeType())
                                && !attachmentSupport.isSingleFrameGif(bytes.bytes());
                        if (shouldStop()
                                || unsupportedAnimatedGif
                                || !budget.canUseBase64(base64Length(bytes.actualBytes()))) {
                            return Optional.empty();
                        }
                        String encoded = Base64.getEncoder().encodeToString(bytes.bytes());
                        budget.admitBase64(encoded.length());
                        return Optional.of(new NativeImage(attachment.mimeType(), encoded));
                    });
        }

        private Optional<ProjectedPart> projectFile(FilePart filePart, String label) {
            if (shouldStop()) {
                return Optional.empty();
            }
            Optional<ProviderAttachmentSupport.ResolvedAttachment> resolved = attachmentSupport.resolve(
                    filePart.attachmentRef(),
                    false
            );
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            ProviderAttachmentSupport.ResolvedAttachment attachment = resolved.get();
            if (attachment.kind() == ProviderAttachmentSupport.AttachmentKind.PDF
                    && profile.provider() == Provider.ANTHROPIC
                    && profile.nativeDocuments()) {
                return anthropicPdf(attachment);
            }
            if (attachment.kind() != ProviderAttachmentSupport.AttachmentKind.TEXT
                    && attachment.kind() != ProviderAttachmentSupport.AttachmentKind.PDF) {
                return Optional.empty();
            }
            if (profile.provider() == Provider.ANTHROPIC
                    && profile.nativeDocuments()
                    && attachment.kind() == ProviderAttachmentSupport.AttachmentKind.TEXT) {
                return nativeTextDocument(attachment);
            }
            return extractedText(attachment, label);
        }

        private Optional<ProjectedPart> anthropicPdf(ProviderAttachmentSupport.ResolvedAttachment attachment) {
            if (!budget.canRead(attachment.actualSize(), ProviderAttachmentSupport.MAX_PDF_BYTES)
                    || !budget.canUseBase64(base64Length(attachment.actualSize()))
                    || shouldStop()) {
                return Optional.empty();
            }
            budget.startRead();
            return attachmentSupport.readBytes(attachment, Math.min(
                            ProviderAttachmentSupport.MAX_PDF_BYTES,
                            budget.remainingSourceBytes()
                    ))
                    .flatMap(bytes -> {
                        budget.chargeSourceBytes(bytes.actualBytes());
                        return shouldStop() || !bytes.complete()
                                ? Optional.empty()
                                : nativePdf(attachment, bytes);
                    });
        }

        private Optional<ProjectedPart> nativePdf(
                ProviderAttachmentSupport.ResolvedAttachment attachment,
                ProviderAttachmentSupport.BoundedBytes bytes
        ) {
            if (!budget.canUseBase64(base64Length(bytes.actualBytes()))) {
                return Optional.empty();
            }
            String encoded = Base64.getEncoder().encodeToString(bytes.bytes());
            budget.admitBase64(encoded.length());
            return Optional.of(new NativePdf(attachment.safeName(), encoded));
        }

        private Optional<ProjectedPart> nativeTextDocument(ProviderAttachmentSupport.ResolvedAttachment attachment) {
            return extract(attachment).map(extracted -> {
                budget.admitExtracted(extracted.text());
                return new NativeTextDocument(attachment.safeName(), extracted.text());
            });
        }

        private Optional<ProjectedPart> extractedText(
                ProviderAttachmentSupport.ResolvedAttachment attachment,
                String label
        ) {
            return extract(attachment).flatMap(extracted -> admitExtractedText(extracted, label));
        }

        private Optional<ProjectedPart> admitExtractedText(
                ProviderAttachmentSupport.ExtractedAttachment extracted,
                String label
        ) {
            if (!budget.canAdmitExtracted(extracted.text())) {
                return Optional.empty();
            }
            budget.admitExtracted(extracted.text());
            return Optional.of(new ExtractedText(label, extracted.text()));
        }

        private Optional<ProviderAttachmentSupport.ExtractedAttachment> extract(
                ProviderAttachmentSupport.ResolvedAttachment attachment
        ) {
            long perFileLimit = attachment.kind() == ProviderAttachmentSupport.AttachmentKind.PDF
                    ? ProviderAttachmentSupport.MAX_PDF_BYTES
                    : ProviderAttachmentSupport.MAX_TEXT_BYTES;
            if (!budget.canRead(attachment.actualSize(), perFileLimit)) {
                return Optional.empty();
            }
            if (shouldStop()) {
                return Optional.empty();
            }
            budget.startRead();
            return attachmentSupport.readBytes(
                            attachment,
                            Math.min(perFileLimit, budget.remainingSourceBytes())
                    )
                    .flatMap(bytes -> {
                        budget.chargeSourceBytes(bytes.actualBytes());
                        if (shouldStop() || !bytes.complete()) {
                            return Optional.empty();
                        }
                        return attachmentSupport.extractedAttachment(attachment, bytes);
                    })
                    .filter(extracted -> budget.canAdmitExtracted(extracted.text()));
        }

        private boolean shouldStop() {
            return Thread.currentThread().isInterrupted() || isCancelled.getAsBoolean();
        }

        private Optional<ProjectedPart> label(String text) {
            return Optional.of(new Label(text));
        }

        private void markOmitted(Coordinate coordinate) {
            if (earliestOmitted == null || coordinate.compareTo(earliestOmitted) < 0) {
                earliestOmitted = coordinate;
            }
        }

        private List<ProjectedMessage> emitForward() {
            List<ProjectedMessage> projectedMessages = new ArrayList<>();
            boolean markerEmitted = false;
            for (int messageIndex = 0; messageIndex < history.size(); messageIndex++) {
                if (shouldStop()) {
                    return emptyList();
                }
                Message message = history.get(messageIndex);
                List<ProjectedPart> projectedParts = new ArrayList<>();
                List<ContentPart> parts = message.parts();
                for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                    if (shouldStop()) {
                        return emptyList();
                    }
                    Coordinate coordinate = new Coordinate(messageIndex, partIndex);
                    if (!markerEmitted && coordinate.equals(earliestOmitted)) {
                        projectedParts.add(new Label(OMISSION_MARKER));
                        markerEmitted = true;
                    }
                    ContentPart part = parts.get(partIndex);
                    if (part instanceof TextPart textPart) {
                        if (StringUtils.isNotBlank(textPart.text())) {
                            projectedParts.add(new PlainText(textPart.text()));
                        }
                    } else {
                        ProjectedPart decision = attachmentDecisions.get(coordinate);
                        if (decision != null) {
                            projectedParts.add(decision);
                        }
                    }
                }
                if (!projectedParts.isEmpty()) {
                    projectedMessages.add(new ProjectedMessage(message.role(), projectedParts));
                }
            }
            return projectedMessages;
        }

        private static boolean isAttachment(ContentPart part) {
            return part instanceof ImagePart || part instanceof FilePart || part instanceof GeneratedImagePart;
        }
    }

    private static final class Budget {
        private int inspectedOccurrences;
        private int byteBearingOccurrences;
        private long sourceBytes;
        private long base64Characters;
        private long extractedTextUtf8Bytes;

        private boolean canRead(long expectedBytes, long perFileLimit) {
            return expectedBytes > 0
                    && expectedBytes <= perFileLimit
                    && byteBearingOccurrences < MAX_BYTE_BEARING_OCCURRENCES
                    && expectedBytes <= remainingSourceBytes();
        }

        private long remainingSourceBytes() {
            return MAX_SOURCE_BYTES - sourceBytes;
        }

        private boolean canUseBase64(long characters) {
            return characters >= 0 && characters <= MAX_BASE64_CHARACTERS - base64Characters;
        }

        private boolean canAdmitExtracted(String text) {
            long utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
            return utf8Bytes <= MAX_EXTRACTED_TEXT_UTF8_BYTES - extractedTextUtf8Bytes;
        }

        private void startRead() {
            byteBearingOccurrences++;
        }

        private void chargeSourceBytes(long actualBytes) {
            sourceBytes += actualBytes;
        }

        private void admitBase64(long encodedCharacters) {
            base64Characters += encodedCharacters;
        }

        private void admitExtracted(String text) {
            extractedTextUtf8Bytes += text.getBytes(StandardCharsets.UTF_8).length;
        }

    }

    private record Coordinate(int messageIndex, int partIndex) implements Comparable<Coordinate> {
        @Override
        public int compareTo(Coordinate other) {
            int messageComparison = Integer.compare(messageIndex, other.messageIndex);
            return messageComparison != 0 ? messageComparison : Integer.compare(partIndex, other.partIndex);
        }
    }

    private static long base64Length(long bytes) {
        return 4L * ((bytes + 2L) / 3L);
    }
}
