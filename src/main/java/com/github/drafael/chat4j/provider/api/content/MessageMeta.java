package com.github.drafael.chat4j.provider.api.content;

import java.util.List;
import static java.util.Collections.emptyList;

public record MessageMeta(
    List<String> activeSkills,
    List<String> fallbackNotices,
    boolean cancelled,
    String error,
    String assistantThinking,
    String assistantWebSearch
) {

    public MessageMeta {
        activeSkills = activeSkills == null ? emptyList() : List.copyOf(activeSkills);
        fallbackNotices = fallbackNotices == null ? emptyList() : List.copyOf(fallbackNotices);
        error = error == null ? "" : error;
        assistantThinking = assistantThinking == null ? "" : assistantThinking;
        assistantWebSearch = assistantWebSearch == null ? "" : assistantWebSearch;
    }

    public MessageMeta(List<String> activeSkills, boolean cancelled, String error) {
        this(activeSkills, emptyList(), cancelled, error, "", "");
    }

    public MessageMeta(List<String> activeSkills, List<String> fallbackNotices, boolean cancelled, String error) {
        this(activeSkills, fallbackNotices, cancelled, error, "", "");
    }

    public MessageMeta(
            List<String> activeSkills,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            String assistantThinking
    ) {
        this(activeSkills, fallbackNotices, cancelled, error, assistantThinking, "");
    }

    public static MessageMeta empty() {
        return new MessageMeta(emptyList(), emptyList(), false, "", "", "");
    }
}
