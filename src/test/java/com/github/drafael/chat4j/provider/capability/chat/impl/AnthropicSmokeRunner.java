package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.models.impl.AnthropicModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.modules.AnthropicModule;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

final class AnthropicSmokeRunner {

    private static final String ENABLED_PROPERTY = "chat4j.smoke.anthropic";
    private static final String BASE_URL_PROPERTY = "chat4j.smoke.anthropic.baseUrl";
    private static final String MODEL_PROPERTY = "chat4j.smoke.anthropic.model";
    private static final String REASONING_MODEL_PROPERTY = "chat4j.smoke.anthropic.reasoningModel";
    private static final String VISION_MODEL_PROPERTY = "chat4j.smoke.anthropic.visionModel";
    private static final String WEB_SEARCH_MODEL_PROPERTY = "chat4j.smoke.anthropic.webSearchModel";
    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final Consumer<String> IGNORE_TOKEN = token -> {
    };

    private final SmokeConfig config;
    private final AnthropicChatCompletionClient chatClient = new AnthropicChatCompletionClient();
    private final AnthropicModelCatalogClient modelCatalogClient = new AnthropicModelCatalogClient();

    AnthropicSmokeRunner(SmokeConfig config) {
        this.config = config;
    }

    SmokeResult runAll() throws Exception {
        List<String> models = modelCatalogClient.fetchModels(runtime(""));
        require(!models.isEmpty(), "Anthropic model listing returned no models");
        ResolvedModels resolvedModels = resolveModels(models);

        List<String> details = new ArrayList<>();
        details.add(modelListingSummary(models, resolvedModels));
        details.add(runBasicStreaming(resolvedModels.model()));
        details.add(runSystemPromptStreaming(resolvedModels.model()));
        details.add(runReasoningStreaming(resolvedModels.reasoningModel()));
        details.add(runWebSearchStreaming(resolvedModels.webSearchModel()));
        details.add(runImageStreaming(resolvedModels.visionModel()));
        details.add(runCancellation(resolvedModels.model()));
        return new SmokeResult(details);
    }

    private ResolvedModels resolveModels(List<String> availableModels) {
        String model = resolveModel(availableModels, config.model(), MODEL_PROPERTY);
        return new ResolvedModels(
                model,
                resolveModel(availableModels, StringUtils.defaultIfBlank(config.reasoningModel(), model), REASONING_MODEL_PROPERTY),
                resolveModel(availableModels, StringUtils.defaultIfBlank(config.visionModel(), model), VISION_MODEL_PROPERTY),
                resolveModel(availableModels, StringUtils.defaultIfBlank(config.webSearchModel(), model), WEB_SEARCH_MODEL_PROPERTY)
        );
    }

