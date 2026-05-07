package com.github.drafael.chat4j.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public class PerplexityWebSearchProvider implements WebSearchProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY_ENV_VAR = "PERPLEXITY_API_KEY";
    private static final String ENDPOINT = "https://api.perplexity.ai/chat/completions";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String id() {
        return WebSearchAvailabilityResolver.PERPLEXITY_OPTION_ID;
    }

    @Override
    public boolean available() {
        return CredentialResolver.hasRequiredCredentials(API_KEY_ENV_VAR);
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) throws Exception {
        return search(request, () -> false);
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, BooleanSupplier isCancelled) throws Exception {
        String apiKey = CredentialResolver.resolveRequiredApiKey(API_KEY_ENV_VAR, null);
        String query = StringUtils.defaultIfBlank(request.query(), "latest information");
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer %s".formatted(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(query)))
                .build();

        HttpResponse<String> response = send(httpRequest, isCancelled);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Perplexity search failed: HTTP %d".formatted(response.statusCode()));
        }

        return parse(query, response.body(), request.resultCount());
    }

    private HttpResponse<String> send(HttpRequest request, BooleanSupplier isCancelled) throws Exception {
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        try {
            while (true) {
                if (shouldStop(isCancelled)) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("Perplexity search cancelled");
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

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private String buildRequestBody(String query) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", "sonar");
        root.put("stream", false);
        ArrayNode messages = JSON.createArrayNode();
        messages.add(message("system", "Search the web and answer concisely. Return useful citations when available."));
        messages.add(message("user", query));
        root.set("messages", messages);
        return JSON.writeValueAsString(root);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private WebSearchResponse parse(String query, String body, int resultCount) throws Exception {
        JsonNode root = JSON.readTree(body);
        String answer = root.path("choices").path(0).path("message").path("content").asText("");
        List<WebSearchResult> results = new ArrayList<>();
        JsonNode searchResults = root.path("search_results");
        if (searchResults.isArray()) {
            searchResults.forEach(result -> addSearchResult(results, result, resultCount));
        }

        JsonNode citations = root.path("citations");
        if (citations.isArray()) {
            citations.forEach(citation -> addCitation(results, citation.asText(""), resultCount));
        }
        return new WebSearchResponse(query, answer, results);
    }

    private void addSearchResult(List<WebSearchResult> results, JsonNode result, int resultCount) {
        String url = result.path("url").asText("");
        if (StringUtils.isBlank(url) || containsUrl(results, url) || (resultCount > 0 && results.size() >= resultCount)) {
            return;
        }

        String title = StringUtils.defaultIfBlank(result.path("title").asText(""), domain(url));
        String snippet = result.path("snippet").asText("");
        results.add(new WebSearchResult(title, url, domain(url), snippet));
    }

    private void addCitation(List<WebSearchResult> results, String url, int resultCount) {
        if (StringUtils.isBlank(url) || containsUrl(results, url) || (resultCount > 0 && results.size() >= resultCount)) {
            return;
        }

        results.add(new WebSearchResult(domain(url), url, domain(url), ""));
    }

    private boolean containsUrl(List<WebSearchResult> results, String url) {
        return results.stream().anyMatch(result -> Strings.CS.equals(result.url(), url));
    }

    private String domain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
