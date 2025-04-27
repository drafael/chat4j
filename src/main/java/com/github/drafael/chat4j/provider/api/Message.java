package com.github.drafael.chat4j.provider.api;

import java.time.Instant;

public record Message(Role role, String content, Instant timestamp) {

    public static Message user(String content) {
        return new Message(Role.USER, content, Instant.now());
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, Instant.now());
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, Instant.now());
    }
}
