package com.github.drafael.chat4j.provider.api.content;

import java.util.List;
import static java.util.Collections.emptyList;

public record MessageMeta(
    List<String> activeSkills,
    List<String> fallbackNotices,
    boolean cancelled,
    String error
) {

    public MessageMeta {
        activeSkills = activeSkills == null ? emptyList() : List.copyOf(activeSkills);
        fallbackNotices = fallbackNotices == null ? emptyList() : List.copyOf(fallbackNotices);
        error = error == null ? "" : error;
    }

    public MessageMeta(List<String> activeSkills, boolean cancelled, String error) {
        this(activeSkills, emptyList(), cancelled, error);
    }

    public static MessageMeta empty() {
        return new MessageMeta(emptyList(), emptyList(), false, "");
    }
}
