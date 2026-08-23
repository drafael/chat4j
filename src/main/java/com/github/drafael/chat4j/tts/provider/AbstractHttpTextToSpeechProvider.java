package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public abstract class AbstractHttpTextToSpeechProvider implements TextToSpeechProvider {

    private final TtsHttpClient httpClient;
    private final CredentialResolver credentialResolver;

    protected AbstractHttpTextToSpeechProvider(
            @NonNull TtsHttpClient httpClient,
            @NonNull CredentialResolver credentialResolver
    ) {
        this.httpClient = httpClient;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public boolean available() {
        return credentialResolver.hasRequiredCredentials(requiredEnvVar());
    }

    @Override
    public String apiKey() {
        return credentialResolver.resolveRequiredApiKey(requiredEnvVar(), null);
    }

    @Override
    public String availableMessage() {
        ApiCredentialStatus status = credentialResolver.resolveCredentialStatus(requiredEnvVar(), null);
        if (status.source() == ApiCredentialSource.SAVED_TOKEN) {
            return "Using %s with entered/stored API token.".formatted(displayName());
        }
        if (status.source() == ApiCredentialSource.SHELL_ENV) {
            return "Using %s with shell environment variable %s.".formatted(displayName(), status.credentialId());
        }
        String credentialId = status.credentialId() == null ? requiredEnvVar() : status.credentialId();
        return "Using %s with environment variable %s.".formatted(displayName(), credentialId);
    }

    protected final <T> T getJson(
            URI uri,
            Map<String, String> headers,
            Class<T> responseType,
            String invalidResponseMessage
    ) throws Exception {
        HttpExchangeResponse response = httpClient.get(uri, headers);
        requireSuccess(response);
        return httpClient.readJson(response.body(), responseType, invalidResponseMessage);
    }

    protected final HttpExchangeResponse postJson(URI uri, Map<String, String> headers, Object requestBody) throws Exception {
        HttpExchangeResponse response = httpClient.postJson(uri, headers, requestBody);
        requireSuccess(response);
        return response;
    }

    protected final <T> T postJson(
            URI uri,
            Map<String, String> headers,
            Object requestBody,
            Class<T> responseType,
            String invalidResponseMessage
    ) throws Exception {
        HttpExchangeResponse response = postJson(uri, headers, requestBody);
        return httpClient.readJson(response.body(), responseType, invalidResponseMessage);
    }

    protected final <T> Optional<T> tryJson(byte[] body, Class<T> responseType) {
        return httpClient.tryReadJson(body, responseType);
    }

    protected TextToSpeechAudio audioBody(HttpExchangeResponse response, String fallbackFormat) {
        String contentType = response.firstHeader("content-type");
        return new TextToSpeechAudio(response.body(), contentType, fallbackFormat);
    }

    protected List<TextToSpeechCatalogItem> nonEmptyOrBundled(List<TextToSpeechCatalogItem> discovered, List<TextToSpeechCatalogItem> bundled) {
        return discovered == null || discovered.isEmpty() ? bundled : discovered;
    }

    private void requireSuccess(HttpExchangeResponse response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String detail = httpClient.safeErrorDetail(response);
        String suffix = StringUtils.isBlank(detail) ? "" : ": %s".formatted(detail);
        throw new IllegalStateException("%s TTS request failed with HTTP %d%s".formatted(displayName(), response.statusCode(), suffix));
    }
}
