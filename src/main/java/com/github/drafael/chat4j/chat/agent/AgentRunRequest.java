package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static java.util.Collections.emptyList;

public record AgentRunRequest(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        Path projectRoot,
        List<ToolInvocationResult> toolResults,
        BooleanSupplier isCancelled
) {

    public AgentRunRequest {
        history = history == null ? emptyList() : List.copyOf(history);
        reasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        toolResults = toolResults == null ? emptyList() : List.copyOf(toolResults);
        isCancelled = isCancelled == null ? () -> false : isCancelled;
    }

    public AgentRunRequest withToolResults(List<ToolInvocationResult> nextToolResults) {
        return new AgentRunRequest(history, reasoningLevel, projectRoot, nextToolResults, isCancelled);
    }
}
