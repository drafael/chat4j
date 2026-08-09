package com.github.drafael.chat4j.chat.conversation;

import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public final class ConversationTurnFingerprint {

    private ConversationTurnFingerprint() {
    }

    public static String create(
            Role role,
            List<ContentPart> parts,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            List<CitationRef> citations
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<ContentPart> safeParts = parts == null ? emptyList() : parts;
            List<String> safeFallbackNotices = fallbackNotices == null ? emptyList() : fallbackNotices;
            List<CitationRef> safeCitations = citations == null ? emptyList() : citations;
            addField(digest, "role", role == null ? "" : role.name());
            addField(digest, "parts-count", safeParts.size());
            safeParts.forEach(part -> addPart(digest, part));
            addField(digest, "fallback-notices-count", safeFallbackNotices.size());
            safeFallbackNotices.forEach(notice -> addField(digest, "fallback-notice", notice));
            addField(digest, "cancelled", cancelled);
            addField(digest, "error", error);
            addField(digest, "citations-count", safeCitations.size());
            safeCitations.forEach(citation -> addCitation(digest, citation));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint a conversation turn.", e);
        }
    }

    private static void addPart(MessageDigest digest, ContentPart part) {
        if (part == null) {
            addField(digest, "part-type", "null");
            return;
        }
        addField(digest, "part-type", part.getClass().getName());
        switch (part) {
            case TextPart text -> addField(digest, "text", text.text());
            case ImagePart image -> {
                addAttachment(digest, image.attachmentRef());
                addField(digest, "width", image.width());
                addField(digest, "height", image.height());
            }
            case GeneratedImagePart image -> {
                addAttachment(digest, image.attachmentRef());
                addField(digest, "width", image.width());
                addField(digest, "height", image.height());
                addField(digest, "alt-text", image.altText());
            }
            case FilePart file -> addAttachment(digest, file.attachmentRef());
        }
    }

    private static void addAttachment(MessageDigest digest, AttachmentRef attachment) {
        if (attachment == null) {
            addField(digest, "attachment", "null");
            return;
        }
        addField(digest, "attachment-id", attachment.id());
        addField(digest, "attachment-storage-path", attachment.storagePath());
        addField(digest, "attachment-original-name", attachment.originalName());
        addField(digest, "attachment-mime-type", attachment.mimeType());
        addField(digest, "attachment-size", attachment.sizeBytes());
        addField(digest, "attachment-sha256", attachment.sha256());
    }

    private static void addCitation(MessageDigest digest, CitationRef citation) {
        if (citation == null) {
            addField(digest, "citation", "null");
            return;
        }
        addField(digest, "citation-number", citation.number());
        addField(digest, "citation-kind", citation.kind());
        addField(digest, "citation-title", citation.title());
        addField(digest, "citation-text", citation.citedText());
        addField(digest, "citation-url", citation.url());
        addField(digest, "citation-encrypted-index", citation.encryptedIndex());
        addField(digest, "citation-document-index", citation.documentIndex());
        addField(digest, "citation-document-title", citation.documentTitle());
        addField(digest, "citation-file-id", citation.fileId());
        addField(digest, "citation-start-page", citation.startPage());
        addField(digest, "citation-end-page", citation.endPage());
        addField(digest, "citation-start-char", citation.startChar());
        addField(digest, "citation-end-char", citation.endChar());
        addField(digest, "citation-start-block", citation.startBlock());
        addField(digest, "citation-end-block", citation.endBlock());
        addField(digest, "citation-source", citation.source());
        addField(digest, "citation-search-result-index", citation.searchResultIndex());
    }

    private static void addField(MessageDigest digest, String name, Object value) {
        add(digest, name);
        add(digest, value);
    }

    private static void add(MessageDigest digest, Object value) {
        byte[] bytes = StringUtils.defaultString(value == null ? null : value.toString()).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
