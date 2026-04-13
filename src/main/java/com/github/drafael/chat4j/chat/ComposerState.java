package com.github.drafael.chat4j.chat;

import java.util.List;

public record ComposerState(String text, List<ComposerAttachment> attachments, List<String> activeSkills) {

    public ComposerState {
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        activeSkills = activeSkills == null ? List.of() : List.copyOf(activeSkills);
    }

    public boolean isEmpty() {
        return text.trim().isEmpty() && attachments.isEmpty() && activeSkills.isEmpty();
    }

    public static ComposerState empty() {
        return new ComposerState("", List.of(), List.of());
    }
}
