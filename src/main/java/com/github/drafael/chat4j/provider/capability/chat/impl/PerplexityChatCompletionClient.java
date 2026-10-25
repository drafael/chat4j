package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpCall;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

@Slf4j
public class PerplexityChatCompletionClient implements ChatCompletionClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[((?:[a-z_]+:)?\\d+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUPLICATE_LINKED_CITATION_PATTERN = Pattern.compile(
            "(\\s\\[(\\d+)]\\((<[^>]+>|[^)]+)\\))(?:\\s+\\[\\2]\\(\\3\\))+"
    );
    private static final Duration SYNC_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration BACKGROUND_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BACKGROUND_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final HttpTransport transport = new JavaNetHttpTransport();
    private final ProviderAttachmentSupport attachmentSupport;

    public PerplexityChatCompletionClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this.attachmentSupport = attachmentSupport;
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                WebSearchRequestOptions.disabled(),
                onToken,
                onThinkingToken,
                part -> {
                },
                citation -> {
                },
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (shouldStop(isCancelled)) {
            return;
        }
        String effort = PerplexityModelIds.agentEffort(runtime.selectedModel());
        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                isCancelled
        );
        if (shouldStop(isCancelled)) {
            return;
        }

        boolean background = !Strings.CS.equals(effort, "fast");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effort", effort);
        body.put("input", projectionPlan.messages().stream().map(this::toMessage).toList());
        body.put("stream", false);
        body.put("store", background);
        if (background) {
            body.put("background", true);
        }
        HttpExchangeRequest request = authorizedRequest(
                runtime,
                agentEndpoint(runtime.baseUrl()),
                "POST",
                JSON.writeString(body),
                background ? BACKGROUND_REQUEST_TIMEOUT : SYNC_REQUEST_TIMEOUT
        );
        HttpExchangeResponse response = send(request, isCancelled, registerActiveStream, clearActiveStream);
        requireSuccess(response, "Perplexity chat failed");
        Map<String, Object> root = readObject(response.body());
        if (background) {
            root = awaitBackgroundResponse(runtime, root, isCancelled, registerActiveStream, clearActiveStream);
        }
        if (shouldStop(isCancelled)) {
            return;
        }
        requireCompleted(root);
        FormattedResponse formatted = formatResponse(root);
        if (StringUtils.isBlank(formatted.text())) {
            throw new IllegalStateException("Perplexity response completed without assistant output");
        }
        if (shouldStop(isCancelled)) {
            return;
        }
        noOpIfNull(onToken).accept(formatted.text());
        for (CitationRef citation : formatted.citations()) {
            if (shouldStop(isCancelled)) {
                return;
            }
            noOpIfNull(onCitation).accept(citation);
        }
    }

    private Map<String, Object> awaitBackgroundResponse(
            ProviderRuntime runtime,
            Map<String, Object> submitted,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        Map<String, Object> root = submitted;
        String requestId = stringValue(root.get("id"));
        if (!isTerminal(root) && StringUtils.isBlank(requestId)) {
            throw new IllegalStateException("Perplexity background response is missing its id");
        }
        long deadline = System.nanoTime() + BACKGROUND_TIMEOUT.toNanos();
        Duration delay = POLL_INTERVAL;
        try {
            while (!isTerminal(root)) {
                waitBeforeNextPoll(isCancelled, delay, deadline);
                if (System.nanoTime() >= deadline) {
                    throw new HttpTimeoutException("Perplexity research timed out after %d minutes".formatted(BACKGROUND_TIMEOUT.toMinutes()));
                }
                HttpExchangeRequest poll = authorizedRequest(
                        runtime,
                        responseEndpoint(runtime.baseUrl(), requestId),
                        "GET",
                        "",
                        BACKGROUND_REQUEST_TIMEOUT
                );
                HttpExchangeResponse response = send(poll, isCancelled, registerActiveStream, clearActiveStream);
                if (response.statusCode() == 429) {
                    delay = retryDelay(response);
                    continue;
                }
                requireSuccess(response, "Perplexity research poll failed");
                root = readObject(response.body());
                delay = POLL_INTERVAL;
            }
            return root;
        } finally {
            if (!isTerminal(root)) {
                cancelBackgroundResponse(runtime, requestId);
            }
        }
    }

    private void cancelBackgroundResponse(ProviderRuntime runtime, String requestId) {
        // Local cancellation interrupts the worker; the separate server-side cancellation still needs to run.
        boolean interrupted = Thread.interrupted();
        try {
            HttpExchangeRequest request = authorizedRequest(
                    runtime,
                    "%s/cancel".formatted(responseEndpoint(runtime.baseUrl(), requestId)),
                    "POST",
                    "",
                    BACKGROUND_REQUEST_TIMEOUT
            );
            HttpExchangeResponse response = transport.send(request, () -> false);
            long deadline = System.nanoTime() + BACKGROUND_REQUEST_TIMEOUT.toNanos();
            while (response.statusCode() == 429 && System.nanoTime() < deadline) {
                waitBeforeNextPoll(() -> false, retryDelay(response), deadline);
                if (System.nanoTime() < deadline) {
                    response = transport.send(request, () -> false);
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Perplexity background cancellation returned HTTP {}; the run may still be active", response.statusCode());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Perplexity background cancellation failed ({}); the run may still be active", e.getClass().getSimpleName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isTerminal(Map<String, Object> root) {
        return Strings.CS.equalsAny(stringValue(root.get("status")), "completed", "failed", "cancelled", "incomplete");
    }

    private void requireCompleted(Map<String, Object> root) {
        String status = stringValue(root.get("status"));
        if (!Strings.CS.equals(status, "completed")) {
            Map<String, Object> error = objectMap(root.get("error"));
            String detail = error == null ? "" : stringValue(error.get("message"));
            throw new IllegalStateException("Perplexity response %s%s".formatted(
                    StringUtils.defaultIfBlank(status, "has no status"),
                    StringUtils.isBlank(detail) ? "" : ": %s".formatted(BoundedUtf8.presentation(detail, 500, 2_000))
            ));
        }
    }

    private Map<String, Object> toMessage(ProjectedMessage message) {
        return Map.of(
                "type", "message",
                "role", message.role().name().toLowerCase(Locale.ROOT),
                "content", message.parts().stream()
                        .map(AttachmentProjectionPlan::textFallback)
                        .filter(StringUtils::isNotBlank)
                        .collect(joining("\n"))
        );
    }

    private FormattedResponse formatResponse(Map<String, Object> root) {
        String answer = outputItems(root)
                .filter(item -> Strings.CS.equals(stringValue(item.get("type")), "message"))
                .filter(item -> Strings.CS.equals(stringValue(item.get("role")), "assistant"))
                .flatMap(item -> objectItems(item.get("content")))
                .filter(part -> Strings.CS.equals(stringValue(part.get("type")), "output_text"))
                .map(part -> stringValue(part.get("text")))
                .collect(joining("\n"));
        CitationAccumulator accumulator = new CitationAccumulator();
        Map<String, CitationRef> byMarker = new LinkedHashMap<>();
        Map<Integer, CitationRef> byNumber = new LinkedHashMap<>();
        outputItems(root)
                .filter(item -> Strings.CS.equals(stringValue(item.get("type")), "search_results"))
                .flatMap(item -> objectItems(item.get("results")))
                .forEach(result -> addSource(result, accumulator, byMarker, byNumber));
        List<CitationRef> citations = List.copyOf(byNumber.values());
        if (citations.isEmpty()) {
            return new FormattedResponse(answer, citations);
        }
        String linked = linkInlineCitationMarkers(answer, byMarker);
        String refs = CITATION_MARKER_PATTERN.matcher(answer).find() ? "" : " %s".formatted(citations.stream()
                .map(this::sourceReference)
                .collect(joining(" ")));
        String sourceList = citations.stream()
                .map(citation -> "%d. %s".formatted(citation.number(), sourceDisplay(citation)))
                .collect(joining("\n"));
        return new FormattedResponse("%s%s\n\nSources:\n%s".formatted(linked.trim(), refs, sourceList).trim(), citations);
    }

    private void addSource(
            Map<String, Object> result,
            CitationAccumulator accumulator,
            Map<String, CitationRef> byMarker,
            Map<Integer, CitationRef> byNumber
    ) {
        String url = normalizeUrl(stringValue(result.get("url")));
        String snippet = StringUtils.defaultIfBlank(stringValue(result.get("snippet")), stringValue(result.get("content")));
        UrlCitationMapper.fromUrl(stringValue(result.get("title")), url, snippet).ifPresent(source -> {
            CitationRef citation = accumulator.add(source);
            byNumber.putIfAbsent(citation.number(), citation);
            Object id = result.get("id");
            if (id instanceof Number || id instanceof String) {
                String marker = id.toString();
                String type = StringUtils.defaultIfBlank(stringValue(result.get("source")), "web").toLowerCase(Locale.ROOT);
                byMarker.putIfAbsent("%s:%s".formatted(type, marker), citation);
                if (Strings.CS.equals(type, "web")) {
                    byMarker.putIfAbsent(marker, citation);
                }
            }
        });
    }

    private String linkInlineCitationMarkers(String answer, Map<String, CitationRef> byMarker) {
        Matcher matcher = CITATION_MARKER_PATTERN.matcher(answer);
        StringBuilder linked = new StringBuilder();
        while (matcher.find()) {
            CitationRef citation = byMarker.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (citation == null) {
                matcher.appendReplacement(linked, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String prefix = matcher.start() > 0 && Character.isWhitespace(answer.charAt(matcher.start() - 1)) ? "" : " ";
            matcher.appendReplacement(linked, Matcher.quoteReplacement("%s%s".formatted(prefix, sourceReference(citation))));
        }
        matcher.appendTail(linked);
        return DUPLICATE_LINKED_CITATION_PATTERN.matcher(linked).replaceAll("$1");
    }

    private String sourceReference(CitationRef citation) {
        return "[%d](%s)".formatted(citation.number(), markdownLinkDestination(citation.url()));
    }

    private String sourceDisplay(CitationRef citation) {
        String title = citation.title().replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
        return StringUtils.isBlank(title)
                ? markdownLinkDestination(citation.url())
                : "[%s](%s)".formatted(title, markdownLinkDestination(citation.url()));
    }

    private String normalizeUrl(String url) {
        String normalized = StringUtils.trimToEmpty(url).replaceAll("[.,;:]+$", "");
        while (normalized.endsWith("/") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Stream<Map<String, Object>> outputItems(Map<String, Object> root) {
        return objectItems(root.get("output"));
    }

    private Stream<Map<String, Object>> objectItems(Object value) {
        return value instanceof List<?> items
                ? items.stream().map(this::objectMap).filter(Objects::nonNull)
                : Stream.empty();
    }

    private HttpExchangeRequest authorizedRequest(ProviderRuntime runtime, String endpoint, String method, String body, Duration timeout) {
        return new HttpExchangeRequest(
                method,
                URI.create(endpoint),
                Map.of("Authorization", "Bearer %s".formatted(runtime.apiKey()), "Content-Type", "application/json"),
                HttpBody.utf8(body),
                timeout,
                0
        );
    }

    private String agentEndpoint(String baseUrl) {
        String normalized = Strings.CS.removeEnd(StringUtils.defaultIfBlank(baseUrl, "https://api.perplexity.ai").trim(), "/");
        return normalized.endsWith("/v1") ? "%s/agent".formatted(normalized) : "%s/v1/agent".formatted(normalized);
    }

    private String responseEndpoint(String baseUrl, String requestId) {
        return "%s/%s".formatted(agentEndpoint(baseUrl), URLEncoder.encode(requestId, StandardCharsets.UTF_8));
    }

    private Duration retryDelay(HttpExchangeResponse response) {
        long seconds = NumberUtils.toLong(response.firstHeader("Retry-After"), POLL_INTERVAL.toSeconds());
        return Duration.ofSeconds(Math.max(POLL_INTERVAL.toSeconds(), seconds));
    }

    private void waitBeforeNextPoll(BooleanSupplier isCancelled, Duration delay, long runDeadline) throws InterruptedException {
        long deadline = Math.min(runDeadline, System.nanoTime() + delay.toNanos());
        while (System.nanoTime() < deadline) {
            if (shouldStop(isCancelled)) {
                throw new InterruptedException("Perplexity research cancelled");
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            try {
                Thread.sleep(Math.min(Math.max(remainingMillis, 1L), 100L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private HttpExchangeResponse send(
            HttpExchangeRequest request,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (shouldStop(isCancelled)) {
            throw new InterruptedException("Perplexity chat cancelled");
        }
        try (HttpCall call = transport.open(request)) {
            registerActiveStream.accept(call);
            try {
                return call.await(isCancelled);
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void requireSuccess(HttpExchangeResponse response, String prefix) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpErrorMessage(prefix, response));
        }
    }

    private String httpErrorMessage(String prefix, HttpExchangeResponse response) {
        String message = "";
        try {
            Map<String, Object> root = readObject(response.body());
            Map<String, Object> error = objectMap(root.get("error"));
            message = StringUtils.defaultIfBlank(error == null ? "" : stringValue(error.get("message")), stringValue(root.get("message")));
        } catch (Exception ignored) {
            // Use the fixed status-only fallback for unrecognized responses.
        }
        String safeMessage = BoundedUtf8.presentation(message, 500, 2_000);
        return StringUtils.isBlank(safeMessage)
                ? "%s: HTTP %d".formatted(prefix, response.statusCode())
                : "%s: HTTP %d: %s".formatted(prefix, response.statusCode(), safeMessage);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(byte[] json) {
        return JSON.read(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private <T> Consumer<T> noOpIfNull(Consumer<T> consumer) {
        return consumer == null ? ignored -> {
        } : consumer;
    }

    private String markdownLinkDestination(String url) {
        return "<%s>".formatted(StringUtils.defaultString(url).replace(">", "%3E"));
    }

    private record FormattedResponse(String text, List<CitationRef> citations) {
    }
}
