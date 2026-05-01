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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

public class PerplexityChatCompletionClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");

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

        HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsEndpoint(runtime.baseUrl())))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer %s".formatted(runtime.apiKey()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(runtime, history)))
                .build();

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        registerActiveStream.accept(() -> future.cancel(true));
        HttpResponse<String> response;
        try {
            response = waitForResponse(future, isCancelled);
        } finally {
            clearActiveStream.run();
        }
        if (shouldStop(isCancelled)) {
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Perplexity chat failed: HTTP %d".formatted(response.statusCode()));
        }

        String content = formatResponse(response.body());
        if (StringUtils.isNotBlank(content)) {
            onToken.accept(content);
        }
    }

    private String buildRequestBody(ProviderRuntime runtime, List<Message> history) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.selectedModel());
        root.put("stream", false);

        ArrayNode messages = JSON.createArrayNode();
        history.stream()
                .map(this::toMessageNode)
                .forEach(messages::add);
        root.set("messages", messages);
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
        String sourceList = sources.stream()
                .map(source -> "- %s".formatted(source.display()))
                .collect(joining("\n"));
        return "%s%s\n\nSources:\n%s".formatted(
                StringUtils.defaultString(linkedAnswer).trim(),
                sourceRefs,
                sourceList
        ).trim();
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

            String replacement = " [%d](%s)".formatted(sourceIndex + 1, sources.get(sourceIndex).url());
            matcher.appendReplacement(linked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(linked);
        return linked.toString();
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
            references.append("[%d](%s)".formatted(i + 1, sources.get(i).url()));
        }
        return references.toString();
    }

    private String chatCompletionsEndpoint(String baseUrl) {
        String normalizedBaseUrl = StringUtils.defaultIfBlank(baseUrl, "https://api.perplexity.ai").trim();
        normalizedBaseUrl = StringUtils.removeEnd(normalizedBaseUrl, "/");
        return "%s/chat/completions".formatted(normalizedBaseUrl);
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
                } catch (TimeoutException e) {
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

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private record Source(String title, String url) {
        private String display() {
            return StringUtils.isBlank(title)
                    ? "<%s>".formatted(url)
                    : "[%s](%s)".formatted(markdownLinkText(title), url);
        }

        private String markdownLinkText(String text) {
            return StringUtils.defaultString(text)
                    .replace("[", "\\[")
                    .replace("]", "\\]");
        }
    }
}
