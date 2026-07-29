package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.mcp.McpInvocationTarget;
import com.github.drafael.chat4j.mcp.McpRunProvider;
import com.github.drafael.chat4j.mcp.McpRunSession;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorMcpTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("A denied MCP invocation is skipped and returned to the provider as an error")
    void streamCompletion_whenMcpApprovalIsDenied_doesNotInvokeServer() throws Exception {
        McpRunSession mcpSession = mock(McpRunSession.class);
        AgentToolDefinition definition = new AgentToolDefinition(
                "mcp_server_echo_deadbeef",
                "Echo",
                Map.of("type", "object", "properties", emptyMap()),
                AgentToolSource.MCP
        );
        when(mcpSession.tools()).thenReturn(List.of(definition));
        when(mcpSession.hasTools()).thenReturn(true);
        when(mcpSession.handles(definition.name())).thenReturn(true);
        when(mcpSession.target(definition.name())).thenReturn(new McpInvocationTarget("Server", "echo", false));
        when(mcpSession.redactForDisplay(any(), any())).thenAnswer(invocation ->
                ((String) invocation.getArgument(1)).replace("transport-secret", "****"));
        McpRunProvider runProvider = ignored -> mcpSession;
        AtomicInteger turns = new AtomicInteger();
        AtomicReference<McpApprovalRequest> approvalRequest = new AtomicReference<>();
        List<ToolInvocationResult> returnedResults = new ArrayList<>();
        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(
                ProviderAttachmentTestSupport.authority()
        ) {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String append,
                    List<AgentToolDefinition> tools
            ) {
                return (request, callbacks) -> {
                    if (turns.getAndIncrement() == 0) {
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest(
                                    "call",
                                    definition.name(),
                                    "{\"value\":\"%stransport-secret%s\"}".formatted(
                                            "x".repeat(65_500),
                                            "y".repeat(1_000)
                                    )
                            )
                        ));
                    }
                    returnedResults.addAll(request.toolResults());
                    return AgentTurnResult.complete();
                };
            }
        };
        var subject = new AgentOrchestrator(
                adapterFactory,
                new LocalToolRuntime(),
                runProvider,
                (approval, cancelled) -> {
                    approvalRequest.set(approval);
                    return McpApprovalDecision.DENY;
                }
        );
        Path projectRoot = tempDirectory;
        var request = new AgentRunRequest(
                List.of(Message.user("echo")),
                ReasoningLevel.OFF,
                projectRoot,
                emptyList(),
                () -> false
        );
        List<AgentToolActivity> activities = new ArrayList<>();

        subject.streamCompletion(
                "OpenAI",
                "gpt-test",
                "https://example.test/v1",
                "key",
                "",
                mock(ProviderService.class),
                request,
                new AgentRunCallbacks(ignored -> { }, ignored -> { }, activities::add, () -> { }, ignored -> { })
        );

        verify(mcpSession, never()).invoke(any(), any(), any(), any(), any());
        assertThat(returnedResults).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("denied");
        });
        assertThat(activities).extracting(AgentToolActivity::status)
                .containsExactly(AgentToolActivity.Status.STARTED, AgentToolActivity.Status.SKIPPED);
        assertThat(approvalRequest.get().arguments())
                .contains("****", "[truncated after 65536 bytes]")
                .doesNotContain("transport-secret");
        assertThat(turns).hasValue(2);
    }

    @Test
    @DisplayName("Malformed arguments fail locally while automatic MCP calls bypass approval")
    void streamCompletion_whenArgumentsAreMalformedAndServerIsAutomatic_invokesOnlyValidAutomaticCall()
            throws Exception {
        McpRunSession mcpSession = mock(McpRunSession.class);
        AgentToolDefinition definition = new AgentToolDefinition(
                "mcp_server_echo_deadbeef",
                "Echo",
                Map.of("type", "object", "properties", emptyMap()),
                AgentToolSource.MCP
        );
        when(mcpSession.tools()).thenReturn(List.of(definition));
        when(mcpSession.hasTools()).thenReturn(true);
        when(mcpSession.handles(definition.name())).thenReturn(true);
        when(mcpSession.target(definition.name())).thenReturn(new McpInvocationTarget("Server", "echo", true));
        var validRequest = new ToolInvocationRequest("valid", definition.name(), "{\"value\":\"hello\"}");
        when(mcpSession.invoke(any(), any(), any(), any(), any()))
                .thenReturn(ToolInvocationResult.success(validRequest, "ok"));
        McpRunProvider runProvider = ignored -> mcpSession;
        AtomicInteger approvalRequests = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        List<ToolInvocationResult> returnedResults = new ArrayList<>();
        AgentProviderAdapterFactory adapterFactory = new AgentProviderAdapterFactory(
                ProviderAttachmentTestSupport.authority()
        ) {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String append,
                    List<AgentToolDefinition> tools
            ) {
                return (request, callbacks) -> {
                    if (turns.getAndIncrement() == 0) {
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest("malformed", definition.name(), "[]"),
                                validRequest
                        ));
                    }
                    returnedResults.addAll(request.toolResults());
                    return AgentTurnResult.complete();
                };
            }
        };
        var subject = new AgentOrchestrator(
                adapterFactory,
                new LocalToolRuntime(),
                runProvider,
                (request, cancelled) -> {
                    approvalRequests.incrementAndGet();
                    return McpApprovalDecision.DENY;
                }
        );
        var request = new AgentRunRequest(
                List.of(Message.user("echo")),
                ReasoningLevel.OFF,
                tempDirectory,
                emptyList(),
                () -> false
        );

        subject.streamCompletion(
                "OpenAI",
                "gpt-test",
                "https://example.test/v1",
                "key",
                "",
                mock(ProviderService.class),
                request,
                new AgentRunCallbacks(ignored -> { }, ignored -> { }, ignored -> { }, () -> { }, ignored -> { })
        );

        assertThat(approvalRequests).hasValue(0);
        verify(mcpSession, times(1)).invoke(any(), any(), any(), any(), any());
        assertThat(returnedResults).hasSize(2);
        assertThat(returnedResults.getFirst().error()).contains("JSON object");
        assertThat(returnedResults.get(1).success()).isTrue();
    }
}
