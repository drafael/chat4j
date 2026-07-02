package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import java.util.function.Consumer;

public record AgentRunCallbacks(
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Consumer<CitationRef> onCitation,
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
        this(onToken, onThinkingToken, part -> {
        }, citation -> {
        }, activity -> {
        }, onComplete, onError);
    }

    public AgentRunCallbacks(
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<AgentToolActivity> onToolActivity,
            Runnable onComplete,
            Consumer<Exception> onError
    ) {
        this(onToken, onThinkingToken, part -> {
        }, citation -> {
        }, onToolActivity, onComplete, onError);
    }

    public AgentRunCallbacks(
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<AgentToolActivity> onToolActivity,
            Runnable onComplete,
            Consumer<Exception> onError
    ) {
        this(onToken, onThinkingToken, onPart, citation -> {
        }, onToolActivity, onComplete, onError);
    }

    public AgentRunCallbacks {
        onToken = onToken == null ? token -> {
        } : onToken;
        onThinkingToken = onThinkingToken == null ? token -> {
        } : onThinkingToken;
        onPart = onPart == null ? part -> {
        } : onPart;
        onCitation = onCitation == null ? citation -> {
        } : onCitation;
        onToolActivity = onToolActivity == null ? activity -> {
        } : onToolActivity;
        onComplete = onComplete == null ? () -> {
        } : onComplete;
        onError = onError == null ? error -> {
        } : onError;
    }
}
