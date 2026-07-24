package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.provider.api.ProviderDiagnosticSanitizer;
import java.net.URI;
import java.time.Duration;
import lombok.NonNull;

public record SpeechToTextProviderContext(
        URI baseUri,
        URI transcriptionUri,
        @NonNull CredentialSource credentialSource,
        @NonNull CancellationToken cancellationToken,
        @NonNull Duration timeout,
        LocalSpeechToTextModelReference localModelReference
) {

    public SpeechToTextProviderContext(
            URI baseUri,
            URI transcriptionUri,
            @NonNull CredentialSource credentialSource,
            @NonNull CancellationToken cancellationToken,
            @NonNull Duration timeout
    ) {
        this(baseUri, transcriptionUri, credentialSource, cancellationToken, timeout, null);
    }

    public boolean cancelled() {
        return cancellationToken.cancelled();
    }

    @Override
    public String toString() {
        return "SpeechToTextProviderContext[baseUri=%s, transcriptionUri=%s, credentialSource=<masked>, timeout=%s, localModel=%s]"
                .formatted(
                        ProviderDiagnosticSanitizer.safeOrigin(baseUri == null ? null : baseUri.toString()),
                        ProviderDiagnosticSanitizer.safeOrigin(transcriptionUri == null ? null : transcriptionUri.toString()),
                        timeout,
                        localModelReference == null ? "none" : "configured"
                );
    }

    public interface CancellationToken {
        boolean cancelled();

        static CancellationToken never() {
            return () -> false;
        }
    }
}
