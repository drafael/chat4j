package com.github.drafael.chat4j.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;

public class ModelWebQueryPlanner implements WebQueryPlanner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderService provider;
    private final List<Message> recentHistory;

    public ModelWebQueryPlanner(ProviderService provider, List<Message> recentHistory) {
        this.provider = provider;
        this.recentHistory = recentHistory == null ? emptyList() : List.copyOf(recentHistory);
    }

    @Override
    public List<String> planQueries(String userPrompt, BooleanSupplier isCancelled) {
        String fallbackQuery = StringUtils.trimToEmpty(userPrompt);
        if (provider == null || StringUtils.isBlank(fallbackQuery) || shouldStop(isCancelled)) {
            return fallback(fallbackQuery, isCancelled);
        }

        StringBuilder output = new StringBuilder();
        AtomicReference<Exception> error = new AtomicReference<>();
        provider.streamCompletion(
                planningMessages(fallbackQuery),
                ReasoningLevel.OFF,
                token -> {
                    if (!shouldStop(isCancelled)) {
                        output.append(token);
                    }
                },
                ignored -> {
                    // Query planning does not display or persist reasoning.
                },
                () -> {
                    // no-op
                },
                error::set,
                isCancelled
        );

        if (error.get() != null || shouldStop(isCancelled)) {
            return fallback(fallbackQuery, isCancelled);
        }

        List<String> parsed = parseQueries(output.toString());
        return parsed.isEmpty() ? fallback(fallbackQuery, isCancelled) : parsed;
    }

    private List<Message> planningMessages(String userPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("""
                You plan web searches for the user's latest request.
                Return JSON only in this exact shape: {"queries":["query 1","query 2"]}.
                Produce 1 to 3 concise search-engine queries. Do not answer the user.
                """.trim()));
        recentHistory.stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .skip(Math.max(0, recentHistory.size() - 6L))
                .forEach(messages::add);
        messages.add(Message.user(userPrompt));
        return messages;
    }

    private List<String> parseQueries(String rawOutput) {
        String normalized = stripJsonFence(rawOutput);
        if (StringUtils.isBlank(normalized)) {
            return emptyList();
        }

        try {
            JsonNode root = JSON.readTree(normalized);
            JsonNode queriesNode = root.path("queries");
            if (!queriesNode.isArray()) {
                return emptyList();
            }

            List<String> queries = new ArrayList<>();
            queriesNode.forEach(node -> {
                String query = StringUtils.trimToEmpty(node.asText(""));
                if (StringUtils.isNotBlank(query) && !queries.contains(query) && queries.size() < 3) {
                    queries.add(query);
                }
            });
            return List.copyOf(queries);
        } catch (Exception e) {
            return emptyList();
        }
    }

    private String stripJsonFence(String rawOutput) {
        String output = StringUtils.trimToEmpty(rawOutput);
        if (output.startsWith("```")) {
            int firstNewline = output.indexOf('\n');
            int lastFence = output.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return output.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return output;
    }

    private List<String> fallback(String fallbackQuery, BooleanSupplier isCancelled) {
        return StringUtils.isBlank(fallbackQuery) || shouldStop(isCancelled) ? emptyList() : List.of(fallbackQuery);
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }
}
