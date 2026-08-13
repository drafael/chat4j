package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.chat.render.ThinkTagStreamParser;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.Collections.synchronizedList;

final class StreamingSession {
    final long sessionId;
    final UUID conversationId;
    volatile ProviderService provider;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicBoolean persisted = new AtomicBoolean(false);
    final AtomicBoolean terminalCallbackStarted = new AtomicBoolean(false);
    final AtomicBoolean activeRequestRegistered = new AtomicBoolean(false);
    final AtomicReference<AutoCloseable> activeRequest = new AtomicReference<>();
    final AtomicReference<Exception> requestCloseFailure = new AtomicReference<>();
    final StringBuilder response = new StringBuilder();
    final List<ContentPart> responseParts = synchronizedList(new ArrayList<>());
    final List<CitationRef> responseCitations = synchronizedList(new ArrayList<>());
    final StringBuilder thinking = new StringBuilder();
    final Object webSearchSourceLock = new Object();
    final StringBuilder webSearchActivity = new StringBuilder();
    final LinkedHashMap<String, WebSearchSource> webSearchSources = new LinkedHashMap<>();
    String webSearchQuery = "";
    boolean consultedSourceMode;
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
        if (request == null) {
            return;
        }
        activeRequestRegistered.set(true);
        if (!isLive()) {
            closeRequest(request);
            return;
        }
        AutoCloseable previous = activeRequest.getAndSet(request);
        if (previous != request) {
            closeRequest(previous);
        }
        if (!isLive() && activeRequest.compareAndSet(request, null)) {
            closeRequest(request);
        }
    }

    void clearActiveRequest() {
        activeRequest.set(null);
    }

    boolean hasRegisteredActiveRequest() {
        return activeRequestRegistered.get();
    }

    void clearProvider() {
        provider = null;
    }

    AutoCloseable detachActiveRequest() {
        return activeRequest.getAndSet(null);
    }

    boolean cancelActiveRequest() {
        AutoCloseable request = detachActiveRequest();
        if (request == null) {
            return false;
        }

        closeRequest(request);
        return true;
    }

    Exception requestCloseFailure() {
        return requestCloseFailure.get();
    }

    private void closeRequest(AutoCloseable request) {
        if (request == null) {
            return;
        }
        try {
            request.close();
        } catch (Exception e) {
            requestCloseFailure.compareAndSet(null, e);
        }
    }
}
