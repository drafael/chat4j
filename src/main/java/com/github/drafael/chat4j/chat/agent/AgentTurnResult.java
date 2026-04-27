package com.github.drafael.chat4j.chat.agent;

import java.util.List;

import static java.util.Collections.emptyList;

public record AgentTurnResult(boolean completed, List<ToolInvocationRequest> toolInvocations) {

    public AgentTurnResult {
        toolInvocations = toolInvocations == null ? emptyList() : List.copyOf(toolInvocations);
    }

    public static AgentTurnResult complete() {
        return new AgentTurnResult(true, emptyList());
    }

    public static AgentTurnResult continueWithTools(List<ToolInvocationRequest> toolInvocations) {
        return new AgentTurnResult(false, toolInvocations);
    }
}
