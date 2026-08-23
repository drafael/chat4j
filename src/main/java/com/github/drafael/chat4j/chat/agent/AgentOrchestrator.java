package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.mcp.McpInvocationTarget;
import com.github.drafael.chat4j.mcp.McpRunProvider;
import com.github.drafael.chat4j.mcp.McpRunSession;
import com.github.drafael.chat4j.provider.api.ProviderService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.Math.min;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;

@Slf4j
@RequiredArgsConstructor
public final class AgentOrchestrator {
    private static final JsonCodec JSON = JsonCodec.standard();
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int LOOP_GUARD_REPEAT_THRESHOLD = 3;
    private static final Set<String> LOOP_GUARD_TOOL_NAMES = Set.of("ls", "find", "grep", "read");

    @NonNull
    private final AgentProviderAdapterFactory adapterFactory;
    @NonNull
    private final LocalToolRuntime toolRuntime;
    @NonNull
    private final McpRunProvider mcpRunProvider;
    @NonNull
    private final McpApprovalHandler approvalHandler;

    public AgentOrchestrator(@NonNull AgentProviderAdapterFactory adapterFactory, @NonNull LocalToolRuntime toolRuntime) {
        this(adapterFactory, toolRuntime, McpRunProvider.disabled(), McpApprovalHandler.denyAll());
    }

    public void streamCompletion(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            String agentSystemPromptAppend,
            @NonNull ProviderService providerService,
            @NonNull AgentRunRequest request,
            @NonNull AgentRunCallbacks callbacks
    ) {
        Validate.notBlank(providerName, "providerName should not be blank");

        Path projectRoot = request.projectRoot();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            throw new IllegalStateException("Agent Mode requires a valid project folder.");
        }

