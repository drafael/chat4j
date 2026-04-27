package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.ProviderService;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int LOOP_GUARD_REPEAT_THRESHOLD = 3;
    private static final Set<String> LOOP_GUARD_TOOL_NAMES = Set.of("ls", "find", "grep", "read");

    private final AgentProviderAdapterFactory adapterFactory;
    private final LocalToolRuntime toolRuntime;

    public AgentOrchestrator(@NonNull AgentProviderAdapterFactory adapterFactory, @NonNull LocalToolRuntime toolRuntime) {
        this.adapterFactory = adapterFactory;
        this.toolRuntime = toolRuntime;
    }

    public static AgentOrchestrator createDefault() {
        return new AgentOrchestrator(new AgentProviderAdapterFactory(), new LocalToolRuntime());
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

        AgentProviderAdapter adapter = adapterFactory.create(
                providerName,
                modelId,
                baseUrl,
                apiKey,
                providerService,
                agentSystemPromptAppend
        );
        List<ToolInvocationResult> toolResults = request.toolResults();
        String previousToolBatchSignature = null;
        int repeatedToolBatchCount = 0;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (request.isCancelled().getAsBoolean()) {
                return;
            }

            AgentTurnResult turnResult = adapter.executeTurn(request.withToolResults(toolResults), callbacks);
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
                toolResults = loopGuardResults(toolInvocations, repeatedToolBatchCount);
            } else {
                toolResults = toolInvocations.stream()
                        .map(toolInvocation -> toolRuntime.execute(toolInvocation, projectRoot, request.isCancelled()))
                        .toList();
            }
        }

        if (request.isCancelled().getAsBoolean()) {
            return;
        }

        AgentTurnResult finalTurnResult = adapter.executeTurn(request.withToolResults(toolResults), callbacks);
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
            callbacks.onToken().accept("\n\n[Agent notice: Repeated read-only tool calls were stopped to avoid a loop. "
                    + "Proceeding with the best available context.]\n");
            callbacks.onComplete().run();
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
        callbacks.onError().accept(new IllegalStateException(message));
    }

    private String toolBatchSignature(List<ToolInvocationRequest> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return "none";
        }

        return toolInvocations.stream()
                .map(invocation -> "%s|%s".formatted(
                        StringUtils.defaultString(invocation.name()),
                        StringUtils.defaultString(invocation.argumentsJson()).trim()
                ))
                .collect(java.util.stream.Collectors.joining("||"));
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
        String guidance = "LOOP_GUARD: The same read-only tool call was repeated %d times with no visible progress. "
                .formatted(repeatedToolBatchCount)
                + "STOP CALLING TOOLS NOW. You MUST provide a final answer immediately using already collected results. "
                + "Only call another tool if the user explicitly asks for a different path/pattern/query.";

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
        if (toolInvocations == null || toolInvocations.isEmpty()) {
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

        int limit = Math.min(6, names.size());
        String joined = String.join(",", names.subList(0, limit));
        if (names.size() > limit) {
            return joined + ",+" + (names.size() - limit) + " more";
        }
        return joined;
    }
}
