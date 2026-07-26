package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * The application-owned serial mutation boundary for conversation persistence.
 * Repository work is FIFO, command failures are isolated, and terminal sealing is first-call-wins.
 */
public class ConversationPersistenceCoordinator implements AutoCloseable {

    private static final String THREAD_NAME = "chat4j-conversation-persistence";

    private final ConversationRepository conversationRepo;
    private final ExecutorService executor;
    private final Object lifecycleLock = new Object();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, List<ConversationHistoryEntry>> automaticRecovery = new HashMap<>();
    private final Set<AssistantRecoveryKey> pendingAssistantCommands = new HashSet<>();
    private final Map<UUID, DesiredState<AgentSettingsRecovery>> agentSettingsRecovery = new HashMap<>();
    private final Map<UUID, DesiredState<ReasoningLevel>> reasoningLevelRecovery = new HashMap<>();
    private final Map<UUID, DesiredState<WebSearchSettingsRecovery>> webSearchSettingsRecovery = new HashMap<>();
    private final Map<UUID, SidebarDesiredState<String>> renameRecovery = new HashMap<>();
    private final Map<UUID, SidebarDesiredState<Boolean>> favoriteRecovery = new HashMap<>();
    private final Map<ExplicitRecoveryKey, RepositoryMutation> explicitRecovery = new LinkedHashMap<>();
    private final Set<ExplicitRecoveryKey> pendingExplicitCommands = new HashSet<>();
    private final Map<UUID, IndeterminateRecovery> indeterminateRecovery = new HashMap<>();
    private final Map<UUID, CompletableFuture<ReconciliationResult>> activeReconciliations = new HashMap<>();
    private final Map<UUID, List<IndeterminateRecovery>> settledReconciliations = new HashMap<>();
    private final Set<UUID> failedSidebarRecoveries = new HashSet<>();
    private final ThreadLocal<List<UUID>> currentMutationIds = ThreadLocal.withInitial(List::of);
    private final Set<UUID> clearingConversationIds = new HashSet<>();
    private final Map<UUID, Integer> truncatingConversationOrdinals = new HashMap<>();
    private final Set<UUID> deletingConversationIds = new HashSet<>();
    private final Set<UUID> deletedConversationIds = new HashSet<>();
    private boolean sealed;
    private CompletableFuture<Void> terminalFuture;

