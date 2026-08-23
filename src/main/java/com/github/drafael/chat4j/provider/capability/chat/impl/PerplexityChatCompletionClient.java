package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpCall;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

public class PerplexityChatCompletionClient implements ChatCompletionClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern DUPLICATE_LINKED_CITATION_PATTERN = Pattern.compile(
            "(\\s\\[(\\d+)]\\((<[^>]+>|[^)]+)\\))(?:\\s+\\[\\2]\\(\\3\\))+"
    );
    private static final String DEEP_RESEARCH_MODEL = "sonar-deep-research";
    private static final Duration SYNC_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration ASYNC_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEEP_RESEARCH_ASYNC_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEEP_RESEARCH_POLL_INTERVAL = Duration.ofSeconds(2);

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
        Consumer<String> safeOnToken = noOpIfNull(onToken);
        Consumer<CitationRef> safeOnCitation = noOpIfNull(onCitation);

        if (shouldStop(isCancelled)) {
            return;
        }

        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                isCancelled
        );
        if (shouldStop(isCancelled)) {
            return;
        }
        if (isDeepResearchModel(runtime.selectedModel())) {
            streamDeepResearchCompletion(
                    runtime,
                    projectionPlan,
                    safeOnToken,
                    safeOnCitation,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
            return;
        }

        HttpExchangeRequest request = authorizedRequest(
                runtime,
                chatCompletionsEndpoint(runtime.baseUrl()),
                "POST",
                buildRequestBody(runtime, projectionPlan, true),
                SYNC_REQUEST_TIMEOUT
        );

        HttpExchangeResponse response = send(request, isCancelled, registerActiveStream, clearActiveStream);
        if (shouldStop(isCancelled)) {
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpErrorMessage("Perplexity chat failed", response));
        }

        emitFormattedResponse(response.bodyText(), safeOnToken, safeOnCitation, isCancelled);
    }

    private void streamDeepResearchCompletion(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        HttpExchangeRequest request = authorizedRequest(
                runtime,
                asyncSonarEndpoint(runtime.baseUrl()),
                "POST",
                buildAsyncRequestBody(runtime, projectionPlan),
                ASYNC_REQUEST_TIMEOUT
        );

        HttpExchangeResponse response = send(request, isCancelled, registerActiveStream, clearActiveStream);
        if (shouldStop(isCancelled)) {
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpErrorMessage("Perplexity deep research submit failed", response));
        }

        Map<String, Object> submitted = readObject(response.body());
        Map<String, Object> completedResponse = completedAsyncResponse(submitted);
        if (completedResponse != null) {
            emitFormattedResponse(JSON.writeString(completedResponse), onToken, onCitation, isCancelled);
            return;
        }

        String requestId = stringValue(submitted.get("id"));
        if (StringUtils.isBlank(requestId)) {
            throw new IllegalStateException("Perplexity deep research submit failed: missing async request id");
        }

        Map<String, Object> asyncResponse = pollAsyncResponse(runtime, requestId, isCancelled, registerActiveStream, clearActiveStream);
        emitFormattedResponse(JSON.writeString(asyncResponse), onToken, onCitation, isCancelled);
    }

    private HttpExchangeRequest authorizedRequest(
            ProviderRuntime runtime,
            String endpoint,
            String method,
            String body,
            Duration timeout
    ) {
        return new HttpExchangeRequest(
                method,
                URI.create(endpoint),
                java.util.Map.of(
                        "Authorization", "Bearer %s".formatted(runtime.apiKey()),
                        "Content-Type", "application/json"
                ),
                HttpBody.utf8(body),
                timeout,
                0
        );
    }

    private void emitFormattedResponse(
            String responseBody,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) throws Exception {
        if (shouldStop(isCancelled)) {
            return;
        }
        FormattedResponse formattedResponse = formatResponse(responseBody);
        if (shouldStop(isCancelled)) {
            return;
        }
        if (StringUtils.isNotBlank(formattedResponse.text())) {
            onToken.accept(formattedResponse.text());
        }
        for (CitationRef citation : formattedResponse.citations()) {
            if (shouldStop(isCancelled)) {
                return;
            }
            onCitation.accept(citation);
        }
    }

    private String buildRequestBody(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            boolean includeStream
    ) {
        return JSON.writeString(requestBody(runtime, projectionPlan, includeStream));
    }

    private Map<String, Object> requestBody(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            boolean includeStream
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", runtime.selectedModel());
        if (includeStream) {
            root.put("stream", false);
        }
        root.put("messages", projectionPlan.messages().stream().map(this::toMessage).toList());
        return root;
    }

    private String buildAsyncRequestBody(ProviderRuntime runtime, AttachmentProjectionPlan projectionPlan) {
        return JSON.writeString(Map.of("request", requestBody(runtime, projectionPlan, false)));
    }

    private Map<String, Object> toMessage(ProjectedMessage message) {
        return Map.of(
                "role", message.role().name().toLowerCase(),
                "content", message.parts().stream()
                        .map(AttachmentProjectionPlan::textFallback)
                        .filter(StringUtils::isNotBlank)
                        .collect(joining("\n"))
        );
    }

    private FormattedResponse formatResponse(String body) throws Exception {
        Map<String, Object> root = readObject(body);
        String answer = answer(root);
        List<Source> sources = sources(root);
        List<CitationRef> citations = citations(sources);
        if (sources.isEmpty()) {
            return new FormattedResponse(answer, citations);
        }

        String linkedAnswer = linkInlineCitationMarkers(answer, sources);
        String sourceRefs = hasCitationMarkers(answer) ? "" : " %s".formatted(sourceReferences(sources));
        String sourceList = numberedSourceList(sources);
        return new FormattedResponse("%s%s\n\nSources:\n%s".formatted(
                StringUtils.defaultString(linkedAnswer).trim(),
                sourceRefs,
                sourceList
        ).trim(), citations);
    }

    private String numberedSourceList(List<Source> sources) {
        return range(0, sources.size())
                .mapToObj(index -> "%d. %s".formatted(index + 1, sources.get(index).display()))
                .collect(joining("\n"));
    }

    private List<CitationRef> citations(List<Source> sources) {
        if (sources.isEmpty()) {
            return emptyList();
        }

        CitationAccumulator citationAccumulator = new CitationAccumulator();
        return sources.stream()
                .map(Source::citation)
                .flatMap(Optional::stream)
                .map(citationAccumulator::add)
                .toList();
    }

    private List<Source> sources(Map<String, Object> root) {
        Set<String> seenUrls = new LinkedHashSet<>();
        List<Source> sources = new ArrayList<>();

        if (root.get("search_results") instanceof List<?> searchResults) {
            searchResults.stream().map(this::objectMap).filter(java.util.Objects::nonNull).forEach(result -> addSource(
                    sources,
                    seenUrls,
                    stringValue(result.get("title")),
                    stringValue(result.get("url")),
                    searchResultCitedText(result)
            ));
        }
        if (root.get("citations") instanceof List<?> citations) {
            citations.forEach(citation -> addSource(sources, seenUrls, "", stringValue(citation), ""));
        }
        return sources;
    }

    private String searchResultCitedText(Map<String, Object> result) {
        String snippet = stringValue(result.get("snippet"));
        return StringUtils.isBlank(snippet) ? stringValue(result.get("content")) : snippet;
    }

    private void addSource(List<Source> sources, Set<String> seenUrls, String title, String url, String snippet) {
        String normalizedUrl = normalizeUrl(url);
        if (StringUtils.isBlank(normalizedUrl) || !seenUrls.add(normalizedUrl)) {
            return;
        }

        sources.add(new Source(StringUtils.trimToEmpty(title), normalizedUrl, StringUtils.trimToEmpty(snippet)));
    }

    private String normalizeUrl(String url) {
        String normalized = StringUtils.trimToEmpty(url).replaceAll("[.,;:]+$", "");
        while (normalized.endsWith("/") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String linkInlineCitationMarkers(String answer, List<Source> sources) {
        if (StringUtils.isBlank(answer) || sources.isEmpty()) {
            return StringUtils.defaultString(answer);
        }

        Matcher matcher = CITATION_MARKER_PATTERN.matcher(answer);
        StringBuilder linked = new StringBuilder();
        while (matcher.find()) {
            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(matcher.group(1)) - 1;
            } catch (NumberFormatException e) {
                matcher.appendReplacement(linked, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (sourceIndex < 0 || sourceIndex >= sources.size()) {
                matcher.appendReplacement(linked, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            String prefix = matcher.start() > 0 && Character.isWhitespace(answer.charAt(matcher.start() - 1)) ? "" : " ";
            String replacement = "%s[%d](%s)".formatted(
                    prefix,
                    sourceIndex + 1,
                    markdownLinkDestination(sources.get(sourceIndex).url())
            );
            matcher.appendReplacement(linked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(linked);
        return collapseDuplicateLinkedCitations(linked.toString());
    }

    private String collapseDuplicateLinkedCitations(String text) {
        return DUPLICATE_LINKED_CITATION_PATTERN.matcher(text).replaceAll("$1");
    }

    private boolean hasCitationMarkers(String answer) {
        return StringUtils.isNotBlank(answer) && CITATION_MARKER_PATTERN.matcher(answer).find();
    }

    private String sourceReferences(List<Source> sources) {
        StringBuilder references = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (!references.isEmpty()) {
                references.append(" ");
            }
            references.append("[%d](%s)".formatted(i + 1, markdownLinkDestination(sources.get(i).url())));
        }
        return references.toString();
    }

    private String chatCompletionsEndpoint(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        return "%s/chat/completions".formatted(normalizedBaseUrl);
    }

    private String asyncSonarEndpoint(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        return normalizedBaseUrl.endsWith("/v1")
                ? "%s/async/sonar".formatted(normalizedBaseUrl)
                : "%s/v1/async/sonar".formatted(normalizedBaseUrl);
    }

    private String asyncSonarRequestEndpoint(String baseUrl, String requestId) {
        return "%s/%s".formatted(asyncSonarEndpoint(baseUrl), URLEncoder.encode(requestId, StandardCharsets.UTF_8));
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalizedBaseUrl = StringUtils.defaultIfBlank(baseUrl, "https://api.perplexity.ai").trim();
        return Strings.CS.removeEnd(normalizedBaseUrl, "/");
    }

    private Map<String, Object> pollAsyncResponse(
            ProviderRuntime runtime,
            String requestId,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + DEEP_RESEARCH_ASYNC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (shouldStop(isCancelled)) {
                throw new InterruptedException("Perplexity deep research cancelled");
            }

            HttpExchangeRequest request = authorizedRequest(
                    runtime,
                    asyncSonarRequestEndpoint(runtime.baseUrl(), requestId),
                    "GET",
                    "",
                    ASYNC_REQUEST_TIMEOUT
            );
            HttpExchangeResponse response = send(request, isCancelled, registerActiveStream, clearActiveStream);
            if (shouldStop(isCancelled)) {
                throw new InterruptedException("Perplexity deep research cancelled");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(httpErrorMessage("Perplexity deep research poll failed", response));
            }

            Map<String, Object> root = readObject(response.body());
            Map<String, Object> completedResponse = completedAsyncResponse(root);
            if (completedResponse != null) {
                return completedResponse;
            }
            waitBeforeNextPoll(isCancelled);
        }

        throw new HttpTimeoutException(
                "Perplexity sonar-deep-research timed out after %d minute(s); try a narrower prompt or retry later"
                        .formatted(DEEP_RESEARCH_ASYNC_TIMEOUT.toMinutes())
        );
    }

    private Map<String, Object> completedAsyncResponse(Map<String, Object> root) {
        String errorMessage = stringValue(root.get("error_message"));
        if (root.get("failed_at") != null || Strings.CI.equals(stringValue(root.get("status")), "FAILED")) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(errorMessage, "Perplexity deep research failed"));
        }

        Map<String, Object> response = objectMap(root.get("response"));
        if (response != null && response.containsKey("choices")) {
            return response;
        }

        if (root.get("completed_at") != null) {
            throw new IllegalStateException("Perplexity deep research completed without a response");
        }

        return null;
    }

    private void waitBeforeNextPoll(BooleanSupplier isCancelled) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + DEEP_RESEARCH_POLL_INTERVAL.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (shouldStop(isCancelled)) {
                throw new InterruptedException("Perplexity deep research cancelled");
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
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
            try {
                registerActiveStream.accept(call);
            } catch (RuntimeException e) {
                call.close();
                throw e;
            }
            try {
                return call.await(isCancelled);
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private String httpErrorMessage(String prefix, HttpExchangeResponse response) {
        String message = "";
        try {
            Map<String, Object> root = readObject(response.body());
            Map<String, Object> error = objectMap(root.get("error"));
            message = StringUtils.defaultIfBlank(
                    error == null ? "" : stringValue(error.get("message")),
                    stringValue(root.get("message"))
            );
        } catch (Exception ignored) {
            // Use the fixed status-only fallback for unrecognized responses.
        }
        String safeMessage = BoundedUtf8.presentation(message, 500, 2_000);
        return StringUtils.isBlank(safeMessage)
                ? "%s: HTTP %d".formatted(prefix, response.statusCode())
                : "%s: HTTP %d: %s".formatted(prefix, response.statusCode(), safeMessage);
    }

    private String answer(Map<String, Object> root) {
        Object choicesValue = root.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            return "";
        }
        Map<String, Object> choice = objectMap(choices.getFirst());
        Map<String, Object> message = choice == null ? null : objectMap(choice.get("message"));
        return message == null ? "" : stringValue(message.get("content"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(byte[] json) {
        return JSON.read(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(String json) {
        return JSON.read(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private boolean isDeepResearchModel(String modelId) {
        return Strings.CI.equals(StringUtils.trimToEmpty(modelId), DEEP_RESEARCH_MODEL);
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private <T> Consumer<T> noOpIfNull(Consumer<T> consumer) {
        return consumer == null ? ignored -> {
        } : consumer;
    }

    private static String markdownLinkDestination(String url) {
        return "<%s>".formatted(StringUtils.defaultString(url).replace(">", "%3E"));
    }

    private record FormattedResponse(String text, List<CitationRef> citations) {
    }

    private record Source(String title, String url, String snippet) {
        private String display() {
            return StringUtils.isBlank(title)
                    ? markdownLinkDestination(url)
                    : "[%s](%s)".formatted(markdownLinkText(title), markdownLinkDestination(url));
        }

        private Optional<CitationRef> citation() {
            return UrlCitationMapper.fromUrl(title, url, snippet);
        }

        private String markdownLinkText(String text) {
            return StringUtils.defaultString(text)
                    .replace("[", "\\[")
                    .replace("]", "\\]");
        }
    }
}
