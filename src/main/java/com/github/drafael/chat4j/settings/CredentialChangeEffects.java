package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.support.CredentialTokenIds;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public final class CredentialChangeEffects {

    private static final Map<String, CredentialChangeEffect> EFFECTS = Map.ofEntries(
            Map.entry("ANTHROPIC_API_KEY", CredentialChangeEffect.chat(List.of("Anthropic"))),
            Map.entry("OPENAI_API_KEY", CredentialChangeEffect.chat(List.of("OpenAI"))),
            Map.entry("OPENROUTER_API_KEY", CredentialChangeEffect.chat(List.of("OpenRouter"))),
            Map.entry("GROQ_API_KEY", new CredentialChangeEffect(List.of("Groq"), List.of("groq"), List.of("groq"))),
            Map.entry("ELEVENLABS_API_KEY", new CredentialChangeEffect(emptyList(), List.of("elevenlabs"), List.of("elevenlabs"))),
            Map.entry("DEEPGRAM_API_KEY", new CredentialChangeEffect(emptyList(), List.of("deepgram"), List.of("deepgram"))),
            Map.entry("ASSEMBLYAI_API_KEY", new CredentialChangeEffect(emptyList(), List.of("assemblyai"), emptyList())),
            Map.entry("DEEPSEEK_API_KEY", CredentialChangeEffect.chat(List.of("DeepSeek"))),
            Map.entry("MISTRAL_API_KEY", CredentialChangeEffect.chat(List.of("Mistral"))),
            Map.entry("XAI_API_KEY", CredentialChangeEffect.chat(List.of("xAI"))),
            Map.entry("PERPLEXITY_API_KEY", CredentialChangeEffect.none()),
            Map.entry("GEMINI_API_KEY", CredentialChangeEffect.chat(List.of("Google AI"))),
            Map.entry("GOOGLEAI_API_KEY", CredentialChangeEffect.chat(List.of("Google AI")))
    );

    private CredentialChangeEffects() {
    }

    public static CredentialChangeEffect forTokenId(String tokenId) {
        return EFFECTS.getOrDefault(tokenId, CredentialChangeEffect.none());
    }

    public static boolean allSupportedTokenIdsCovered() {
        return CredentialTokenIds.supportedProviderEnvVars().stream().allMatch(EFFECTS::containsKey);
    }

    public record CredentialChangeEffect(
            List<String> chatProviders,
            List<String> speechToTextProviderIds,
            List<String> textToSpeechProviderIds
    ) {
        static CredentialChangeEffect chat(List<String> chatProviders) {
            return new CredentialChangeEffect(chatProviders, emptyList(), emptyList());
        }

        static CredentialChangeEffect none() {
            return new CredentialChangeEffect(emptyList(), emptyList(), emptyList());
        }

        public boolean affectsChatProvider(String providerName) {
            return chatProviders.stream().anyMatch(provider -> StringUtils.equals(provider, providerName));
        }
    }
}
