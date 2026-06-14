package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.CurrentConversationSaveCoordinator;
import com.github.drafael.chat4j.persistence.shutdown.ShutdownSaveDispatchCoordinator;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.NonNull;

public class MainFrameShutdownSaveActionFactory {

    public ShutdownSaveDispatchCoordinator.SaveAction create(@NonNull ShutdownSaveRequest request) {
        try {
            ShutdownSaveSnapshot snapshot = capture(request);
            return () -> request.currentConversationSaveCoordinator().save(
                    snapshot.currentConversationId(),
                    snapshot.history(),
                    snapshot.selectedModelKey(),
                    snapshot.reasoningLevel(),
                    snapshot.agentModeEnabled(),
                    snapshot.agentProjectRoot()
            );
        } catch (Exception e) {
            request.snapshotFailureHandler().handle(e);
            return () -> {
            };
        }
    }

    ShutdownSaveSnapshot capture(@NonNull ShutdownSaveRequest request) {
        return new ShutdownSaveSnapshot(
                request.currentConversationIdSupplier().get(),
                List.copyOf(request.historySupplier().get()),
                request.selectedModelKeySupplier().get(),
                request.reasoningLevelSupplier().get(),
                request.agentModeEnabledSupplier().getAsBoolean(),
                request.agentProjectRootSupplier().get()
        );
    }

    public record ShutdownSaveRequest(
            @NonNull Supplier<UUID> currentConversationIdSupplier,
            @NonNull Supplier<List<Message>> historySupplier,
            @NonNull Supplier<String> selectedModelKeySupplier,
            @NonNull Supplier<ReasoningLevel> reasoningLevelSupplier,
            @NonNull BooleanSupplier agentModeEnabledSupplier,
            @NonNull Supplier<Path> agentProjectRootSupplier,
            @NonNull CurrentConversationSaveCoordinator currentConversationSaveCoordinator,
            @NonNull ShutdownSaveDispatchCoordinator.FailureHandler snapshotFailureHandler
    ) {
    }

    record ShutdownSaveSnapshot(
            UUID currentConversationId,
            List<Message> history,
            String selectedModelKey,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot
    ) {
        @Override
        public String toString() {
            return "ShutdownSaveSnapshot[currentConversationId=%s, historySize=%d, selectedModelKey=%s, reasoningLevel=%s, agentModeEnabled=%s, agentProjectRoot=%s]"
                    .formatted(
                            currentConversationId,
                            history.size(),
                            selectedModelKey,
                            reasoningLevel,
                            agentModeEnabled,
                            agentProjectRoot
                    );
        }
    }
}
