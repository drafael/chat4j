package com.github.drafael.chat4j.provider.support;

import java.util.Set;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;

import static java.util.Arrays.fill;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class CredentialMutationService {

    private static final String MUTATION_FAILED_MESSAGE =
            "Could not update saved credentials. Durable state was reloaded.";
    private static final String RECREATION_FAILED_MESSAGE =
            "Could not recreate the token vault. Durable state was reloaded.";
    private static final String CLOSED_MESSAGE = "Credential service is closed.";

    @NonNull
    private final ApiTokenVault tokenVault;
    private final Object mutationLock = new Object();
    private volatile boolean closed;

    public static CredentialMutationService shared() {
        return CredentialResolver.mutationService();
    }

    public CredentialMutationResult saveTokenOverride(
            String envVarExpression,
            @NonNull char[] token,
            CredentialMutationListener listener
    ) {
        CredentialMutationResult result;
        CredentialMutationListener validatedListener;
        try {
            validatedListener = Validate.notNull(listener, "listener should not be null");
            Validate.notBlank(envVarExpression, "envVarExpression should not be blank");
            CredentialTokenIds.MutationTokenIds tokenIds = CredentialTokenIds.mutationTokenIds(envVarExpression);
            ApiTokenVault.validateTokenInput(token);
            result = mutateToken(tokenIds, token);
        } finally {
            fill(token, '\0');
        }
        return notifyListener(result, validatedListener);
    }

    public CredentialMutationResult recreateVault(@NonNull CredentialMutationListener listener) {
        Set<String> affectedTokenIds = CredentialTokenIds.supportedCanonicalTokenIds();
        CredentialMutationResult result;
        if (closed) {
            result = closedResult(affectedTokenIds);
        } else {
            synchronized (mutationLock) {
                if (closed) {
                    result = closedResult(affectedTokenIds);
                } else {
                    result = recreateVaultLocked(affectedTokenIds);
                }
            }
        }
        return notifyListener(result, listener);
    }

    public void closeSecrets() {
        if (closed) {
            return;
        }
        closed = true;
        tokenVault.closeSecrets();
    }

    private CredentialMutationResult mutateToken(CredentialTokenIds.MutationTokenIds tokenIds, char[] token) {
        Set<String> affectedTokenIds = Set.of(tokenIds.canonicalTokenId());
        if (closed) {
            return closedResult(affectedTokenIds);
        }
        synchronized (mutationLock) {
            if (closed) {
                return closedResult(affectedTokenIds);
            }
            boolean clearOverride = ApiTokenVault.isBlank(token)
                    || CredentialResolver.matchesEffectiveRawEnvironment(tokenIds.aliases(), token);
            try {
                boolean changed = tokenVault.applyTokenMutation(
                        tokenIds.canonicalTokenId(),
                        tokenIds.aliases(),
                        clearOverride ? null : token
                );
                return new CredentialMutationResult(
                        changed ? CredentialMutationStatus.APPLIED : CredentialMutationStatus.UNCHANGED,
                        affectedTokenIds,
                        ""
                );
            } catch (ApiTokenVault.ApiTokenVaultException e) {
                tokenVault.refreshFromDiskReadOnly();
                return new CredentialMutationResult(
                        CredentialMutationStatus.FAILED_RELOADED,
                        affectedTokenIds,
                        MUTATION_FAILED_MESSAGE
                );
            }
        }
    }

    private CredentialMutationResult recreateVaultLocked(Set<String> affectedTokenIds) {
        try {
            tokenVault.recreateVault();
            return new CredentialMutationResult(
                    CredentialMutationStatus.APPLIED,
                    affectedTokenIds,
                    ""
            );
        } catch (ApiTokenVault.ApiTokenVaultException e) {
            tokenVault.refreshFromDiskReadOnly();
            return new CredentialMutationResult(
                    CredentialMutationStatus.FAILED_RELOADED,
                    affectedTokenIds,
                    RECREATION_FAILED_MESSAGE
            );
        }
    }

    private CredentialMutationResult notifyListener(
            CredentialMutationResult result,
            CredentialMutationListener listener
    ) {
        try {
            listener.mutationCompleted(result);
            return result;
        } catch (RuntimeException e) {
            return result.withNotificationFailure();
        }
    }

    private CredentialMutationResult closedResult(Set<String> affectedTokenIds) {
        return new CredentialMutationResult(
                CredentialMutationStatus.REJECTED_CLOSED,
                affectedTokenIds,
                CLOSED_MESSAGE
        );
    }
}
