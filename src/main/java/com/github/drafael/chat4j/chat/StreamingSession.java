package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.chat.render.ThinkTagStreamParser;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.Collections.synchronizedList;

final class StreamingSession {
    final long sessionId;
    final UUID conversationId;
    final ProviderService provider;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicBoolean persisted = new AtomicBoolean(false);
    final AtomicBoolean terminalCallbackStarted = new AtomicBoolean(false);
    final AtomicBoolean activeRequestRegistered = new AtomicBoolean(false);
    final AtomicReference<AutoCloseable> activeRequest = new AtomicReference<>();
    final StringBuilder response = new StringBuilder();
    final List<ContentPart> responseParts = synchronizedList(new ArrayList<>());
    final StringBuilder thinking = new StringBuilder();
    final StringBuilder webSearchActivity = new StringBuilder();
    final List<AgentToolActivity> agentToolActivities = synchronizedList(new ArrayList<>());
    final ThinkTagStreamParser thinkTagParser = new ThinkTagStreamParser();
    volatile Thread worker;
    volatile boolean finished = false;

    StreamingSession(long sessionId, UUID conversationId, ProviderService provider) {
        this.sessionId = sessionId;
        this.conversationId = conversationId;
        this.provider = provider;
    }

    boolean isLive() {
        return !finished && !cancelled.get();
    }

    boolean beginTerminalCallback() {
        return isLive() && terminalCallbackStarted.compareAndSet(false, true);
    }

    void registerActiveRequest(AutoCloseable request) {
        activeRequestRegistered.set(true);
        activeRequest.set(request);
    }

    void clearActiveRequest() {
        activeRequest.set(null);
    }

    boolean hasRegisteredActiveRequest() {
        return activeRequestRegistered.get();
    }

    boolean cancelActiveRequest() {
        AutoCloseable request = activeRequest.getAndSet(null);
        if (request == null) {
            return false;
        }

        try {
            request.close();
        } catch (Exception ignored) {
        }
        return true;
    }
}
