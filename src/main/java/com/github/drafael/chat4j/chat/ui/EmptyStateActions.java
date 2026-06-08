package com.github.drafael.chat4j.chat.ui;

import java.util.function.Consumer;

public record EmptyStateActions(
        Consumer<String> setInputText,
        Runnable enableAgentMode,
        Runnable openAttachmentPicker,
        Runnable enableWebSearch,
        Runnable requestInputFocus
) {
}