    public ConversationPersistenceCoordinator(@NonNull ConversationRepository conversationRepo) {
        this(conversationRepo, Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }));
    }

    ConversationPersistenceCoordinator(
            @NonNull ConversationRepository conversationRepo,
            @NonNull ExecutorService executor
    ) {
        this.conversationRepo = conversationRepo;
        this.executor = executor;
    }

    public CompletableFuture<UUID> submitNewConversation(
            @NonNull ConversationRepository.CreateConversationCommand command
    ) {
        var recoveryKey = new ExplicitRecoveryKey(
                command.conversationId(),
                command.firstEntry().messageId(),
                command.firstEntry().ordinal(),
                ExplicitMutationType.USER
        );
        return submitExplicit(command.conversationId(), recoveryKey, () -> {
            runReconciled(
                    () -> conversationRepo.createConversation(command),
                    () -> conversationRepo.isCanonicalCreate(command),
                    () -> removeExplicitRecovery(recoveryKey)
            );
            return command.conversationId();
        });
    }

    public CompletableFuture<UUID> submitUserMessage(
            @NonNull UUID conversationId,
            @NonNull ConversationHistoryEntry entry
    ) {
        var recoveryKey = new ExplicitRecoveryKey(
                conversationId,
                entry.messageId(),
                entry.ordinal(),
                ExplicitMutationType.USER
        );
        return submitExplicit(conversationId, recoveryKey, () -> {
            try {
                retryAssistantRecovery(conversationId);
            } catch (ConversationPersistenceIndeterminateException e) {
                throw new ConversationPersistencePrerequisiteIndeterminateException(e);
            }
            appendReconciled(conversationId, entry, () -> removeExplicitRecovery(recoveryKey));
            return conversationId;
        });
    }

    public CompletableFuture<UUID> submitFirstAfterClear(
            @NonNull ConversationRepository.FirstAfterClearCommand command
    ) {
        var recoveryKey = new ExplicitRecoveryKey(
                command.conversationId(),
                command.entry().messageId(),
                command.entry().ordinal(),
                ExplicitMutationType.USER
        );
        return submitExplicit(command.conversationId(), recoveryKey, () -> {
            runReconciled(
                    () -> conversationRepo.appendFirstAfterClear(command),
                    () -> conversationRepo.isCanonicalFirstAfterClear(command),
                    () -> removeExplicitRecovery(recoveryKey)
            );
            return command.conversationId();
        });
    }

    public CompletableFuture<Void> submitAssistant(
            @NonNull UUID conversationId,
            @NonNull ConversationHistoryEntry entry
    ) {
        var recoveryKey = new AssistantRecoveryKey(conversationId, entry.messageId());
        var commandRegistered = new AtomicBoolean();
        var recoveryRegistered = new AtomicBoolean();
        CompletableFuture<Void> result = submit(
                conversationId,
                () -> {
                    runReconciled(
                            () -> conversationRepo.appendMessage(conversationId, entry),
                            () -> conversationRepo.isCanonicalEntry(conversationId, entry),
                            () -> {
                                synchronized (lifecycleLock) {
                                    removeAutomaticRecovery(conversationId, entry);
                                }
                            }
                    );
                    return null;
                },
                true,
                () -> {
                    if (!pendingAssistantCommands.add(recoveryKey)) {
                        throw new RejectedExecutionException("Assistant message persistence is already pending");
                    }
                    commandRegistered.set(true);
                    recoveryRegistered.set(registerAutomaticRecovery(conversationId, entry));
                },
                () -> {
                    if (commandRegistered.get()) {
                        pendingAssistantCommands.remove(recoveryKey);
                    }
                    if (recoveryRegistered.get()) {
                        removeAutomaticRecovery(conversationId, entry);
                    }
                },
                () -> {},
                () -> pendingAssistantCommands.remove(recoveryKey),
                true
        );
        result.thenRun(() -> {
            synchronized (lifecycleLock) {
                removeAutomaticRecovery(conversationId, entry);
            }
        });
        return result;
    }

    public CompletableFuture<Void> submitEdit(@NonNull UUID conversationId, @NonNull ConversationHistoryEntry entry) {
        var recoveryKey = new ExplicitRecoveryKey(
                conversationId,
                entry.messageId(),
                entry.ordinal(),
                ExplicitMutationType.EDIT
        );
        return submitExplicit(conversationId, recoveryKey, () -> {
            runReconciled(
                    () -> conversationRepo.updateMessage(conversationId, entry),
                    () -> conversationRepo.isCanonicalEdit(conversationId, entry),
                    () -> removeExplicitRecovery(recoveryKey)
            );
            return null;
        });
    }

    public CompletableFuture<Void> submitEditAndTruncate(
            @NonNull UUID conversationId,
            @NonNull ConversationHistoryEntry entry
    ) {
        var recoveryKey = new ExplicitRecoveryKey(
                conversationId,
                entry.messageId(),
                entry.ordinal(),
                ExplicitMutationType.EDIT_AND_TRUNCATE
        );
        return submitTruncatingExplicit(
                conversationId,
                recoveryKey,
                entry.ordinal(),
                () -> {
                    runReconciled(
                            () -> conversationRepo.updateMessageAndDeleteSuffix(conversationId, entry),
                            () -> conversationRepo.isCanonicalEditAndTruncate(conversationId, entry),
                            () -> {
                                removeRecoveryAfterOrdinal(conversationId, entry.ordinal());
                                removeExplicitRecovery(recoveryKey);
                            }
                    );
                    return null;
                },
                () -> removeRecoveryAfterOrdinal(conversationId, entry.ordinal())
        );
    }

    public CompletableFuture<Void> submitTruncate(
            @NonNull UUID conversationId,
            @NonNull UUID retainedMessageId,
            int retainedOrdinal
    ) {
        var recoveryKey = new ExplicitRecoveryKey(
                conversationId,
                retainedMessageId,
                retainedOrdinal,
                ExplicitMutationType.TRUNCATE
        );
        return submitTruncatingExplicit(
                conversationId,
                recoveryKey,
                retainedOrdinal,
                () -> {
                    runReconciled(
                            () -> conversationRepo.truncateAfter(conversationId, retainedMessageId, retainedOrdinal),
                            () -> conversationRepo.isCanonicalTruncate(conversationId, retainedMessageId, retainedOrdinal),
                            () -> {
                                removeRecoveryAfterOrdinal(conversationId, retainedOrdinal);
                                removeExplicitRecovery(recoveryKey);
                            }
                    );
                    return null;
                },
                () -> removeRecoveryAfterOrdinal(conversationId, retainedOrdinal)
        );
    }

    public void markUserMessageFailureDelivered(UUID conversationId, UUID messageId) {
        synchronized (lifecycleLock) {
            explicitRecovery.keySet().removeIf(key -> key.conversationId().equals(conversationId)
                    && key.messageId().equals(messageId)
                    && key.type() == ExplicitMutationType.USER);
        }
    }

    public void markHistoryMutationFailureDelivered(UUID conversationId, UUID messageId) {
        synchronized (lifecycleLock) {
            explicitRecovery.keySet().removeIf(key -> key.conversationId().equals(conversationId)
                    && key.messageId().equals(messageId)
                    && key.type() != ExplicitMutationType.USER);
        }
    }

    public CompletableFuture<Void> submitClear(@NonNull UUID conversationId) {
        var clearSucceeded = new AtomicBoolean();
        var prerequisiteBlocked = new AtomicBoolean();
        CompletableFuture<Void> result = submit(
                conversationId,
                () -> {
                    runReconciled(
                            () -> conversationRepo.clearMessages(conversationId),
                            () -> conversationRepo.isClear(conversationId),
                            () -> {
                                synchronized (lifecycleLock) {
                                    removeMessageRecovery(conversationId);
                                }
                            }
                    );
                    clearSucceeded.set(true);
                    return null;
                },
                true,
                () -> clearingConversationIds.add(conversationId),
                () -> clearingConversationIds.remove(conversationId),
                () -> prerequisiteBlocked.set(true),
                () -> {
                    if (clearSucceeded.get()) {
                        removeMessageRecovery(conversationId);
                    }
                    clearingConversationIds.remove(conversationId);
                },
                false
        );
        var exposedResult = new CompletableFuture<Void>();
        result.whenComplete((ignored, error) -> {
            if (error == null) {
                exposedResult.complete(null);
            } else if (prerequisiteBlocked.get()) {
                exposedResult.completeExceptionally(
                        new ConversationPersistencePrerequisiteIndeterminateException(error)
                );
            } else {
                exposedResult.completeExceptionally(error);
            }
        });
        return exposedResult;
    }

    public CompletableFuture<Void> submitDelete(@NonNull List<UUID> exactIds) {
        List<UUID> ids = exactIds.stream().filter(Objects::nonNull).distinct().toList();
        Set<UUID> admittedIds = new HashSet<>();
        CompletableFuture<Void> result = submit(
                null,
                () -> {
                    synchronized (lifecycleLock) {
                        UUID blockedId = ids.stream()
                                .filter(indeterminateRecovery::containsKey)
                                .findFirst()
                                .orElse(null);
                        if (blockedId != null) {
                            throw new ConversationPersistencePrerequisiteIndeterminateException(
                                    new RejectedExecutionException(
                                            "Conversation mutation is blocked: %s".formatted(blockedId)
                                    )
                            );
                        }
                        ids.forEach(this::clearSettledRecoveries);
                    }
                    runReconciled(
                            ids,
                            () -> conversationRepo.deleteConversations(ids),
                            () -> conversationRepo.conversationsAbsent(ids),
                            () -> {
                                synchronized (lifecycleLock) {
                                    ids.forEach(this::removeRecovery);
                                    deletedConversationIds.addAll(ids);
                                }
                            },
                            () -> {},
                            ReconciliationType.DELETE
                    );
                    synchronized (lifecycleLock) {
                        ids.forEach(this::removeRecovery);
                        deletedConversationIds.addAll(ids);
                    }
                    return null;
                },
                false,
                () -> {
                    UUID blockedId = ids.stream().filter(this::mutationBlocked).findFirst().orElse(null);
                    if (blockedId != null) {
                        throw new RejectedExecutionException("Conversation mutation is blocked: %s".formatted(blockedId));
                    }
                    deletingConversationIds.addAll(ids);
                    admittedIds.addAll(ids);
                    ids.forEach(this::incrementRevision);
                },
                () -> {
                    deletingConversationIds.removeAll(admittedIds);
                    admittedIds.forEach(this::decrementRevision);
                },
                () -> {},
                () -> deletingConversationIds.removeAll(ids),
                false
        );
        return result;
    }

    public CompletableFuture<Void> submitRename(@NonNull UUID conversationId, String title) {
        return submitSidebarDesiredState(
                conversationId,
                renameRecovery,
                title,
                () -> conversationRepo.updateTitle(conversationId, title),
                () -> conversationRepo.hasTitle(conversationId, title)
        );
    }

    public CompletableFuture<Void> submitFavorite(@NonNull UUID conversationId, boolean favorite) {
        return submitSidebarDesiredState(
                conversationId,
                favoriteRecovery,
                favorite,
                () -> conversationRepo.setFavorite(conversationId, favorite),
                () -> conversationRepo.hasFavorite(conversationId, favorite)
        );
    }

    public CompletableFuture<Void> submitAgentSettings(UUID conversationId, boolean enabled, Path projectRoot) {
        Path normalizedRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        var desired = new AgentSettingsRecovery(enabled, normalizedRoot);
        return submitRecoverable(
                conversationId,
                agentSettingsRecovery,
                desired,
                () -> conversationRepo.updateAgentSettings(conversationId, desired.enabled(), desired.projectRoot()),
                () -> conversationRepo.hasAgentSettings(conversationId, desired.enabled(), desired.projectRoot())
        );
    }

    public CompletableFuture<Void> submitReasoningLevel(UUID conversationId, ReasoningLevel level) {
        ReasoningLevel desired = level == null ? ReasoningLevel.OFF : level;
        return submitRecoverable(
                conversationId,
                reasoningLevelRecovery,
                desired,
                () -> conversationRepo.updateReasoningLevel(conversationId, desired),
                () -> conversationRepo.hasReasoningLevel(conversationId, desired)
        );
    }

    public CompletableFuture<Void> submitWebSearchSettings(UUID conversationId, boolean enabled, String optionId) {
        var desired = new WebSearchSettingsRecovery(enabled, optionId);
        return submitRecoverable(
                conversationId,
                webSearchSettingsRecovery,
                desired,
                () -> conversationRepo.updateWebSearchSettings(conversationId, desired.enabled(), desired.optionId()),
                () -> conversationRepo.hasWebSearchSettings(conversationId, desired.enabled(), desired.optionId())
        );
    }

    public CompletableFuture<Long> fenceRevision(@NonNull UUID conversationId) {
        var result = new CompletableFuture<Long>();
        synchronized (lifecycleLock) {
            if (sealed) {
                result.completeExceptionally(new RejectedExecutionException("Conversation persistence is sealed"));
                return result;
            }
            if (destructiveMutationBlocked(conversationId)) {
                result.completeExceptionally(new RejectedExecutionException(
                        "Conversation mutation is blocked: %s".formatted(conversationId)
                ));
                return result;
            }
            long fencedRevision = revision(conversationId);
            try {
                executor.execute(() -> {
                    currentMutationIds.set(List.of(conversationId));
                    try {
                        reconcileIndeterminate(conversationId);
                        retryDeferredSidebarRecovery(conversationId, true);
                        retryAssistantRecoveryForLoad(conversationId);
                        retryRuntimeRecovery(conversationId);
                        result.complete(fencedRevision);
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    } finally {
                        currentMutationIds.remove();
                    }
                });
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(e);
            }
        }
        return result;
    }

    public List<UUID> indeterminateDeleteConversationIds(UUID conversationId) {
        synchronized (lifecycleLock) {
            IndeterminateRecovery recovery = indeterminateRecovery.get(conversationId);
            return recovery == null || recovery.type() != ReconciliationType.DELETE
                    ? emptyList()
                    : recovery.conversationIds();
        }
    }

    public Optional<Boolean> consumeSettledDeleteReconciliation(@NonNull List<UUID> conversationIds) {
        synchronized (lifecycleLock) {
            IndeterminateRecovery recovery = settledReconciliations.values().stream()
                    .flatMap(List::stream)
                    .filter(candidate -> candidate.type() == ReconciliationType.DELETE)
                    .filter(candidate -> candidate.conversationIds().equals(conversationIds))
                    .filter(candidate -> conversationIds.stream().allMatch(id -> containsSettledRecovery(id, candidate)))
                    .findFirst()
                    .orElse(null);
            if (recovery == null) {
                return Optional.empty();
            }
            removeSettledRecovery(recovery);
            return Optional.of(recovery.outcome().join());
        }
    }

    public boolean consumeFailedSidebarRecovery(@NonNull UUID conversationId) {
        synchronized (lifecycleLock) {
            return failedSidebarRecoveries.remove(conversationId);
        }
    }

    public CompletableFuture<ReconciliationResult> reconcileBlocked(@NonNull UUID conversationId) {
        synchronized (lifecycleLock) {
            CompletableFuture<ReconciliationResult> active = activeReconciliations.get(conversationId);
            if (active != null) {
                return active;
            }
            var result = new CompletableFuture<ReconciliationResult>();
            if (sealed) {
                result.completeExceptionally(new RejectedExecutionException("Conversation persistence is sealed"));
                return result;
            }
            IndeterminateRecovery settled = pollSettledRecovery(conversationId);
            if (settled != null) {
                return CompletableFuture.completedFuture(reconciliationResult(settled, settled.outcome().join()));
            }
            IndeterminateRecovery recovery = indeterminateRecovery.get(conversationId);
            if (recovery == null) {
                result.completeExceptionally(new RejectedExecutionException(
                        "Conversation does not have an indeterminate mutation: %s".formatted(conversationId)
                ));
                return result;
            }
            recovery.conversationIds().forEach(id -> activeReconciliations.put(id, result));
            try {
                executor.execute(() -> {
                    currentMutationIds.set(List.of(conversationId));
                    try {
                        boolean canonical = reconcileIndeterminate(recovery);
                        retryDeferredSidebarRecovery(conversationId, false);
                        synchronized (lifecycleLock) {
                            removeSettledRecovery(recovery);
                            recovery.conversationIds().forEach(id -> activeReconciliations.remove(id, result));
                        }
                        result.complete(reconciliationResult(recovery, canonical));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    } finally {
                        synchronized (lifecycleLock) {
                            recovery.conversationIds().forEach(id -> activeReconciliations.remove(id, result));
                        }
                        currentMutationIds.remove();
                    }
                });
            } catch (Throwable t) {
                recovery.conversationIds().forEach(id -> activeReconciliations.remove(id, result));
                result.completeExceptionally(t);
            }
            return result;
        }
    }

    private ReconciliationResult reconciliationResult(IndeterminateRecovery recovery, boolean canonical) {
        List<UUID> deleteConversationIds = recovery.type() == ReconciliationType.DELETE
                ? recovery.conversationIds()
                : emptyList();
        return new ReconciliationResult(canonical, deleteConversationIds);
    }

    public long revision(UUID conversationId) {
        synchronized (lifecycleLock) {
            return revisions.getOrDefault(conversationId, 0L);
        }
    }

    public boolean isConversationBlocked(UUID conversationId) {
        synchronized (lifecycleLock) {
            return conversationId != null && mutationBlocked(conversationId);
        }
    }

    public boolean hasIndeterminateMutation(UUID conversationId) {
        synchronized (lifecycleLock) {
            return conversationId != null && indeterminateRecovery.containsKey(conversationId);
        }
    }

    public Set<UUID> pendingAssistantRecoveryMessageIds(UUID conversationId) {
        synchronized (lifecycleLock) {
            return automaticRecovery.getOrDefault(conversationId, List.of()).stream()
                    .map(ConversationHistoryEntry::messageId)
                    .collect(toUnmodifiableSet());
        }
    }

    public CompletableFuture<Void> sealWithFinal() {
        synchronized (lifecycleLock) {
            if (terminalFuture != null) {
                return terminalFuture;
            }
            sealed = true;
            terminalFuture = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    try {
                        completeTerminal(retryAutomaticRecovery());
                    } catch (Throwable t) {
                        completeTerminal(t);
                    }
                });
            } catch (RejectedExecutionException e) {
                completeTerminal(e);
            }
            executor.shutdown();
            return terminalFuture;
        }
    }

    public CompletableFuture<Void> sealWithoutFinal() {
        synchronized (lifecycleLock) {
            if (terminalFuture != null) {
                return terminalFuture;
            }
            sealed = true;
            terminalFuture = new CompletableFuture<>();
            try {
                executor.execute(() -> completeTerminal(null));
            } catch (RejectedExecutionException e) {
                completeTerminal(e);
            }
            executor.shutdown();
            return terminalFuture;
        }
    }

    @Override
    public void close() {
        sealWithoutFinal();
    }

    private <T> CompletableFuture<T> submit(
            UUID conversationId,
            Callable<T> command,
            boolean advanceRevision,
            Runnable onAccepted,
            Runnable onRejected
    ) {
        return submit(conversationId, command, advanceRevision, onAccepted, onRejected, () -> {}, () -> {}, false);
    }

    private <T> CompletableFuture<T> submit(
            UUID conversationId,
            Callable<T> command,
            boolean advanceRevision,
            Runnable onAccepted,
            Runnable onRejected,
            Runnable onExecutionBlocked,
            Runnable onSettled,
            boolean retainAssistantRecoveryWhileDestructivePending
    ) {
        var result = new CompletableFuture<T>();
        synchronized (lifecycleLock) {
            if (sealed) {
                result.completeExceptionally(new RejectedExecutionException("Conversation persistence is sealed"));
                return result;
            }
            boolean retainBlockedAssistant = conversationId != null
                    && retainAssistantRecoveryWhileDestructivePending
                    && !deletedConversationIds.contains(conversationId)
                    && (pendingDestructiveMutation(conversationId)
                    || indeterminateRecovery.containsKey(conversationId));
            if (conversationId != null && mutationBlocked(conversationId) && !retainBlockedAssistant) {
                result.completeExceptionally(new RejectedExecutionException(
                        "Conversation mutation is blocked: %s".formatted(conversationId)
                ));
                return result;
            }
            boolean revisionAdvanced = false;
            try {
                onAccepted.run();
                if (advanceRevision && conversationId != null) {
                    incrementRevision(conversationId);
                    revisionAdvanced = true;
                }
                if (retainBlockedAssistant) {
                    onSettled.run();
                    result.completeExceptionally(new RejectedExecutionException(
                            "Conversation mutation is blocked: %s".formatted(conversationId)
                    ));
                    return result;
                }
                executor.execute(() -> {
                    currentMutationIds.set(conversationId == null ? List.of() : List.of(conversationId));
                    try {
                        synchronized (lifecycleLock) {
                            if (conversationId != null && indeterminateRecovery.containsKey(conversationId)) {
                                onExecutionBlocked.run();
                                onSettled.run();
                                result.completeExceptionally(new RejectedExecutionException(
                                        "Conversation mutation is blocked: %s".formatted(conversationId)
                                ));
                                return;
                            }
                        }
                        T value = command.call();
                        synchronized (lifecycleLock) {
                            onSettled.run();
                            result.complete(value);
                        }
                    } catch (Throwable t) {
                        synchronized (lifecycleLock) {
                            onSettled.run();
                            result.completeExceptionally(t);
                        }
                    } finally {
                        currentMutationIds.remove();
                    }
                });
                if (conversationId != null) {
                    clearSettledRecoveries(conversationId);
                }
            } catch (RejectedExecutionException e) {
                if (revisionAdvanced) {
                    decrementRevision(conversationId);
                }
                onRejected.run();
                result.completeExceptionally(e);
            } catch (Throwable t) {
                if (revisionAdvanced) {
                    decrementRevision(conversationId);
                }
                onRejected.run();
                result.completeExceptionally(t);
            }
        }
        return result;
    }

    private void incrementRevision(UUID conversationId) {
        revisions.merge(conversationId, 1L, Long::sum);
    }

    private void decrementRevision(UUID conversationId) {
        long revision = revisions.getOrDefault(conversationId, 0L);
        if (revision <= 1) {
            revisions.remove(conversationId);
        } else {
            revisions.put(conversationId, revision - 1);
        }
    }

    private boolean mutationBlocked(UUID conversationId) {
        return destructiveMutationBlocked(conversationId) || indeterminateRecovery.containsKey(conversationId);
    }

    private boolean destructiveMutationBlocked(UUID conversationId) {
        return pendingDestructiveMutation(conversationId) || deletedConversationIds.contains(conversationId);
    }

    private boolean pendingDestructiveMutation(UUID conversationId) {
        return clearingConversationIds.contains(conversationId)
                || truncatingConversationOrdinals.containsKey(conversationId)
                || deletingConversationIds.contains(conversationId);
    }

    private <T> CompletableFuture<T> submitExplicit(
            UUID conversationId,
            ExplicitRecoveryKey recoveryKey,
            Callable<T> command
    ) {
        RepositoryMutation recoveryMutation = () -> command.call();
        var previousRecovery = new AtomicReference<RepositoryMutation>();
        var commandRegistered = new AtomicBoolean();
        CompletableFuture<T> result = submit(
                conversationId,
                command,
                true,
                () -> {
                    if (!pendingExplicitCommands.add(recoveryKey)) {
                        throw new RejectedExecutionException("Conversation mutation is already pending");
                    }
                    commandRegistered.set(true);
                    previousRecovery.set(explicitRecovery.put(recoveryKey, recoveryMutation));
                },
                () -> {
                    if (commandRegistered.get()) {
                        pendingExplicitCommands.remove(recoveryKey);
                        restorePreviousRecovery(recoveryKey, recoveryMutation, previousRecovery.get());
                    }
                },
                () -> {},
                () -> pendingExplicitCommands.remove(recoveryKey),
                false
        );
        result.thenRun(() -> removeExplicitRecovery(recoveryKey));
        return result;
    }

    private <T> CompletableFuture<T> submitTruncatingExplicit(
            UUID conversationId,
            ExplicitRecoveryKey recoveryKey,
            int retainedOrdinal,
            Callable<T> command,
            Runnable onSuccessSettled
    ) {
        RepositoryMutation recoveryMutation = () -> {
            command.call();
            onSuccessSettled.run();
        };
        var previousRecovery = new AtomicReference<RepositoryMutation>();
        var commandRegistered = new AtomicBoolean();
        var commandSucceeded = new AtomicBoolean();
        CompletableFuture<T> result = submit(
                conversationId,
                () -> {
                    T value = command.call();
                    commandSucceeded.set(true);
                    return value;
                },
                true,
                () -> {
                    if (!pendingExplicitCommands.add(recoveryKey)) {
                        throw new RejectedExecutionException("Conversation mutation is already pending");
                    }
                    commandRegistered.set(true);
                    previousRecovery.set(explicitRecovery.put(recoveryKey, recoveryMutation));
                    truncatingConversationOrdinals.put(conversationId, retainedOrdinal);
                },
                () -> {
                    if (commandRegistered.get()) {
                        pendingExplicitCommands.remove(recoveryKey);
                        restorePreviousRecovery(recoveryKey, recoveryMutation, previousRecovery.get());
                        truncatingConversationOrdinals.remove(conversationId, retainedOrdinal);
                    }
                },
                () -> {},
                () -> {
                    if (commandSucceeded.get()) {
                        onSuccessSettled.run();
                    }
                    pendingExplicitCommands.remove(recoveryKey);
                    truncatingConversationOrdinals.remove(conversationId, retainedOrdinal);
                },
                false
        );
        result.thenRun(() -> removeExplicitRecovery(recoveryKey));
        return result;
    }

    private void restorePreviousRecovery(
            ExplicitRecoveryKey recoveryKey,
            RepositoryMutation rejectedRecovery,
            RepositoryMutation previousRecovery
    ) {
        if (previousRecovery == null) {
            explicitRecovery.remove(recoveryKey, rejectedRecovery);
        } else {
            explicitRecovery.replace(recoveryKey, rejectedRecovery, previousRecovery);
        }
    }

    private <T> CompletableFuture<Void> submitSidebarDesiredState(
            UUID conversationId,
            Map<UUID, SidebarDesiredState<T>> recoveryState,
            T desired,
            RepositoryMutation mutation,
            Postcondition postcondition
    ) {
        var acceptedState = new SidebarDesiredState<>(desired);
        CompletableFuture<Void> result = submit(
                conversationId,
                () -> {
                    Runnable removeAcceptedState = () -> {
                        synchronized (lifecycleLock) {
                            recoveryState.remove(conversationId, acceptedState);
                        }
                    };
                    runReconciled(
                            mutation,
                            postcondition,
                            () -> removeCanonicalDesired(conversationId, acceptedState, recoveryState),
                            removeAcceptedState
                    );
                    return null;
                },
                true,
                () -> {
                    failedSidebarRecoveries.remove(conversationId);
                    recoveryState.put(conversationId, acceptedState);
                },
                () -> recoveryState.remove(conversationId, acceptedState),
                acceptedState::markRecoveryRequired,
                () -> {},
                false
        );
        var exposedResult = new CompletableFuture<Void>();
        result.whenComplete((ignored, error) -> {
            synchronized (lifecycleLock) {
                if (error == null || !indeterminateRecovery.containsKey(conversationId)) {
                    recoveryState.remove(conversationId, acceptedState);
                }
                if (error == null) {
                    exposedResult.complete(null);
                } else if (acceptedState.recoveryRequired()) {
                    exposedResult.completeExceptionally(
                            new ConversationPersistencePrerequisiteIndeterminateException(error)
                    );
                } else {
                    exposedResult.completeExceptionally(error);
                }
            }
        });
        return exposedResult;
    }

    private <T> CompletableFuture<Void> submitRecoverable(
            UUID conversationId,
            Map<UUID, DesiredState<T>> recoveryState,
            T desired,
            RepositoryMutation mutation,
            Postcondition postcondition
    ) {
        var acceptedState = new DesiredState<>(desired);
        CompletableFuture<Void> result = submit(
                conversationId,
                () -> {
                    runReconciled(
                            mutation,
                            postcondition,
                            () -> removeCanonicalDesired(conversationId, acceptedState, recoveryState)
                    );
                    return null;
                },
                true,
                () -> recoveryState.put(conversationId, acceptedState),
                () -> recoveryState.remove(conversationId, acceptedState)
        );
        result.thenRun(() -> {
            synchronized (lifecycleLock) {
                recoveryState.remove(conversationId, acceptedState);
            }
        });
        return result;
    }

    private void retryDeferredSidebarRecovery(UUID conversationId, boolean failIfIndeterminate) throws Exception {
        try {
            retrySidebarRecovery(conversationId);
        } catch (Exception e) {
            synchronized (lifecycleLock) {
                if (indeterminateRecovery.containsKey(conversationId)) {
                    if (failIfIndeterminate) {
                        throw e;
                    }
                    return;
                }
                failedSidebarRecoveries.add(conversationId);
            }
        }
    }

    private void retrySidebarRecovery(UUID conversationId) throws Exception {
        SidebarDesiredState<String> titleState;
        SidebarDesiredState<Boolean> favoriteState;
        synchronized (lifecycleLock) {
            titleState = renameRecovery.get(conversationId);
            favoriteState = favoriteRecovery.get(conversationId);
        }

        Throwable failure = null;
        if (titleState != null && titleState.recoveryRequired()) {
            String title = titleState.desired();
            failure = retryRecovery(
                    failure,
                    conversationId,
                    titleState,
                    renameRecovery,
                    () -> conversationRepo.updateTitle(conversationId, title),
                    () -> conversationRepo.hasTitle(conversationId, title),
                    () -> markSidebarRecoveryFailed(conversationId, titleState, renameRecovery)
            );
            removeDefinitiveSidebarRecovery(conversationId, titleState, renameRecovery, failure);
            throwIfIndeterminate(conversationId, failure);
        }
        if (favoriteState != null && favoriteState.recoveryRequired()) {
            boolean favorite = favoriteState.desired();
            failure = retryRecovery(
                    failure,
                    conversationId,
                    favoriteState,
                    favoriteRecovery,
                    () -> conversationRepo.setFavorite(conversationId, favorite),
                    () -> conversationRepo.hasFavorite(conversationId, favorite),
                    () -> markSidebarRecoveryFailed(conversationId, favoriteState, favoriteRecovery)
            );
            removeDefinitiveSidebarRecovery(conversationId, favoriteState, favoriteRecovery, failure);
            throwIfIndeterminate(conversationId, failure);
        }
        throwFailure(failure, "Failed to recover conversation Sidebar settings");
    }

    private <T> void removeDefinitiveSidebarRecovery(
            UUID conversationId,
            T desired,
            Map<UUID, T> recoveryState,
            Throwable failure
    ) {
        if (failure == null) {
            return;
        }
        synchronized (lifecycleLock) {
            if (!indeterminateRecovery.containsKey(conversationId)) {
                markSidebarRecoveryFailed(conversationId, desired, recoveryState);
            }
        }
    }

    private <T> void markSidebarRecoveryFailed(UUID conversationId, T desired, Map<UUID, T> recoveryState) {
        synchronized (lifecycleLock) {
            recoveryState.remove(conversationId, desired);
            failedSidebarRecoveries.add(conversationId);
        }
    }

    private <T, S extends DesiredState<T>> void removeCanonicalDesired(
            UUID conversationId,
            S confirmedState,
            Map<UUID, S> recoveryState
    ) {
        synchronized (lifecycleLock) {
            S currentState = recoveryState.get(conversationId);
            if (currentState != null && Objects.equals(currentState.desired(), confirmedState.desired())) {
                recoveryState.remove(conversationId, currentState);
            }
        }
    }

    private void retryRuntimeRecovery(UUID conversationId) throws Exception {
        DesiredState<AgentSettingsRecovery> agentSettingsState;
        DesiredState<ReasoningLevel> reasoningLevelState;
        DesiredState<WebSearchSettingsRecovery> webSearchSettingsState;
        synchronized (lifecycleLock) {
            agentSettingsState = agentSettingsRecovery.get(conversationId);
            reasoningLevelState = reasoningLevelRecovery.get(conversationId);
            webSearchSettingsState = webSearchSettingsRecovery.get(conversationId);
        }

        Throwable failure = null;
        if (agentSettingsState != null) {
            AgentSettingsRecovery agentSettings = agentSettingsState.desired();
            failure = retryRecovery(
                    failure,
                    conversationId,
                    agentSettingsState,
                    agentSettingsRecovery,
                    () -> conversationRepo.updateAgentSettings(
                            conversationId,
                            agentSettings.enabled(),
                            agentSettings.projectRoot()
                    ),
                    () -> conversationRepo.hasAgentSettings(
                            conversationId,
                            agentSettings.enabled(),
                            agentSettings.projectRoot()
                    )
            );
            throwIfIndeterminate(conversationId, failure);
        }
        if (reasoningLevelState != null) {
            ReasoningLevel reasoningLevel = reasoningLevelState.desired();
            failure = retryRecovery(
                    failure,
                    conversationId,
                    reasoningLevelState,
                    reasoningLevelRecovery,
                    () -> conversationRepo.updateReasoningLevel(conversationId, reasoningLevel),
                    () -> conversationRepo.hasReasoningLevel(conversationId, reasoningLevel)
            );
            throwIfIndeterminate(conversationId, failure);
        }
        if (webSearchSettingsState != null) {
            WebSearchSettingsRecovery webSearchSettings = webSearchSettingsState.desired();
            failure = retryRecovery(
                    failure,
                    conversationId,
                    webSearchSettingsState,
                    webSearchSettingsRecovery,
                    () -> conversationRepo.updateWebSearchSettings(
                            conversationId,
                            webSearchSettings.enabled(),
                            webSearchSettings.optionId()
                    ),
                    () -> conversationRepo.hasWebSearchSettings(
                            conversationId,
                            webSearchSettings.enabled(),
                            webSearchSettings.optionId()
                    )
            );
            throwIfIndeterminate(conversationId, failure);
        }
        throwFailure(failure, "Failed to recover conversation runtime settings");
    }

    private void throwIfIndeterminate(UUID conversationId, Throwable failure) throws Exception {
        synchronized (lifecycleLock) {
            if (!indeterminateRecovery.containsKey(conversationId)) {
                return;
            }
        }
        throwFailure(failure, "Conversation persistence outcome remains indeterminate");
    }

    private void throwFailure(Throwable failure, String message) throws Exception {
        if (failure instanceof Exception e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        if (failure != null) {
            throw new IllegalStateException(message, failure);
        }
    }

    private Throwable retryAutomaticRecovery() {
        Throwable primary = retryAllIndeterminate();
        Set<UUID> messageRecoveryIds;
        Map<UUID, DesiredState<AgentSettingsRecovery>> agentSnapshot;
        Map<UUID, DesiredState<ReasoningLevel>> reasoningSnapshot;
        Map<UUID, DesiredState<WebSearchSettingsRecovery>> webSearchSnapshot;
        Map<UUID, SidebarDesiredState<String>> renameSnapshot;
        Map<UUID, SidebarDesiredState<Boolean>> favoriteSnapshot;
        Map<ExplicitRecoveryKey, RepositoryMutation> explicitSnapshot;
        synchronized (lifecycleLock) {
            messageRecoveryIds = Set.copyOf(automaticRecovery.keySet());
            agentSnapshot = Map.copyOf(agentSettingsRecovery);
            reasoningSnapshot = Map.copyOf(reasoningLevelRecovery);
            webSearchSnapshot = Map.copyOf(webSearchSettingsRecovery);
            renameSnapshot = Map.copyOf(renameRecovery);
            favoriteSnapshot = Map.copyOf(favoriteRecovery);
            explicitSnapshot = new LinkedHashMap<>(explicitRecovery);
        }

        for (Map.Entry<ExplicitRecoveryKey, RepositoryMutation> recovery : explicitSnapshot.entrySet()) {
            try {
                synchronized (lifecycleLock) {
                    if (recoveryBlocked(recovery.getKey().conversationId())
                            || !explicitRecovery.remove(recovery.getKey(), recovery.getValue())
                    ) {
                        continue;
                    }
                }
                runFinalRecovery(recovery.getKey().conversationId(), recovery.getValue());
            } catch (Throwable t) {
                primary = combineFailure(primary, t);
            }
        }
        for (UUID conversationId : messageRecoveryIds) {
            try {
                synchronized (lifecycleLock) {
                    if (recoveryBlocked(conversationId)) {
                        continue;
                    }
                }
                runFinalRecovery(conversationId, () -> retryAssistantRecovery(conversationId));
            } catch (Throwable t) {
                primary = combineFailure(primary, t);
            }
        }
        for (Map.Entry<UUID, SidebarDesiredState<String>> recovery : renameSnapshot.entrySet()) {
            if (!recovery.getValue().recoveryRequired()) {
                continue;
            }
            primary = retryFinalDesiredStateRecovery(
                    primary,
                    recovery.getKey(),
                    recovery.getValue(),
                    renameRecovery,
                    () -> conversationRepo.updateTitle(recovery.getKey(), recovery.getValue().desired()),
                    () -> conversationRepo.hasTitle(recovery.getKey(), recovery.getValue().desired())
            );
        }
        for (Map.Entry<UUID, SidebarDesiredState<Boolean>> recovery : favoriteSnapshot.entrySet()) {
            if (!recovery.getValue().recoveryRequired()) {
                continue;
            }
            primary = retryFinalDesiredStateRecovery(
                    primary,
                    recovery.getKey(),
                    recovery.getValue(),
                    favoriteRecovery,
                    () -> conversationRepo.setFavorite(recovery.getKey(), recovery.getValue().desired()),
                    () -> conversationRepo.hasFavorite(recovery.getKey(), recovery.getValue().desired())
            );
        }
        for (Map.Entry<UUID, DesiredState<AgentSettingsRecovery>> recovery : agentSnapshot.entrySet()) {
            AgentSettingsRecovery desired = recovery.getValue().desired();
            primary = retryFinalDesiredStateRecovery(
                    primary,
                    recovery.getKey(),
                    recovery.getValue(),
                    agentSettingsRecovery,
                    () -> conversationRepo.updateAgentSettings(
                            recovery.getKey(),
                            desired.enabled(),
                            desired.projectRoot()
                    ),
                    () -> conversationRepo.hasAgentSettings(
                            recovery.getKey(),
                            desired.enabled(),
                            desired.projectRoot()
                    )
            );
        }
        for (Map.Entry<UUID, DesiredState<ReasoningLevel>> recovery : reasoningSnapshot.entrySet()) {
            ReasoningLevel desired = recovery.getValue().desired();
            primary = retryFinalDesiredStateRecovery(
                    primary,
                    recovery.getKey(),
                    recovery.getValue(),
                    reasoningLevelRecovery,
                    () -> conversationRepo.updateReasoningLevel(recovery.getKey(), desired),
                    () -> conversationRepo.hasReasoningLevel(recovery.getKey(), desired)
            );
        }
        for (Map.Entry<UUID, DesiredState<WebSearchSettingsRecovery>> recovery : webSearchSnapshot.entrySet()) {
            WebSearchSettingsRecovery desired = recovery.getValue().desired();
            primary = retryFinalDesiredStateRecovery(
                    primary,
                    recovery.getKey(),
                    recovery.getValue(),
                    webSearchSettingsRecovery,
                    () -> conversationRepo.updateWebSearchSettings(
                            recovery.getKey(),
                            desired.enabled(),
                            desired.optionId()
                    ),
                    () -> conversationRepo.hasWebSearchSettings(
                            recovery.getKey(),
                            desired.enabled(),
                            desired.optionId()
                    )
            );
        }
        return primary;
    }

    private void runFinalRecovery(UUID conversationId, RepositoryMutation mutation) throws Exception {
        currentMutationIds.set(List.of(conversationId));
        try {
            mutation.run();
        } finally {
            currentMutationIds.remove();
        }
    }

    private <T, S extends DesiredState<T>> Throwable retryFinalDesiredStateRecovery(
            Throwable primary,
            UUID conversationId,
            S desired,
            Map<UUID, S> recoveryState,
            RepositoryMutation mutation,
            Postcondition postcondition
    ) {
        currentMutationIds.set(List.of(conversationId));
        try {
            return retryRecovery(primary, conversationId, desired, recoveryState, mutation, postcondition);
        } finally {
            currentMutationIds.remove();
        }
    }

    private <T, S extends DesiredState<T>> Throwable retryRecovery(
            Throwable primary,
            UUID conversationId,
            S desired,
            Map<UUID, S> recoveryState,
            RepositoryMutation mutation,
            Postcondition postcondition
    ) {
        return retryRecovery(
                primary,
                conversationId,
                desired,
                recoveryState,
                mutation,
                postcondition,
                () -> {}
        );
    }

    private <T, S extends DesiredState<T>> Throwable retryRecovery(
            Throwable primary,
            UUID conversationId,
            S desired,
            Map<UUID, S> recoveryState,
            RepositoryMutation mutation,
            Postcondition postcondition,
            Runnable onNonCanonicalReconciliation
    ) {
        try {
            synchronized (lifecycleLock) {
                if (recoveryBlocked(conversationId)
                        || !Objects.equals(recoveryState.get(conversationId), desired)
                ) {
                    return primary;
                }
            }
            Runnable removeExactDesired = () -> {
                synchronized (lifecycleLock) {
                    recoveryState.remove(conversationId, desired);
                }
            };
            runReconciled(
                    mutation,
                    postcondition,
                    () -> removeCanonicalDesired(conversationId, desired, recoveryState),
                    onNonCanonicalReconciliation
            );
            removeExactDesired.run();
            return primary;
        } catch (Throwable t) {
            return combineFailure(primary, t);
        }
    }

    private Throwable combineFailure(Throwable primary, Throwable failure) {
        if (primary == null) {
            return failure;
        }
        if (failure != primary) {
            primary.addSuppressed(failure);
        }
        return primary;
    }

    private boolean recoveryBlocked(UUID conversationId) {
        return indeterminateRecovery.containsKey(conversationId)
                || deletedConversationIds.contains(conversationId);
    }

    private boolean registerAutomaticRecovery(UUID conversationId, ConversationHistoryEntry entry) {
        List<ConversationHistoryEntry> existing = automaticRecovery.getOrDefault(conversationId, List.of());
        ConversationHistoryEntry sameIdentity = existing.stream()
                .filter(candidate -> candidate.messageId().equals(entry.messageId()))
                .findFirst()
                .orElse(null);
        if (sameIdentity != null) {
            if (!sameIdentity.equals(entry)) {
                throw new RejectedExecutionException("Conflicting assistant message recovery identity");
            }
            return false;
        }
        var updated = new ArrayList<>(existing);
        updated.add(entry);
        updated.sort(Comparator.comparingInt(ConversationHistoryEntry::ordinal));
        automaticRecovery.put(conversationId, List.copyOf(updated));
        return true;
    }

    private void removeAutomaticRecovery(UUID conversationId, ConversationHistoryEntry entry) {
        List<ConversationHistoryEntry> existing = automaticRecovery.get(conversationId);
        if (existing == null) {
            return;
        }
        List<ConversationHistoryEntry> remaining = existing.stream()
                .filter(candidate -> !candidate.equals(entry))
                .toList();
        if (remaining.isEmpty()) {
            automaticRecovery.remove(conversationId);
        } else {
            automaticRecovery.put(conversationId, remaining);
        }
    }

    private void retryAssistantRecoveryForLoad(UUID conversationId) throws Exception {
        try {
            retryAssistantRecovery(conversationId);
        } catch (Exception e) {
            synchronized (lifecycleLock) {
                if (indeterminateRecovery.containsKey(conversationId)) {
                    throw e;
                }
            }
        }
    }

    private void retryAssistantRecovery(UUID conversationId) throws Exception {
        List<ConversationHistoryEntry> entries;
        synchronized (lifecycleLock) {
            entries = automaticRecovery.getOrDefault(conversationId, List.of());
        }
        for (ConversationHistoryEntry entry : entries) {
            appendReconciled(conversationId, entry);
            synchronized (lifecycleLock) {
                removeAutomaticRecovery(conversationId, entry);
            }
        }
    }

    private void appendReconciled(UUID conversationId, ConversationHistoryEntry entry) throws Exception {
        appendReconciled(conversationId, entry, () -> {});
    }

    private void appendReconciled(
            UUID conversationId,
            ConversationHistoryEntry entry,
            Runnable onCanonicalReconciliation
    ) throws Exception {
        runReconciled(
                () -> conversationRepo.appendMessage(conversationId, entry),
                () -> conversationRepo.isCanonicalEntry(conversationId, entry),
                onCanonicalReconciliation
        );
    }

    private void runReconciled(RepositoryMutation mutation, Postcondition postcondition) throws Exception {
        runReconciled(
                currentMutationIds.get(),
                mutation,
                postcondition,
                () -> {},
                () -> {},
                ReconciliationType.OTHER
        );
    }

    private void runReconciled(
            RepositoryMutation mutation,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation
    ) throws Exception {
        runReconciled(currentMutationIds.get(), mutation, postcondition, onCanonicalReconciliation, () -> {});
    }

    private void runReconciled(
            RepositoryMutation mutation,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation,
            Runnable onNonCanonicalReconciliation
    ) throws Exception {
        runReconciled(
                currentMutationIds.get(),
                mutation,
                postcondition,
                onCanonicalReconciliation,
                onNonCanonicalReconciliation
        );
    }

    private void runReconciled(
            List<UUID> affectedConversationIds,
            RepositoryMutation mutation,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation,
            Runnable onNonCanonicalReconciliation
    ) throws Exception {
        runReconciled(
                affectedConversationIds,
                mutation,
                postcondition,
                onCanonicalReconciliation,
                onNonCanonicalReconciliation,
                ReconciliationType.OTHER
        );
    }

    private void runReconciled(
            List<UUID> affectedConversationIds,
            RepositoryMutation mutation,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation,
            Runnable onNonCanonicalReconciliation,
            ReconciliationType reconciliationType
    ) throws Exception {
        try {
            mutation.run();
        } catch (Exception originalFailure) {
            try {
                if (postcondition.matches()) {
                    return;
                }
            } catch (Exception reconciliationFailure) {
                originalFailure.addSuppressed(reconciliationFailure);
                registerIndeterminate(
                        affectedConversationIds,
                        postcondition,
                        onCanonicalReconciliation,
                        onNonCanonicalReconciliation,
                        reconciliationType
                );
                throw new ConversationPersistenceIndeterminateException(originalFailure);
            }
            throw originalFailure;
        }
    }

    private void registerIndeterminate(
            List<UUID> conversationIds,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation,
            Runnable onNonCanonicalReconciliation,
            ReconciliationType reconciliationType
    ) {
        if (conversationIds.isEmpty()) {
            return;
        }
        var recovery = new IndeterminateRecovery(
                List.copyOf(conversationIds),
                postcondition,
                onCanonicalReconciliation,
                onNonCanonicalReconciliation,
                new CompletableFuture<>(),
                reconciliationType
        );
        synchronized (lifecycleLock) {
            conversationIds.forEach(conversationId -> indeterminateRecovery.put(conversationId, recovery));
        }
    }

    private boolean reconcileIndeterminate(UUID conversationId) throws Exception {
        IndeterminateRecovery recovery;
        synchronized (lifecycleLock) {
            recovery = indeterminateRecovery.get(conversationId);
        }
        return recovery != null && reconcileIndeterminate(recovery);
    }

    private boolean reconcileIndeterminate(IndeterminateRecovery recovery) throws Exception {
        Boolean settledOutcome = recovery.outcome().getNow(null);
        if (settledOutcome != null) {
            return settledOutcome;
        }
        boolean canonical = recovery.postcondition().matches();
        synchronized (lifecycleLock) {
            if (canonical) {
                recovery.onCanonicalReconciliation().run();
            } else {
                recovery.onNonCanonicalReconciliation().run();
            }
            recovery.outcome().complete(canonical);
            recovery.conversationIds().forEach(id -> {
                indeterminateRecovery.remove(id, recovery);
                List<IndeterminateRecovery> settled = settledReconciliations.computeIfAbsent(
                        id,
                        ignored -> new ArrayList<>()
                );
                if (settled.stream().noneMatch(candidate -> candidate == recovery)) {
                    settled.add(recovery);
                }
            });
        }
        return canonical;
    }

    private void clearSettledRecoveries(UUID conversationId) {
        List<IndeterminateRecovery> settled = List.copyOf(
                settledReconciliations.getOrDefault(conversationId, List.of())
        );
        settled.forEach(this::removeSettledRecovery);
    }

    private boolean containsSettledRecovery(UUID conversationId, IndeterminateRecovery recovery) {
        return settledReconciliations.getOrDefault(conversationId, List.of()).stream()
                .anyMatch(candidate -> candidate == recovery);
    }

    private IndeterminateRecovery pollSettledRecovery(UUID conversationId) {
        List<IndeterminateRecovery> settled = settledReconciliations.get(conversationId);
        if (settled == null || settled.isEmpty()) {
            return null;
        }
        IndeterminateRecovery recovery = settled.getFirst();
        removeSettledRecovery(recovery);
        return recovery;
    }

    private void removeSettledRecovery(IndeterminateRecovery recovery) {
        recovery.conversationIds().forEach(id -> {
            List<IndeterminateRecovery> settled = settledReconciliations.get(id);
            if (settled == null) {
                return;
            }
            settled.removeIf(candidate -> candidate == recovery);
            if (settled.isEmpty()) {
                settledReconciliations.remove(id);
            }
        });
    }

    private Throwable retryAllIndeterminate() {
        List<IndeterminateRecovery> recoveries;
        synchronized (lifecycleLock) {
            recoveries = indeterminateRecovery.values().stream().distinct().toList();
        }
        Throwable primary = null;
        for (IndeterminateRecovery recovery : recoveries) {
            try {
                reconcileIndeterminate(recovery);
            } catch (Throwable t) {
                primary = combineFailure(primary, t);
            }
        }
        return primary;
    }

    private void removeExplicitRecovery(ExplicitRecoveryKey recoveryKey) {
        synchronized (lifecycleLock) {
            explicitRecovery.remove(recoveryKey);
        }
    }

    private void removeRecoveryAfterOrdinal(UUID conversationId, int retainedOrdinal) {
        synchronized (lifecycleLock) {
            List<ConversationHistoryEntry> assistantEntries = automaticRecovery.get(conversationId);
            if (assistantEntries != null) {
                List<ConversationHistoryEntry> retainedEntries = assistantEntries.stream()
                        .filter(entry -> entry.ordinal() <= retainedOrdinal)
                        .toList();
                if (retainedEntries.isEmpty()) {
                    automaticRecovery.remove(conversationId);
                } else {
                    automaticRecovery.put(conversationId, retainedEntries);
                }
            }
            explicitRecovery.keySet().removeIf(key -> key.conversationId().equals(conversationId)
                    && key.ordinal() > retainedOrdinal);
        }
    }

    private void removeMessageRecovery(UUID conversationId) {
        automaticRecovery.remove(conversationId);
        pendingAssistantCommands.removeIf(key -> key.conversationId().equals(conversationId));
        explicitRecovery.keySet().removeIf(key -> key.conversationId().equals(conversationId));
        indeterminateRecovery.remove(conversationId);
        clearSettledRecoveries(conversationId);
        failedSidebarRecoveries.remove(conversationId);
    }

    private void removeRecovery(UUID conversationId) {
        removeMessageRecovery(conversationId);
        agentSettingsRecovery.remove(conversationId);
        reasoningLevelRecovery.remove(conversationId);
        webSearchSettingsRecovery.remove(conversationId);
        renameRecovery.remove(conversationId);
        favoriteRecovery.remove(conversationId);
    }

    private void completeTerminal(Throwable failure) {
        CompletableFuture<Void> future;
        synchronized (lifecycleLock) {
            future = terminalFuture;
        }
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(failure);
        }
    }

    public record ReconciliationResult(boolean canonical, List<UUID> deleteConversationIds) {
        public ReconciliationResult {
            deleteConversationIds = List.copyOf(deleteConversationIds);
        }

        public boolean reconcilesDelete() {
            return !deleteConversationIds.isEmpty();
        }
    }

    @FunctionalInterface
    private interface RepositoryMutation {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Postcondition {
        boolean matches() throws Exception;
    }

    private record AgentSettingsRecovery(boolean enabled, Path projectRoot) {
    }

    private static class DesiredState<T> {
        private final T desired;

        DesiredState(T desired) {
            this.desired = desired;
        }

        T desired() {
            return desired;
        }
    }

    private static final class SidebarDesiredState<T> extends DesiredState<T> {
        private boolean recoveryRequired;

        private SidebarDesiredState(T desired) {
            super(desired);
        }

        private boolean recoveryRequired() {
            return recoveryRequired;
        }

        private void markRecoveryRequired() {
            recoveryRequired = true;
        }
    }

    private record WebSearchSettingsRecovery(boolean enabled, String optionId) {
    }

    private record AssistantRecoveryKey(UUID conversationId, UUID messageId) {
    }

    private record ExplicitRecoveryKey(
            UUID conversationId,
            UUID messageId,
            int ordinal,
            ExplicitMutationType type
    ) {
    }

    private record IndeterminateRecovery(
            List<UUID> conversationIds,
            Postcondition postcondition,
            Runnable onCanonicalReconciliation,
            Runnable onNonCanonicalReconciliation,
            CompletableFuture<Boolean> outcome,
            ReconciliationType type
    ) {
    }

    private enum ReconciliationType {
        DELETE,
        OTHER
    }

    private enum ExplicitMutationType {
        USER,
        EDIT,
        EDIT_AND_TRUNCATE,
        TRUNCATE
    }
}
