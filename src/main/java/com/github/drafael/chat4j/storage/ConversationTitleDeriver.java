package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.content.TextPart;

public class ConversationTitleDeriver {

    private static final String FALLBACK_TITLE = "New chat";
    private static final int MAX_TITLE_LENGTH = 50;
    private static final String ACTIVATED_SKILLS_PREFIX = "Activated skills:";

    public String derive(Message firstMessage) {
        if (firstMessage == null) {
            return FALLBACK_TITLE;
        }

        String titleCandidate = firstMessage.parts().stream()
                .filter(part -> part instanceof TextPart)
                .map(part -> ((TextPart) part).text())
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .filter(text -> !text.startsWith(ACTIVATED_SKILLS_PREFIX))
                .findFirst()
                .orElse(firstMessage.content().trim());

        if (titleCandidate.isBlank()) {
            return FALLBACK_TITLE;
        }

        return titleCandidate.length() > MAX_TITLE_LENGTH
                ? "%s...".formatted(titleCandidate.substring(0, MAX_TITLE_LENGTH))
                : titleCandidate;
    }
}
