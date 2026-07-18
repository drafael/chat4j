package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;

public record CredentialMutationResult(
        @NonNull CredentialMutationStatus status,
        @NonNull Set<String> affectedTokenIds,
        String message
) {

    public CredentialMutationResult {
        affectedTokenIds = Set.copyOf(affectedTokenIds);
        message = StringUtils.defaultString(message);
    }

    public boolean applied() {
        return status == CredentialMutationStatus.APPLIED
                || status == CredentialMutationStatus.APPLIED_WITH_NOTIFICATION_FAILURE;
    }

    public boolean successful() {
        return applied()
                || status == CredentialMutationStatus.UNCHANGED
                || status == CredentialMutationStatus.UNCHANGED_WITH_NOTIFICATION_FAILURE;
    }

    public boolean requiresDependentRefresh() {
        return applied()
                || status == CredentialMutationStatus.FAILED_RELOADED
                || status == CredentialMutationStatus.FAILED_RELOADED_WITH_NOTIFICATION_FAILURE;
    }

    CredentialMutationResult withNotificationFailure() {
        CredentialMutationStatus updatedStatus = switch (status) {
            case APPLIED, APPLIED_WITH_NOTIFICATION_FAILURE ->
                    CredentialMutationStatus.APPLIED_WITH_NOTIFICATION_FAILURE;
            case UNCHANGED, UNCHANGED_WITH_NOTIFICATION_FAILURE ->
                    CredentialMutationStatus.UNCHANGED_WITH_NOTIFICATION_FAILURE;
            case FAILED_RELOADED, FAILED_RELOADED_WITH_NOTIFICATION_FAILURE ->
                    CredentialMutationStatus.FAILED_RELOADED_WITH_NOTIFICATION_FAILURE;
            default -> status;
        };
        if (updatedStatus == status) {
            return this;
        }
        String updatedMessage = switch (updatedStatus) {
            case APPLIED_WITH_NOTIFICATION_FAILURE, UNCHANGED_WITH_NOTIFICATION_FAILURE ->
                    "Credential change completed, but dependent refresh failed.";
            case FAILED_RELOADED_WITH_NOTIFICATION_FAILURE ->
                    "%s Dependent refresh also failed.".formatted(message);
            default -> message;
        };
        return new CredentialMutationResult(updatedStatus, affectedTokenIds, updatedMessage);
    }
}
