package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static java.util.Collections.emptyList;

public record ComposerState(String text, List<ComposerAttachment> attachments, List<String> activeSkills) {

    public ComposerState {
        text = text == null ? "" : text;
        attachments = attachments == null ? emptyList() : List.copyOf(attachments);
        activeSkills = activeSkills == null ? emptyList() : List.copyOf(activeSkills);
    }

    public boolean isEmpty() {
        return StringUtils.isBlank(text) && attachments.isEmpty() && activeSkills.isEmpty();
    }

    @Override
    public String toString() {
        return "ComposerState[text=<masked>, attachments=%d, activeSkills=%d]"
                .formatted(attachments.size(), activeSkills.size());
    }

    public static ComposerState empty() {
        return new ComposerState("", emptyList(), emptyList());
    }
}
