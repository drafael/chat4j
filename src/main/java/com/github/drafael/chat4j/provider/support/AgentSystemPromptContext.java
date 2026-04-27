package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

public final class AgentSystemPromptContext {

    private static final ThreadLocal<String> PROMPT_APPEND = new ThreadLocal<>();

    private AgentSystemPromptContext() {
    }

    public static Scope open(String promptAppend) {
        String normalized = StringUtils.trimToNull(promptAppend);
        String previous = PROMPT_APPEND.get();

        if (normalized == null) {
            PROMPT_APPEND.remove();
        } else {
            PROMPT_APPEND.set(normalized);
        }

        return new Scope(previous);
    }

    public static Optional<String> currentPromptAppend() {
        return Optional.ofNullable(PROMPT_APPEND.get());
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                PROMPT_APPEND.remove();
            } else {
                PROMPT_APPEND.set(previous);
            }
        }
    }
}
