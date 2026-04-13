package com.github.drafael.chat4j.provider.api.content;

import java.util.List;

public record MessageMeta(
    List<String> activeSkills,
    List<String> fallbackNotices,
    boolean cancelled,
    String error
) {

    public MessageMeta {
        activeSkills = activeSkills == null ? List.of() : List.copyOf(activeSkills);
        fallbackNotices = fallbackNotices == null ? List.of() : List.copyOf(fallbackNotices);
        error = error == null ? "" : error;
    }

    public MessageMeta(List<String> activeSkills, boolean cancelled, String error) {
        this(activeSkills, List.of(), cancelled, error);
    }

    public static MessageMeta empty() {
        return new MessageMeta(List.of(), List.of(), false, "");
    }
}
