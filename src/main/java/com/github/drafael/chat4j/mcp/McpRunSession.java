package com.github.drafael.chat4j.mcp;

import com.github.drafael.chat4j.chat.agent.AgentToolDefinition;
import com.github.drafael.chat4j.chat.agent.McpInvocationPermit;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.chat.agent.ToolInvocationResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public final class McpRunSession implements AutoCloseable {

    private final List<AgentToolDefinition> tools;
    private final Map<String, McpToolRoute> routes;
    private final List<ClientLease> leases;
    private final AtomicBoolean closed = new AtomicBoolean();

    McpRunSession(
            List<AgentToolDefinition> tools,
            Map<String, McpToolRoute> routes,
            List<ClientLease> leases
    ) {
        this.tools = List.copyOf(tools);
        this.routes = Map.copyOf(routes);
        this.leases = List.copyOf(leases);
    }

    public static McpRunSession empty() {
        return new McpRunSession(emptyList(), emptyMap(), emptyList());
    }

    public List<AgentToolDefinition> tools() {
        return tools;
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public boolean handles(String alias) {
        return routes.containsKey(alias);
    }

    public McpInvocationTarget target(String alias) {
        McpToolRoute route = requiredRoute(alias);
        return new McpInvocationTarget(
                route.client().redactForDisplay(route.server().displayName()),
                route.client().redactForDisplay(route.toolName()),
                route.server().automatic()
        );
    }

    public String redactForDisplay(String alias, String value) {
        return requiredRoute(alias).client().redactForDisplay(value);
    }

    public ToolInvocationResult invoke(
            String alias,
            @NonNull Map<String, Object> arguments,
            @NonNull ToolInvocationRequest request,
            @NonNull BooleanSupplier cancelled
    ) {
        return invoke(alias, arguments, request, cancelled, McpInvocationPermit.automaticallyAllowed());
    }

    public ToolInvocationResult invoke(
            String alias,
            @NonNull Map<String, Object> arguments,
            @NonNull ToolInvocationRequest request,
            @NonNull BooleanSupplier cancelled,
            @NonNull McpInvocationPermit permit
    ) {
        if (closed.get()) {
            return ToolInvocationResult.failure(request, "MCP run session is closed.");
        }
        McpToolRoute route = requiredRoute(alias);
        return route.client().call(route.toolName(), arguments, route.outputSchema(), request, cancelled, permit);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            leases.reversed().forEach(ClientLease::close);
        }
    }

    private McpToolRoute requiredRoute(String alias) {
        McpToolRoute route = routes.get(alias);
        if (route == null) {
            throw new IllegalArgumentException("Unknown MCP tool alias.");
        }
        return route;
    }

    interface ClientLease extends AutoCloseable {
        McpClientSession client();

        @Override
        void close();
    }
}
