package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

public final class TranscriptAttachmentRenderer {

    private static final int ATTACHMENT_IMAGE_MAX_WIDTH = 420;
    private static final int ATTACHMENT_IMAGE_MAX_HEIGHT = 360;
    private static final int GENERATED_IMAGE_SOURCE_MAX_WIDTH = 1280;
    private static final int GENERATED_IMAGE_SOURCE_MAX_HEIGHT = 1280;

    private TranscriptAttachmentRenderer() {
    }

    public static String renderAttachmentStripHtml(List<ConversationAttachment> attachments) {
        List<ConversationAttachment> safeAttachments = attachments == null ? emptyList() : attachments;
        if (safeAttachments.isEmpty()) {
            return "";
        }

        String content = safeAttachments.stream()
                .map(TranscriptAttachmentRenderer::renderAttachmentHtml)
                .collect(joining(""));
        return "<div class=\"attachment-strip\">%s</div>".formatted(content);
    }

    private static String renderAttachmentHtml(ConversationAttachment attachment) {
        return attachment == null
                ? ""
                : attachment.image() ? renderImageAttachmentHtml(attachment) : renderFileAttachmentHtml(attachment);
    }

    private static String renderImageAttachmentHtml(ConversationAttachment attachment) {
        String imageDataUri = imageThumbnailDataUri(attachment);
        if (StringUtils.isBlank(imageDataUri)) {
            return renderUnavailableAttachmentHtml(attachment, "🖼", "Image unavailable");
        }

        String title = escapeHtmlAttribute(displayName(attachment));
        String path = escapeHtmlAttribute(attachment.storagePath());
        return """
                <button class="attachment-image-button" type="button" data-action="open-attachment" data-attachment-path="%s" title="%s">
                  <img class="attachment-image" src="%s" alt="%s">
                </button>
                """.formatted(path, title, imageDataUri, title);
    }

    private static String renderFileAttachmentHtml(ConversationAttachment attachment) {
        boolean available = isAttachmentAvailable(attachment);
        String className = available ? "attachment-chip" : "attachment-chip unavailable";
        String actionAttributes = available
                ? " data-action=\"open-attachment\" data-attachment-path=\"%s\"".formatted(escapeHtmlAttribute(attachment.storagePath()))
                : " disabled";
        String size = attachment.sizeBytes() > 0 ? formatAttachmentSize(attachment.sizeBytes()) : "";
        String sizeHtml = StringUtils.isBlank(size) ? "" : "<span class=\"attachment-size\">%s</span>".formatted(escapeHtml(size));
        return """
                <button class="%s" type="button"%s title="%s">
                  <span class="attachment-icon" aria-hidden="true">📄</span>
                  <span class="attachment-name">%s</span>
                  %s
                </button>
                """.formatted(
                className,
                actionAttributes,
                escapeHtmlAttribute(displayName(attachment)),
                escapeHtml(displayName(attachment)),
                sizeHtml
        );
    }

    private static String renderUnavailableAttachmentHtml(ConversationAttachment attachment, String icon, String status) {
        String size = attachment.sizeBytes() > 0 ? formatAttachmentSize(attachment.sizeBytes()) : status;
        return """
                <span class="attachment-chip unavailable" title="%s">
                  <span class="attachment-icon" aria-hidden="true">%s</span>
                  <span class="attachment-name">%s</span>
                  <span class="attachment-size">%s</span>
                </span>
                """.formatted(
                escapeHtmlAttribute(status),
                escapeHtml(icon),
                escapeHtml(displayName(attachment)),
                escapeHtml(size)
        );
    }

    public static String generatedImageDataUri(String storagePath) {
        return imageDataUri(
                new ConversationAttachment(storagePath, "generated image", "image/png", 0L, true),
                GENERATED_IMAGE_SOURCE_MAX_WIDTH,
                GENERATED_IMAGE_SOURCE_MAX_HEIGHT
        );
    }

    private static String imageThumbnailDataUri(ConversationAttachment attachment) {
        return imageDataUri(attachment, ATTACHMENT_IMAGE_MAX_WIDTH, ATTACHMENT_IMAGE_MAX_HEIGHT);
    }

    private static String imageDataUri(ConversationAttachment attachment, int maxWidth, int maxHeight) {
        Path path = attachmentPath(attachment);
        if (path == null || !Files.exists(path)) {
            return "";
        }

        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                return "";
            }
            BufferedImage thumbnail = renderThumbnail(image, maxWidth, maxHeight);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "png", output);
            String encoded = Base64.getEncoder().encodeToString(output.toByteArray());
            return "data:image/png;base64,%s".formatted(encoded);
        } catch (Exception e) {
            return "";
        }
    }

    private static BufferedImage renderThumbnail(BufferedImage source, int maxWidth, int maxHeight) {
        double scale = Math.min(
                1.0,
                Math.min(
                        maxWidth / (double) source.getWidth(),
                        maxHeight / (double) source.getHeight()
                )
        );
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return thumbnail;
    }

    private static boolean isAttachmentAvailable(ConversationAttachment attachment) {
        Path path = attachmentPath(attachment);
        return path != null && Files.exists(path);
    }

    private static Path attachmentPath(ConversationAttachment attachment) {
        if (attachment == null || StringUtils.isBlank(attachment.storagePath())) {
            return null;
        }
        try {
            return Path.of(attachment.storagePath());
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(ConversationAttachment attachment) {
        String fallback = attachment.image() ? "image" : "attachment";
        if (StringUtils.isNotBlank(attachment.originalName())) {
            return attachment.originalName();
        }
        Path path = attachmentPath(attachment);
        if (path != null && path.getFileName() != null) {
            return path.getFileName().toString();
        }
        return fallback;
    }

    private static String formatAttachmentSize(long bytes) {
        if (bytes < 1_000) {
            return "%d B".formatted(bytes);
        }
        if (bytes < 1_000_000) {
            return "%.1f kB".formatted(bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000) {
            return "%.1f MB".formatted(bytes / 1_000_000.0);
        }
        return "%.1f GB".formatted(bytes / 1_000_000_000.0);
    }

    private static String escapeHtml(String text) {
        return StringUtils.defaultString(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeHtmlAttribute(String text) {
        return escapeHtml(text)
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
