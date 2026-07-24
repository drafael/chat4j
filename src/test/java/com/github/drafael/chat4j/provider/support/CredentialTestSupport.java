package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

public final class CredentialTestSupport {

    private CredentialTestSupport() {
    }

    public static CredentialAssembly create(@NonNull StoragePaths storagePaths) {
        return create(storagePaths, emptyMap(), emptyMap());
    }

    public static CredentialAssembly create(
            @NonNull StoragePaths storagePaths,
            Map<String, String> processEnvironment,
            Map<String, String> shellEnvironment
    ) {
        ApiTokenVault vault = new ApiTokenVault(storagePaths);
        CredentialResolver resolver = new CredentialResolver(vault, processEnvironment, shellEnvironment);
        CredentialMutationService mutationService = new CredentialMutationService(vault, resolver);
        return new CredentialAssembly(vault, resolver, mutationService);
    }

    static void saveToken(@NonNull ApiTokenVault vault, String tokenId, @NonNull char[] token) {
        vault.applyTokenMutation(tokenId, List.of(tokenId), token);
    }

    static void deleteToken(@NonNull ApiTokenVault vault, String tokenId) {
        vault.applyTokenMutation(tokenId, List.of(tokenId), null);
    }

    public record CredentialAssembly(
            ApiTokenVault vault,
            CredentialResolver resolver,
            CredentialMutationService mutationService
    ) implements AutoCloseable {
        @Override
        public void close() {
            mutationService.closeSecrets();
        }
    }
}
