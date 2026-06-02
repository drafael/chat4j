package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

public class PerplexityChatCompletionClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern DUPLICATE_LINKED_CITATION_PATTERN = Pattern.compile(
            "(\\s\\[(\\d+)]\\((<[^>]+>|[^)]+)\\))(?:\\s+\\[\\2]\\(\\3\\))+"
    );
    private static final String DEEP_RESEARCH_MODEL = "sonar-deep-research";
    private static final Duration SYNC_REQUEST_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration ASYNC_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEEP_RESEARCH_ASYNC_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEEP_RESEARCH_POLL_INTERVAL = Duration.ofSeconds(2);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
        if (shouldStop(isCancelled)) {
            return;
        }

        if (isDeepResearchModel(runtime.selectedModel())) {
            streamDeepResearchCompletion(
                    runtime,
                    history,
                    onToken,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
            return;
        }

        HttpRequest request = authorizedRequest(runtime, chatCompletionsEndpoint(runtime.baseUrl()), SYNC_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(runtime, history, true)))
                .build();

        HttpResponse<String> response = send(request, isCancelled, registerActiveStream, clearActiveStream);
        if (shouldStop(isCancelled)) {
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpErrorMessage("Perplexity chat failed", response));
        }

        emitFormattedResponse(response.body(), onToken);
    }

    private void streamDeepResearchCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            Consumer<String> onToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        HttpRequest request = authorizedRequest(runtime, asyncSonarEndpoint(runtime.baseUrl()), ASYNC_REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(buildAsyncRequestBody(runtime, history)))
                .build();

        HttpResponse<String> response = send(request, isCancelled, registerActiveStream, clearActiveStream);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(httpErrorMessage("Perplexity deep research submit failed", response));
        }

        JsonNode submitted = JSON.readTree(response.body());
        JsonNode completedResponse = completedAsyncResponse(submitted);
        if (completedResponse != null) {
            emitFormattedResponse(JSON.writeValueAsString(completedResponse), onToken);
            return;
        }

        String requestId = submitted.path("id").asText("");
        if (StringUtils.isBlank(requestId)) {
            throw new IllegalStateException("Perplexity deep research submit failed: missing async request id");
        }

        JsonNode asyncResponse = pollAsyncResponse(runtime, requestId, isCancelled, registerActiveStream, clearActiveStream);
        emitFormattedResponse(JSON.writeValueAsString(asyncResponse), onToken);
    }

    private HttpRequest.Builder authorizedRequest(ProviderRuntime runtime, String endpoint, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer %s".formatted(runtime.apiKey()))
                .header("Content-Type", "application/json");
    }

    private void emitFormattedResponse(String responseBody, Consumer<String> onToken) throws Exception {
        String content = formatResponse(responseBody);
        if (StringUtils.isNotBlank(content)) {
            onToken.accept(content);
        }
    }

    private String buildRequestBody(ProviderRuntime runtime, List<Message> history, boolean includeStream) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.selectedModel());
        if (includeStream) {
            root.put("stream", false);
        }

        ArrayNode messages = JSON.createArrayNode();
        history.stream()
                .map(this::toMessageNode)
                .forEach(messages::add);
        root.set("messages", messages);
        return JSON.writeValueAsString(root);
    }

    private String buildAsyncRequestBody(ProviderRuntime runtime, List<Message> history) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.set("request", JSON.readTree(buildRequestBody(runtime, history, false)));
        return JSON.writeValueAsString(root);
    }

    private ObjectNode toMessageNode(Message message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("role", message.role().name().toLowerCase());
        node.put("content", message.content());
        return node;
    }

    private String formatResponse(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        String answer = root.path("choices").path(0).path("message").path("content").asText("");
        List<Source> sources = sources(root);
        if (sources.isEmpty()) {
            return answer;
        }

        String linkedAnswer = linkInlineCitationMarkers(answer, sources);
        String sourceRefs = hasCitationMarkers(answer) ? "" : " %s".formatted(sourceReferences(sources));
        String sourceList = numberedSourceList(sources);
        return "%s%s\n\nSources:\n%s".formatted(
                StringUtils.defaultString(linkedAnswer).trim(),
                sourceRefs,
                sourceList
        ).trim();
    }

    private String numberedSourceList(List<Source> sources) {
        return range(0, sources.size())
                .mapToObj(index -> "%d. %s".formatted(index + 1, sources.get(index).display()))
                .collect(joining("\n"));
    }

    private List<Source> sources(JsonNode root) {
        Set<String> seenUrls = new LinkedHashSet<>();
        List<Source> sources = new ArrayList<>();

        JsonNode searchResults = root.path("search_results");
        if (searchResults.isArray()) {
            searchResults.forEach(result -> addSource(
                    sources,
                    seenUrls,
                    result.path("title").asText(""),
                    result.path("url").asText("")
            ));
        }

        JsonNode citations = root.path("citations");
        if (citations.isArray()) {
            citations.forEach(citation -> addSource(sources, seenUrls, "", citation.asText("")));
        }

        return sources;
    }

    private void addSource(List<Source> sources, Set<String> seenUrls, String title, String url) {
        String normalizedUrl = normalizeUrl(url);
        if (StringUtils.isBlank(normalizedUrl) || !seenUrls.add(normalizedUrl)) {
            return;
        }

        sources.add(new Source(StringUtils.trimToEmpty(title), normalizedUrl));
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
            int sourceIndex = Integer.parseInt(matcher.group(1)) - 1;
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
            if (references.length() > 0) {
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

    private JsonNode pollAsyncResponse(
            ProviderRuntime runtime,
            String requestId,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + DEEP_RESEARCH_ASYNC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (shouldStop(isCancelled)) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Perplexity deep research cancelled");
            }

            HttpRequest request = authorizedRequest(
                    runtime,
                    asyncSonarRequestEndpoint(runtime.baseUrl(), requestId),
                    ASYNC_REQUEST_TIMEOUT
            ).GET().build();
            HttpResponse<String> response = send(request, isCancelled, registerActiveStream, clearActiveStream);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(httpErrorMessage("Perplexity deep research poll failed", response));
            }

            JsonNode root = JSON.readTree(response.body());
            JsonNode completedResponse = completedAsyncResponse(root);
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

    private JsonNode completedAsyncResponse(JsonNode root) {
        String errorMessage = root.path("error_message").asText("");
        if (hasValue(root.path("failed_at")) || Strings.CI.equals(root.path("status").asText(""), "FAILED")) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(errorMessage, "Perplexity deep research failed"));
        }

        JsonNode response = root.path("response");
        if (!response.isMissingNode() && !response.isNull() && response.has("choices")) {
            return response;
        }

        if (hasValue(root.path("completed_at"))) {
            throw new IllegalStateException("Perplexity deep research completed without a response");
        }

        return null;
    }

    private boolean hasValue(JsonNode node) {
        return !node.isMissingNode() && !node.isNull();
    }

    private void waitBeforeNextPoll(BooleanSupplier isCancelled) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + DEEP_RESEARCH_POLL_INTERVAL.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (shouldStop(isCancelled)) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Perplexity deep research cancelled");
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            Thread.sleep(Math.min(Math.max(remainingMillis, 1L), 100L));
        }
    }

    private HttpResponse<String> send(
            HttpRequest request,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        registerActiveStream.accept(() -> future.cancel(true));
        try {
            return waitForResponse(future, isCancelled);
        } finally {
            clearActiveStream.run();
        }
    }

    private HttpResponse<String> waitForResponse(
            CompletableFuture<HttpResponse<String>> future,
            BooleanSupplier isCancelled
    ) throws Exception {
        try {
            while (true) {
                if (shouldStop(isCancelled)) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("Perplexity chat cancelled");
                }

                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll cancellation while the HTTP request is in flight.
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private String httpErrorMessage(String prefix, HttpResponse<String> response) {
        String body = StringUtils.abbreviate(StringUtils.trimToEmpty(response.body()), 500);
        return StringUtils.isBlank(body)
                ? "%s: HTTP %d".formatted(prefix, response.statusCode())
                : "%s: HTTP %d: %s".formatted(prefix, response.statusCode(), body);
    }

    private boolean isDeepResearchModel(String modelId) {
        return Strings.CI.equals(StringUtils.trimToEmpty(modelId), DEEP_RESEARCH_MODEL);
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private static String markdownLinkDestination(String url) {
        return "<%s>".formatted(StringUtils.defaultString(url).replace(">", "%3E"));
    }

    private record Source(String title, String url) {
        private String display() {
            return StringUtils.isBlank(title)
                    ? markdownLinkDestination(url)
                    : "[%s](%s)".formatted(markdownLinkText(title), markdownLinkDestination(url));
        }

        private String markdownLinkText(String text) {
            return StringUtils.defaultString(text)
                    .replace("[", "\\[")
                    .replace("]", "\\]");
        }
    }
}
