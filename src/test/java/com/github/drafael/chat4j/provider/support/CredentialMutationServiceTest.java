package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialMutationServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        CredentialTestSupport.reset();
    }

    @Test
    @DisplayName("Saving a supported token applies one encrypted override and clears the caller buffer")
    void saveTokenOverride_whenTokenIsSupported_appliesOverrideAndClearsInput() {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        var subject = new CredentialMutationService(vault);
        char[] token = "saved-secret".toCharArray();

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                token,
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED);
        assertThat(result.affectedTokenIds()).containsExactly("OPENAI_API_KEY");
        assertThat(token).containsOnly('\0');
        assertThat(readToken(vault, "OPENAI_API_KEY")).isEqualTo("saved-secret");
    }

    @Test
    @DisplayName("Saving the same override reports unchanged without replacing the record")
    void saveTokenOverride_whenSavedTokenAlreadyMatches_reportsUnchanged() {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        var subject = new CredentialMutationService(vault);
        subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "saved-secret".toCharArray(),
                CredentialMutationListener.NO_OP
        );
        String originalVault = readFile(vaultPath());

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "saved-secret".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.UNCHANGED);
        assertThat(readFile(vaultPath())).isEqualTo(originalVault);
    }

    @Test
    @DisplayName("Google AI aliases canonicalize to Gemini before the vault is mutated")
    void saveTokenOverride_whenGoogleAliasRequested_savesCanonicalGeminiRecord() {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        CredentialTestSupport.saveToken(vault, "GOOGLEAI_API_KEY", "old-alias".toCharArray());
        var subject = new CredentialMutationService(vault);

        CredentialMutationResult result = subject.saveTokenOverride(
                "GOOGLEAI_API_KEY",
                "new-token".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        assertThat(result.affectedTokenIds()).containsExactly("GEMINI_API_KEY");
        assertThat(vault.hasRecord("GEMINI_API_KEY")).isTrue();
        assertThat(vault.hasRecord("GOOGLEAI_API_KEY")).isFalse();
        CredentialTestSupport.configureVault(StoragePaths.ofConfigHome(tempDir));
        assertThat(CredentialResolver.resolveRequiredApiKey("GOOGLEAI_API_KEY", null)).isEqualTo("new-token");
    }

    @Test
    @DisplayName("Legacy alias-only records remain readable through the canonical expression")
    void resolveRequiredApiKey_whenLegacyAliasRecordExists_returnsAliasValue() {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var vault = new ApiTokenVault(storagePaths);
        CredentialTestSupport.saveToken(vault, "GOOGLEAI_API_KEY", "legacy-alias".toCharArray());
        CredentialTestSupport.configureVault(storagePaths);

        String result = CredentialResolver.resolveRequiredApiKey(
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                null
        );

        assertThat(result).isEqualTo("legacy-alias");
    }

    @Test
    @DisplayName("Blank input clears every saved alias in one mutation")
    void saveTokenOverride_whenInputIsBlank_clearsSavedAliases() {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        CredentialTestSupport.saveToken(vault, "GOOGLEAI_API_KEY", "old-alias".toCharArray());
        var subject = new CredentialMutationService(vault);

        CredentialMutationResult result = subject.saveTokenOverride(
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                "   ".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED);
        assertThat(vault.hasRecord("GEMINI_API_KEY")).isFalse();
        assertThat(vault.hasRecord("GOOGLEAI_API_KEY")).isFalse();
    }

    @Test
    @DisplayName("A token matching the effective process environment removes redundant saved overrides")
    void saveTokenOverride_whenTokenMatchesEffectiveEnvironment_clearsSavedOverride() {
        CredentialResolver.configureProcessEnv(name -> "OPENAI_API_KEY".equals(name) ? "environment-secret" : null);
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "old-saved-secret".toCharArray());
        var subject = new CredentialMutationService(vault);

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "environment-secret".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED);
        assertThat(vault.hasRecord("OPENAI_API_KEY")).isFalse();
    }

    @Test
    @DisplayName("Unknown token identifiers are rejected before secret files are created")
    void saveTokenOverride_whenTokenIdIsUnknown_rejectsAndClearsInputBeforeIo() {
        var subject = new CredentialMutationService(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        char[] token = "secret".toCharArray();

        assertThatThrownBy(() -> subject.saveTokenOverride(
                "UNKNOWN_API_KEY",
                token,
                CredentialMutationListener.NO_OP
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported token id");

        assertThat(token).containsOnly('\0');
        assertThat(Files.exists(StoragePaths.ofConfigHome(tempDir).secretsDirectory())).isFalse();
    }

    @Test
    @DisplayName("Null completion listener rejection still clears the caller token")
    void saveTokenOverride_whenListenerIsNull_rejectsAndClearsInput() {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new CredentialMutationService(new ApiTokenVault(storagePaths));
        char[] token = "secret".toCharArray();

        assertThatThrownBy(() -> subject.saveTokenOverride("OPENAI_API_KEY", token, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("listener should not be null");

        assertThat(token).containsOnly('\0');
        assertThat(Files.exists(storagePaths.secretsDirectory())).isFalse();
    }

    @Test
    @DisplayName("Malformed UTF-16 input is rejected and cleared before vault I/O")
    void saveTokenOverride_whenTokenContainsUnpairedSurrogate_rejectsAndClearsInput() {
        var subject = new CredentialMutationService(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        char[] token = {'\uD800'};

        assertThatThrownBy(() -> subject.saveTokenOverride(
                "OPENAI_API_KEY",
                token,
                CredentialMutationListener.NO_OP
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("API token contains malformed characters.");

        assertThat(token).containsOnly('\0');
        assertThat(Files.exists(StoragePaths.ofConfigHome(tempDir).secretsDirectory())).isFalse();
    }

    @Test
    @DisplayName("The exact 64 KiB token bound is accepted")
    void saveTokenOverride_whenTokenIsExactlyAtLimit_appliesOverride() {
        var subject = new CredentialMutationService(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        char[] token = new char[ApiTokenVault.MAX_TOKEN_BYTES];
        fill(token, 'a');

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                token,
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED);
        assertThat(token).containsOnly('\0');
    }

    @Test
    @DisplayName("Tokens over 64 KiB are rejected and cleared before vault I/O")
    void saveTokenOverride_whenTokenExceedsLimit_rejectsAndClearsInput() {
        var subject = new CredentialMutationService(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        char[] token = new char[ApiTokenVault.MAX_TOKEN_BYTES + 1];
        fill(token, 'a');

        assertThatThrownBy(() -> subject.saveTokenOverride(
                "OPENAI_API_KEY",
                token,
                CredentialMutationListener.NO_OP
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("API token exceeds the 64 KiB limit.");

        assertThat(token).containsOnly('\0');
        assertThat(Files.exists(StoragePaths.ofConfigHome(tempDir).secretsDirectory())).isFalse();
    }

    @Test
    @DisplayName("Write failures return a sanitized failure after reloading durable state")
    void saveTokenOverride_whenVaultTargetIsUnsafe_returnsFailedReloadedWithoutSecretText() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var vault = new ApiTokenVault(storagePaths);
        Files.createDirectories(storagePaths.tokenVaultFile());
        var subject = new CredentialMutationService(vault);

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "SUPER_SECRET_VALUE".toCharArray(),
                ignored -> {
                    throw new IllegalStateException("listener detail");
                }
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.FAILED_RELOADED_WITH_NOTIFICATION_FAILURE);
        assertThat(result.message())
                .contains(
                        "Could not update saved credentials. Durable state was reloaded.",
                        "Dependent refresh also failed."
                )
                .doesNotContain("SUPER_SECRET_VALUE", "listener detail");
        assertThat(vault.status("OPENAI_API_KEY").source()).isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Close does not relabel an admitted mutation failure as rejected")
    void saveTokenOverride_whenCloseRacesAdmittedFailure_reportsFailedReloaded() throws Exception {
        var mutationReached = new CountDownLatch(1);
        var releaseMutation = new CountDownLatch(1);
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)) {
            @Override
            boolean applyTokenMutation(String canonicalTokenId, List<String> aliases, char[] token) {
                mutationReached.countDown();
                await(releaseMutation);
                throw new ApiTokenVaultException("Simulated admitted failure.");
            }
        };
        var subject = new CredentialMutationService(vault);
        CompletableFuture<CredentialMutationResult> mutation = CompletableFuture.supplyAsync(() ->
                subject.saveTokenOverride(
                        "OPENAI_API_KEY",
                        "new-secret".toCharArray(),
                        CredentialMutationListener.NO_OP
                ));
        assertThat(mutationReached.await(5, TimeUnit.SECONDS)).isTrue();

        subject.closeSecrets();
        releaseMutation.countDown();

        assertThat(mutation.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(CredentialMutationStatus.FAILED_RELOADED);
    }

    @Test
    @DisplayName("Listener failures are reported without retaining the applied mutation")
    void saveTokenOverride_whenCompletionListenerFails_reportsAppliedNotificationFailure() {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        var subject = new CredentialMutationService(vault);

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "saved-secret".toCharArray(),
                ignored -> {
                    throw new IllegalStateException("listener detail");
                }
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED_WITH_NOTIFICATION_FAILURE);
        assertThat(result.message())
                .isEqualTo("Credential change completed, but dependent refresh failed.")
                .doesNotContain("listener detail", "saved-secret");
        assertThat(readToken(vault, "OPENAI_API_KEY")).isEqualTo("saved-secret");
    }

    @Test
    @DisplayName("Terminal close rejects new mutations without creating secret files")
    void saveTokenOverride_whenServiceIsClosed_rejectsWithoutIo() {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var subject = new CredentialMutationService(new ApiTokenVault(storagePaths));
        subject.closeSecrets();

        CredentialMutationResult result = subject.saveTokenOverride(
                "OPENAI_API_KEY",
                "saved-secret".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.REJECTED_CLOSED);
        assertThat(Files.exists(storagePaths.secretsDirectory())).isFalse();
    }

    @Test
    @DisplayName("Vault recreation notifies every supported canonical credential without duplicate aliases")
    void recreateVault_whenRequested_notifiesEveryCanonicalToken() {
        var subject = new CredentialMutationService(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        var notified = new AtomicReference<CredentialMutationResult>();

        CredentialMutationResult result = subject.recreateVault(notified::set);

        assertThat(result.status()).isEqualTo(CredentialMutationStatus.APPLIED);
        assertThat(notified.get()).isEqualTo(result);
        assertThat(result.affectedTokenIds())
                .containsExactlyInAnyOrderElementsOf(CredentialTokenIds.supportedCanonicalTokenIds())
                .doesNotContain("GOOGLEAI_API_KEY");
    }

    @Test
    @DisplayName("Terminal close is idempotent on the EDT")
    void closeSecrets_whenCalledRepeatedlyOnEdt_isIdempotentAndRejectsReads() throws Exception {
        var vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir));
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "saved-secret".toCharArray());
        var subject = new CredentialMutationService(vault);

        SwingUtilities.invokeAndWait(subject::closeSecrets);
        SwingUtilities.invokeAndWait(subject::closeSecrets);

        assertThat(vault.status("OPENAI_API_KEY"))
                .extracting(ApiCredentialStatus::source, ApiCredentialStatus::errorMessage)
                .containsExactly(ApiCredentialSource.ERROR, "Credential service is closed.");
    }

    @Test
    @DisplayName("Low-level vault mutation methods are not public production APIs")
    void mutationApi_whenInspected_exposesCredentialMutationServiceAsPublicBoundary() throws Exception {
        List<String> lowLevelMethodNames = List.of(
                "applyTokenMutation",
                "recreateVault",
                "refreshFromDiskReadOnly"
        );

        assertThat(stream(ApiTokenVault.class.getDeclaredMethods())
                .filter(method -> lowLevelMethodNames.contains(method.getName())))
                .allSatisfy(method -> assertThat(Modifier.isPublic(method.getModifiers())).isFalse());
        assertThat(stream(CredentialResolver.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("saveTokenOverride", "recreateTokenVault");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting test release");
        }
    }

    private Path vaultPath() {
        return StoragePaths.ofConfigHome(tempDir).tokenVaultFile();
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String readToken(ApiTokenVault vault, String tokenId) {
        try (ApiTokenLookup lookup = vault.readTokenChars(tokenId)) {
            return lookup.present() ? String.valueOf(lookup.token()) : null;
        }
    }
}
