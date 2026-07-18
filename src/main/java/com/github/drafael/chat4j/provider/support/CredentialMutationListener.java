package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

@FunctionalInterface
public interface CredentialMutationListener {

    CredentialMutationListener NO_OP = result -> {
    };

    void mutationCompleted(@NonNull CredentialMutationResult result);
}