        try (McpRunSession mcpSession = mcpRunProvider.openRun(request.isCancelled())) {
            List<AgentToolDefinition> tools = concat(
                    LocalAgentToolCatalog.definitions().stream(),
                    mcpSession.tools().stream()
            ).toList();
            AgentProviderAdapter adapter = mcpSession.hasTools()
                    ? adapterFactory.create(
                            providerName,
                            modelId,
                            baseUrl,
                            apiKey,
                            providerService,
                            agentSystemPromptAppend,
                            tools
                    )
                    : adapterFactory.create(
                            providerName,
                            modelId,
                            baseUrl,
                            apiKey,
                            providerService,
                            agentSystemPromptAppend
                    );
            runToolLoop(providerName, modelId, adapter, request, callbacks, projectRoot, mcpSession);
        }
    }

    private void runToolLoop(
            String providerName,
            String modelId,
            AgentProviderAdapter adapter,
            AgentRunRequest request,
            AgentRunCallbacks callbacks,
            Path projectRoot,
            McpRunSession mcpSession
    ) {
        List<ToolInvocationResult> toolResults = request.toolResults();
        String previousToolBatchSignature = null;
        int repeatedToolBatchCount = 0;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (shouldStop(request)) {
                return;
            }

            AgentTurnResult turnResult = adapter.executeTurn(request.withToolResults(toolResults), callbacks);
            if (shouldStop(request)) {
                return;
            }
            List<ToolInvocationRequest> toolInvocations = turnResult.toolInvocations();

            if (toolInvocations.isEmpty()) {
                if (turnResult.completed()) {
                    callbacks.onComplete().run();
                }
                return;
            }

            String toolBatchSignature = toolBatchSignature(toolInvocations);
            if (toolBatchSignature.equals(previousToolBatchSignature)) {
                repeatedToolBatchCount++;
            } else {
                repeatedToolBatchCount = 1;
                previousToolBatchSignature = toolBatchSignature;
            }

            if (shouldApplyLoopGuard(toolInvocations, repeatedToolBatchCount)) {
                log.warn("Agent tool loop guard activated (provider={}, model={}, repeatedBatchCount={}, tools={})",
                        providerName,
                        modelId,
                        repeatedToolBatchCount,
                        summarizeToolInvocations(toolInvocations));
                emitSkippedToolActivities(
                        toolInvocations,
                        callbacks,
                        request,
                        "Loop guard skipped repeated read-only tool call"
                );
                if (shouldStop(request)) {
                    return;
                }
                toolResults = loopGuardResults(toolInvocations, repeatedToolBatchCount);
            } else {
                toolResults = toolInvocations.stream()
                        .takeWhile(ignored -> !shouldStop(request))
                        .map(toolInvocation -> executeToolInvocation(
                                toolInvocation,
                                projectRoot,
                                request,
                                callbacks,
                                mcpSession
                        ))
                        .flatMap(Optional::stream)
                        .toList();
            }
        }

        if (shouldStop(request)) {
            return;
        }

        AgentTurnResult finalTurnResult = adapter.executeTurn(request.withToolResults(toolResults), callbacks);
        if (shouldStop(request)) {
            return;
        }
        if (finalTurnResult.toolInvocations().isEmpty()) {
            if (finalTurnResult.completed()) {
                callbacks.onComplete().run();
            }
            return;
        }

        String finalToolBatchSignature = toolBatchSignature(finalTurnResult.toolInvocations());
        int finalRepeatedBatchCount = finalToolBatchSignature.equals(previousToolBatchSignature)
                ? repeatedToolBatchCount + 1
                : 1;
        if (shouldApplyLoopGuard(finalTurnResult.toolInvocations(), finalRepeatedBatchCount)) {
            String toolSummary = summarizeToolInvocations(finalTurnResult.toolInvocations());
            String notice = "Agent tool loop guard stopped repeated read-only tool calls (provider=%s, model=%s, tools=%s)."
                    .formatted(
                            StringUtils.defaultIfBlank(providerName, "unknown"),
                            StringUtils.defaultIfBlank(modelId, "unknown"),
                            toolSummary
                    );
            log.warn(notice);
            emitSkippedToolActivities(
                    finalTurnResult.toolInvocations(),
                    callbacks,
                    request,
                    "Loop guard stopped repeated read-only tool calls"
            );
            if (shouldStop(request)) {
                return;
            }
            callbacks.onToken().accept("\n\n[Agent notice: Repeated read-only tool calls were stopped to avoid a loop. Proceeding with the best available context.]\n");
            if (!shouldStop(request)) {
                callbacks.onComplete().run();
            }
            return;
        }

        String toolSummary = summarizeToolInvocations(finalTurnResult.toolInvocations());
        String message = "Agent tool loop exceeded maximum rounds (maxRounds=%d, provider=%s, model=%s, requestedTools=%s)"
                .formatted(
                        MAX_TOOL_ROUNDS,
                        StringUtils.defaultIfBlank(providerName, "unknown"),
                        StringUtils.defaultIfBlank(modelId, "unknown"),
                        toolSummary
                );
        log.warn(message);
        if (!shouldStop(request)) {
            callbacks.onError().accept(new IllegalStateException(message));
        }
    }

    private Optional<ToolInvocationResult> executeToolInvocation(
            ToolInvocationRequest toolInvocation,
            Path projectRoot,
            AgentRunRequest request,
            AgentRunCallbacks callbacks,
            McpRunSession mcpSession
    ) {
        if (shouldStop(request)) {
            return Optional.empty();
        }
        callbacks.onToolActivity().accept(AgentToolActivityFormatter.started(toolInvocation));
        ToolInvocationResult result = mcpSession.handles(toolInvocation.name())
                ? executeMcpInvocation(toolInvocation, request, mcpSession)
                : toolRuntime.execute(toolInvocation, projectRoot, request.isCancelled());
        if (shouldStop(request)) {
            return Optional.empty();
        }
        callbacks.onToolActivity().accept("User denied the MCP tool call.".equals(result.error())
                ? AgentToolActivityFormatter.skipped(toolInvocation, "MCP tool call denied")
                : AgentToolActivityFormatter.completed(toolInvocation, result));
        return Optional.of(result);
    }

    private ToolInvocationResult executeMcpInvocation(
            ToolInvocationRequest toolInvocation,
            AgentRunRequest request,
            McpRunSession mcpSession
    ) {
        Map<String, Object> arguments;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = JSON.read(toolInvocation.argumentsJson(), Map.class);
            arguments = decoded;
            if (arguments == null) {
                throw new IllegalArgumentException("MCP tool arguments must be a JSON object.");
            }
        } catch (Exception e) {
            return ToolInvocationResult.failure(toolInvocation, "MCP tool arguments must be a JSON object.");
        }

        McpInvocationTarget target = mcpSession.target(toolInvocation.name());
        McpInvocationPermit permit = target.automatic()
                ? McpInvocationPermit.automaticallyAllowed()
                : McpInvocationPermit.pendingApproval();
        if (!target.automatic()) {
            String formattedArguments;
            try {
                formattedArguments = AgentToolResultLimiter.limit(mcpSession.redactForDisplay(
                        toolInvocation.name(),
                        JSON.writePrettyString(arguments)
                ));
            } catch (Exception e) {
                return ToolInvocationResult.failure(toolInvocation, "Could not display MCP tool arguments.");
            }
            McpApprovalDecision decision = approvalHandler.requestApproval(
                    new McpApprovalRequest(target.serverName(), target.toolName(), formattedArguments),
                    request.isCancelled()
            );
            if (decision != McpApprovalDecision.ALLOW_ONCE || shouldStop(request) || !permit.allowOnce()) {
                permit.cancel();
                return ToolInvocationResult.failure(toolInvocation, "User denied the MCP tool call.");
            }
        }
        if (shouldStop(request)) {
            permit.cancel();
            return ToolInvocationResult.failure(toolInvocation, "MCP tool call cancelled.");
        }
        return mcpSession.invoke(
                toolInvocation.name(),
                arguments,
                toolInvocation,
                request.isCancelled(),
                permit
        );
    }

    private void emitSkippedToolActivities(
            List<ToolInvocationRequest> toolInvocations,
            AgentRunCallbacks callbacks,
            AgentRunRequest request,
            String message
    ) {
        toolInvocations.stream()
                .takeWhile(ignored -> !shouldStop(request))
                .map(toolInvocation -> AgentToolActivityFormatter.skipped(toolInvocation, message))
                .forEach(callbacks.onToolActivity());
    }

    private boolean shouldStop(AgentRunRequest request) {
        return Thread.currentThread().isInterrupted() || request.isCancelled().getAsBoolean();
    }

    private String toolBatchSignature(List<ToolInvocationRequest> toolInvocations) {
        if (ObjectUtils.isEmpty(toolInvocations)) {
            return "none";
        }

        return toolInvocations.stream()
                .map(invocation -> "%s|%s".formatted(
                        StringUtils.defaultString(invocation.name()),
                        StringUtils.defaultString(invocation.argumentsJson()).trim()
                ))
                .collect(joining("||"));
    }

    private boolean shouldApplyLoopGuard(List<ToolInvocationRequest> toolInvocations, int repeatedToolBatchCount) {
        if (repeatedToolBatchCount < LOOP_GUARD_REPEAT_THRESHOLD) {
            return false;
        }

        return toolInvocations.stream()
                .map(ToolInvocationRequest::name)
                .allMatch(name -> LOOP_GUARD_TOOL_NAMES.contains(StringUtils.lowerCase(name)));
    }

    private List<ToolInvocationResult> loopGuardResults(
            List<ToolInvocationRequest> toolInvocations,
            int repeatedToolBatchCount
    ) {
        String guidance = "LOOP_GUARD: The same read-only tool call was repeated %d times with no visible progress. STOP CALLING TOOLS NOW. You MUST provide a final answer immediately using already collected results. Only call another tool if the user explicitly asks for a different path/pattern/query."
                .formatted(repeatedToolBatchCount);

        return toolInvocations.stream()
                .map(toolInvocation -> new ToolInvocationResult(
                        StringUtils.defaultString(toolInvocation.id()),
                        StringUtils.defaultString(toolInvocation.name()),
                        true,
                        guidance,
                        ""
                ))
                .toList();
    }

    private String summarizeToolInvocations(List<ToolInvocationRequest> toolInvocations) {
        if (ObjectUtils.isEmpty(toolInvocations)) {
            return "none";
        }

        List<String> names = toolInvocations.stream()
                .map(ToolInvocationRequest::name)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        if (names.isEmpty()) {
            return "unknown";
        }

        int limit = min(6, names.size());
        String joined = String.join(",", names.subList(0, limit));
        if (names.size() > limit) {
            return "%s,+%d more".formatted(joined, names.size() - limit);
        }
        return joined;
    }
}