    private String resolveModel(List<String> availableModels, String requestedModel, String propertyName) {
        if (StringUtils.isBlank(requestedModel)) {
            return preferredModel(availableModels);
        }

        return availableModels.stream()
                .filter(model -> Strings.CS.equals(model, requestedModel))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Requested Anthropic smoke model from -D%s=%s was not returned by the Anthropic models API. Available models: %s"
                                .formatted(propertyName, requestedModel, availableModels)
                ));
    }

    private String preferredModel(List<String> availableModels) {
        return availableModels.stream()
                .min(Comparator.comparingInt(AnthropicSmokeRunner::modelPreferenceRank))
                .orElseThrow(() -> new IllegalStateException("Anthropic model listing returned no models"));
    }

    private static int modelPreferenceRank(String model) {
        String normalized = StringUtils.defaultString(model).toLowerCase();
        if (normalized.contains("sonnet-4")) {
            return 0;
        }
        if (normalized.contains("opus-4")) {
            return 1;
        }
        if (normalized.contains("haiku-4")) {
            return 2;
        }
        if (normalized.contains("sonnet")) {
            return 3;
        }
        if (normalized.contains("opus")) {
            return 4;
        }
        if (normalized.contains("haiku")) {
            return 5;
        }
        if (normalized.contains("claude")) {
            return 6;
        }
        return 7;
    }

    private String modelListingSummary(List<String> models, ResolvedModels resolvedModels) {
        return "model listing: %d model(s), selected model=%s, reasoning=%s, vision=%s, webSearch=%s"
                .formatted(
                        models.size(),
                        resolvedModels.model(),
                        resolvedModels.reasoningModel(),
                        resolvedModels.visionModel(),
                        resolvedModels.webSearchModel()
                );
    }

    private String runBasicStreaming(String model) throws Exception {
        String text = streamText(
                model,
                List.of(Message.user("Reply with exactly: chat4j anthropic smoke ok")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled()
        );
        requireContains(text, "chat4j", "basic streaming response did not contain the smoke marker");
        return "basic streaming: %d character(s)".formatted(text.length());
    }

    private String runSystemPromptStreaming(String model) throws Exception {
        String text = streamText(
                model,
                List.of(
                        Message.system("You are a smoke-test assistant. Keep answers short."),
                        Message.user("Reply with exactly: system smoke ok")
                ),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled()
        );
        requireContains(text, "system", "system prompt response did not contain the smoke marker");
        return "system prompt: %d character(s)".formatted(text.length());
    }

    private String runReasoningStreaming(String model) throws Exception {
        StringBuilder thinking = new StringBuilder();
        String text = streamText(
                model,
                List.of(Message.user("Think briefly, then answer with exactly: reasoning smoke ok")),
                ReasoningLevel.LOW,
                WebSearchRequestOptions.disabled(),
                IGNORE_TOKEN,
                thinking::append,
                new AtomicBoolean(false)
        );
        requireContains(text, "reasoning", "reasoning response did not contain the smoke marker");
        require(StringUtils.isNotBlank(thinking), "reasoning smoke produced no thinking tokens");
        return "reasoning: %d response character(s), %d thinking character(s)"
                .formatted(text.length(), thinking.length());
    }

    private String runWebSearchStreaming(String model) throws Exception {
        String text = streamText(
                model,
                List.of(Message.user("Use web search if needed. In one short sentence, say what chat4j anthropic web search smoke is.")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true, "anthropic-smoke")
        );
        require(StringUtils.isNotBlank(text), "web search smoke produced no streamed text");
        return "web search: %d character(s)".formatted(text.length());
    }

    private String runImageStreaming(String model) throws Exception {
        Path imagePath = Files.createTempFile("chat4j-anthropic-smoke-", ".png");
        try {
            writeSmokeImage(imagePath);
            List<ContentPart> parts = List.of(
                    new TextPart("What is the dominant color in this image? Reply in three words or fewer."),
                    new ImagePart(
                            new AttachmentRef(
                                    UUID.randomUUID(),
                                    imagePath.toString(),
                                    "chat4j-anthropic-smoke.png",
                                    "image/png",
                                    Files.size(imagePath),
                                    ""
                            ),
                            16,
                            16
                    )
            );
            String text = streamText(
                    model,
                    List.of(Message.user(parts)),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled()
            );
            require(StringUtils.isNotBlank(text), "image smoke produced no streamed text");
            return "image input: %d character(s)".formatted(text.length());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    private String runCancellation(String model) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicBoolean cleared = new AtomicBoolean(false);
        StringBuilder text = new StringBuilder();

        chatClient.streamCompletion(
                runtime(model),
                List.of(Message.user("Write a long ten paragraph story for cancellation smoke testing.")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                token -> {
                    text.append(token);
                    cancelled.set(true);
                },
                IGNORE_TOKEN,
                cancelled::get,
                stream -> registered.set(true),
                () -> cleared.set(true)
        );

        require(registered.get(), "cancellation smoke did not register the active stream");
        require(cleared.get(), "cancellation smoke did not clear the active stream");
        require(StringUtils.isNotBlank(text), "cancellation smoke produced no token before cancellation");
        return "cancellation: %d character(s) before stop".formatted(text.length());
    }

    private String streamText(
            String model,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions
    ) throws Exception {
        return streamText(
                model,
                history,
                reasoningLevel,
                webSearchOptions,
                IGNORE_TOKEN,
                IGNORE_TOKEN,
                new AtomicBoolean(false)
        );
    }

    private String streamText(
            String model,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            AtomicBoolean cancelled
    ) throws Exception {
        StringBuilder text = new StringBuilder();
        AtomicBoolean registered = new AtomicBoolean(false);
        AtomicBoolean cleared = new AtomicBoolean(false);

        chatClient.streamCompletion(
                runtime(model),
                history,
                reasoningLevel,
                webSearchOptions,
                token -> {
                    text.append(token);
                    onToken.accept(token);
                },
                onThinkingToken,
                cancelled::get,
                stream -> registered.set(true),
                () -> cleared.set(true)
        );

        require(registered.get(), "Anthropic stream was not registered for model %s".formatted(model));
        require(cleared.get(), "Anthropic stream was not cleared for model %s".formatted(model));
        require(StringUtils.isNotBlank(text), "Anthropic stream produced no text for model %s".formatted(model));
        return text.toString();
    }

    private ProviderRuntime runtime(String model) {
        AnthropicModule module = new AnthropicModule("Anthropic", API_KEY_ENV, config.baseUrl());
        return new ProviderRuntime(
                module.descriptor(),
                API_KEY_ENV,
                module.descriptor().normalizeBaseUrl(config.baseUrl()),
                config.apiKey(),
                model,
                emptyList()
        );
    }

    private static void writeSmokeImage(Path path) throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void requireContains(String actual, String expected, String message) {
        require(StringUtils.containsIgnoreCase(actual, expected), "%s. Actual response: %s".formatted(message, actual));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static SmokeConfig configFromEnvironment() {
        String apiKey = System.getenv(API_KEY_ENV);
        return new SmokeConfig(
                apiKey,
                systemProperty(BASE_URL_PROPERTY, DEFAULT_BASE_URL),
                System.getProperty(MODEL_PROPERTY),
                System.getProperty(REASONING_MODEL_PROPERTY),
                System.getProperty(VISION_MODEL_PROPERTY),
                System.getProperty(WEB_SEARCH_MODEL_PROPERTY)
        );
    }

    static String activationHelp() {
        return "Run with -D%s=true and %s set. Optional overrides: -D%s=<base-url> -D%s=<model> -D%s=<model> -D%s=<model> -D%s=<model>"
                .formatted(
                        ENABLED_PROPERTY,
                        API_KEY_ENV,
                        BASE_URL_PROPERTY,
                        MODEL_PROPERTY,
                        REASONING_MODEL_PROPERTY,
                        VISION_MODEL_PROPERTY,
                        WEB_SEARCH_MODEL_PROPERTY
                );
    }

    private static String systemProperty(String name, String fallback) {
        return StringUtils.defaultIfBlank(System.getProperty(name), fallback);
    }

    record SmokeConfig(
            String apiKey,
            String baseUrl,
            String model,
            String reasoningModel,
            String visionModel,
            String webSearchModel
    ) {
        SmokeConfig {
            baseUrl = StringUtils.defaultIfBlank(baseUrl, DEFAULT_BASE_URL);
            model = StringUtils.trimToEmpty(model);
            reasoningModel = StringUtils.trimToEmpty(reasoningModel);
            visionModel = StringUtils.trimToEmpty(visionModel);
            webSearchModel = StringUtils.trimToEmpty(webSearchModel);
        }

        @Override
        public String toString() {
            return "SmokeConfig[apiKey=<masked>, baseUrl=%s, model=%s, reasoningModel=%s, visionModel=%s, webSearchModel=%s]"
                    .formatted(
                            baseUrl,
                            displayModel(model),
                            displayModel(reasoningModel),
                            displayModel(visionModel),
                            displayModel(webSearchModel)
                    );
        }

        private static String displayModel(String model) {
            return StringUtils.defaultIfBlank(model, "<auto>");
        }
    }

    private record ResolvedModels(
            String model,
            String reasoningModel,
            String visionModel,
            String webSearchModel
    ) {
    }

    record SmokeResult(List<String> details) {
        SmokeResult {
            details = details == null ? emptyList() : List.copyOf(details);
        }

        String summary() {
            return String.join(System.lineSeparator(), details);
        }
    }
}
