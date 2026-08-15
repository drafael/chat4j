package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderDef;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelSelectorPopupEdtTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Blocked dynamic capability discovery does not block the event dispatch thread")
    void preload_whenDynamicCapabilityDiscoveryBlocks_keepsEdtResponsive() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required for model selector behavior.");

        var requestStarted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestStarted.countDown();
            try {
                boolean released;
                try {
                    released = releaseResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                if (!released) {
                    exchange.sendResponseHeaders(504, -1);
                    return;
                }
                byte[] response = """
                        {"name":"models/gemini-2.5-pro","supportsImageInput":true,"supportsReasoning":true}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        String baseUrl = "http://localhost:%d".formatted(server.getAddress().getPort());
        var provider = new ProviderDef(
                "Google AI",
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                baseUrl,
                baseUrl,
                List.of("gemini-2.5-pro"),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                List::of
        );
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        when(providerRegistry.availableProviders()).thenReturn(List.of(provider));
        CredentialResolver credentialResolver = mock(CredentialResolver.class);
        when(credentialResolver.resolveApiKey(provider.envVar(), null)).thenReturn("test-key");
        var modelCacheService = new ProviderModelCacheService(
                new ProviderModelCache(StoragePaths.ofConfigHome(tempDir))
        );
        var owner = new AtomicReference<JDialog>();
        var popup = new AtomicReference<ModelSelectorPopup>();

        try {
            runOnEdt(() -> {
                owner.set(new JDialog());
                popup.set(new ModelSelectorPopup(
                        owner.get(),
                        modelCacheService,
                        ModelFavoritesService.createInMemory(),
                        providerRegistry,
                        (providerName, modelId) -> { },
                        (providers, scopeVersion) -> true,
                        () -> { },
                        () -> { },
                        credentialResolver
                ));
                popup.get().preload();
            });

            assertThat(requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
            var edtResponded = new CountDownLatch(1);
            SwingUtilities.invokeLater(edtResponded::countDown);

            assertThat(edtResponded.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseResponse.countDown();
            runOnEdt(() -> {
                if (popup.get() != null) {
                    popup.get().dispose();
                }
                if (owner.get() != null) {
                    owner.get().dispose();
                }
            });
            runOnEdt(() -> { });
            server.stop(0);
        }
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
