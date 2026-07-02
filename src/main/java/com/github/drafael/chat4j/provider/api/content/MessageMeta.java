package com.github.drafael.chat4j.provider.api.content;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.emptyList;

public record MessageMeta(
    List<String> activeSkills,
    List<String> fallbackNotices,
    boolean cancelled,
    String error,
    String assistantThinking,
    String assistantWebSearch,
    List<AgentToolActivityMeta> agentToolActivities,
    List<CitationRef> citations
) {

    public MessageMeta {
        activeSkills = activeSkills == null ? emptyList() : List.copyOf(activeSkills);
        fallbackNotices = fallbackNotices == null ? emptyList() : List.copyOf(fallbackNotices);
        error = error == null ? "" : error;
        assistantThinking = assistantThinking == null ? "" : assistantThinking;
        assistantWebSearch = assistantWebSearch == null ? "" : assistantWebSearch;
        agentToolActivities = agentToolActivities == null
                ? emptyList()
                : agentToolActivities.stream().filter(Objects::nonNull).toList();
        citations = citations == null
                ? emptyList()
                : citations.stream().filter(Objects::nonNull).toList();
    }

    public MessageMeta(List<String> activeSkills, boolean cancelled, String error) {
        this(activeSkills, emptyList(), cancelled, error, "", "", emptyList());
    }

    public MessageMeta(List<String> activeSkills, List<String> fallbackNotices, boolean cancelled, String error) {
        this(activeSkills, fallbackNotices, cancelled, error, "", "", emptyList());
    }

    public MessageMeta(
            List<String> activeSkills,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            String assistantThinking
    ) {
        this(activeSkills, fallbackNotices, cancelled, error, assistantThinking, "", emptyList(), emptyList());
    }

    public MessageMeta(
            List<String> activeSkills,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            String assistantThinking,
            String assistantWebSearch
    ) {
        this(activeSkills, fallbackNotices, cancelled, error, assistantThinking, assistantWebSearch, emptyList(), emptyList());
    }

    public MessageMeta(
            List<String> activeSkills,
            List<String> fallbackNotices,
            boolean cancelled,
            String error,
            String assistantThinking,
            String assistantWebSearch,
            List<AgentToolActivityMeta> agentToolActivities
    ) {
        this(activeSkills, fallbackNotices, cancelled, error, assistantThinking, assistantWebSearch, agentToolActivities, emptyList());
    }

    public static MessageMeta empty() {
        return new MessageMeta(emptyList(), emptyList(), false, "", "", "", emptyList(), emptyList());
    }
}
