package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.math.BigInteger;
import java.net.URI;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class TogetherModelCatalogClient implements ModelCatalogClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final JsonCodec JSON_CODEC = JsonCodec.standard();
    private static final HttpTransport DEFAULT_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(3), RedirectPolicy.NEVER);

    private final HttpTransport transport;

    public TogetherModelCatalogClient() {
        this(DEFAULT_TRANSPORT);
    }

    TogetherModelCatalogClient(@NonNull HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        if (Thread.currentThread().isInterrupted() || StringUtils.isBlank(runtime.apiKey())) {
            return emptyList();
        }

        try {
            var request = new HttpExchangeRequest(
                    "GET",
                    URI.create(modelsEndpoint(runtime.baseUrl())),
                    Map.of("Authorization", "Bearer %s".formatted(runtime.apiKey()), "Accept", "application/json"),
                    HttpBody.empty(),
                    REQUEST_TIMEOUT,
                    0
            );
            HttpExchangeResponse response = transport.send(request, Thread.currentThread()::isInterrupted);
            if (!response.successful()) {
                log.debug("Together model listing failed with HTTP {}", response.statusCode());
                return emptyList();
            }

            Model[] root = JSON_CODEC.read(response.body(), Model[].class);
            List<String> modelIds = Arrays.stream(root)
                    .filter(TogetherModelCatalogClient::validChatEntry)
                    .map(model -> (String) model.id())
                    .toList();
            List<String> selectable = ModelOrdering.sanitizeAndSortByProvider("Together", modelIds);
            if (root.length == 0) {
                log.debug("Together model listing returned an empty catalog");
            } else if (selectable.isEmpty()) {
                log.debug("Together model listing contained no selectable serverless chat models");
            }
            return selectable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Together model listing interrupted");
            return emptyList();
        } catch (Exception e) {
            String diagnostic = ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey());
            log.debug("Together model listing failed: {}", diagnostic);
            return emptyList();
        }
    }

    private static boolean validChatEntry(Model model) {
        return model != null
                && model.id() instanceof String id
                && StringUtils.isNotBlank(id)
                && "model".equals(model.object())
                && integral(model.created())
                && "chat".equals(model.type());
    }

    private static boolean integral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger;
    }

    private static String modelsEndpoint(String baseUrl) {
        return baseUrl.endsWith("/") ? "%smodels".formatted(baseUrl) : "%s/models".formatted(baseUrl);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Model(Object id, Object object, Object created, Object type) {
    }
}
