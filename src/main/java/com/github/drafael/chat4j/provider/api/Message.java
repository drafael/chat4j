package com.github.drafael.chat4j.provider.api;

import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ContentParts;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Message(Role role, List<ContentPart> parts, Instant timestamp, MessageMeta meta) {

    public Message {
        role = Objects.requireNonNull(role, "role can't be null");
        parts = parts == null ? List.of() : List.copyOf(parts);
        timestamp = timestamp == null ? Instant.now() : timestamp;
        meta = meta == null ? MessageMeta.empty() : meta;
    }

    public Message(Role role, List<ContentPart> parts, Instant timestamp) {
        this(role, parts, timestamp, MessageMeta.empty());
    }

    public Message(Role role, String content, Instant timestamp) {
        this(role, ContentParts.ofText(content), timestamp, MessageMeta.empty());
    }

    public String content() {
        return ContentParts.toText(parts);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, Instant.now());
    }

    public static Message user(List<ContentPart> parts) {
        return new Message(Role.USER, parts, Instant.now(), MessageMeta.empty());
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, Instant.now());
    }

    public static Message assistant(List<ContentPart> parts) {
        return new Message(Role.ASSISTANT, parts, Instant.now(), MessageMeta.empty());
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, Instant.now());
    }

    public static Message system(List<ContentPart> parts) {
        return new Message(Role.SYSTEM, parts, Instant.now(), MessageMeta.empty());
    }
}
