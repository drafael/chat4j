package com.github.drafael.chat4j.chat.agent;

import java.util.function.Consumer;

public record AgentRunCallbacks(
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<AgentToolActivity> onToolActivity,
        Runnable onComplete,
        Consumer<Exception> onError
) {

    public AgentRunCallbacks(
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Runnable onComplete,
            Consumer<Exception> onError
    ) {
        this(onToken, onThinkingToken, activity -> {
        }, onComplete, onError);
    }

    public AgentRunCallbacks {
        onToken = onToken == null ? token -> {
        } : onToken;
        onThinkingToken = onThinkingToken == null ? token -> {
        } : onThinkingToken;
        onToolActivity = onToolActivity == null ? activity -> {
        } : onToolActivity;
        onComplete = onComplete == null ? () -> {
        } : onComplete;
        onError = onError == null ? error -> {
        } : onError;
    }
}
