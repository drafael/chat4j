package com.github.drafael.chat4j.chat.agent;

public interface AgentProviderAdapter {

    AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks);
}
