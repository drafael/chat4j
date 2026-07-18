package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;

import static java.util.Collections.emptyMap;

public final class CredentialTestSupport {

    private CredentialTestSupport() {
    }

    public static void configureVault(@NonNull StoragePaths storagePaths) {
        CredentialResolver.configureTokenVault(new ApiTokenVault(storagePaths));
    }

    public static void reset() {
        Path isolatedConfigHome = Path.of("target", "credential-test-reset", UUID.randomUUID().toString());
        CredentialResolver.configureProcessEnv(System::getenv);
        configureVault(StoragePaths.ofConfigHome(isolatedConfigHome));
        CredentialResolver.init(emptyMap());
    }

    static void saveToken(@NonNull ApiTokenVault vault, String tokenId, @NonNull char[] token) {
        vault.applyTokenMutation(tokenId, List.of(tokenId), token);
    }

    static void deleteToken(@NonNull ApiTokenVault vault, String tokenId) {
        vault.applyTokenMutation(tokenId, List.of(tokenId), null);
    }

    public static CredentialMutationResult saveToken(String envVarExpression, @NonNull char[] token) {
        return CredentialMutationService.shared().saveTokenOverride(
                envVarExpression,
                token,
                CredentialMutationListener.NO_OP
        );
    }
}
