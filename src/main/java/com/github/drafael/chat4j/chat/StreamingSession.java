package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.provider.api.ProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.synchronizedList;

final class StreamingSession {
    final long sessionId;
    final UUID conversationId;
    final ProviderService provider;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicBoolean persisted = new AtomicBoolean(false);
    final AtomicBoolean terminalCallbackStarted = new AtomicBoolean(false);
    final StringBuilder response = new StringBuilder();
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
}
