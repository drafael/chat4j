package com.github.drafael.chat4j.chat;

import java.util.List;
import static java.util.Collections.emptyList;

public record ComposerState(String text, List<ComposerAttachment> attachments, List<String> activeSkills) {

    public ComposerState {
        text = text == null ? "" : text;
        attachments = attachments == null ? emptyList() : List.copyOf(attachments);
        activeSkills = activeSkills == null ? emptyList() : List.copyOf(activeSkills);
    }

    public boolean isEmpty() {
        return text.trim().isEmpty() && attachments.isEmpty() && activeSkills.isEmpty();
    }

    public static ComposerState empty() {
        return new ComposerState("", emptyList(), emptyList());
    }
}
