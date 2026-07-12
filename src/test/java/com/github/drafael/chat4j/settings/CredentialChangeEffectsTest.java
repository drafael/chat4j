package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialChangeEffectsTest {

    @Test
    @DisplayName("Every supported token id has an affected-service mapping")
    void allSupportedTokenIdsCovered_whenChecked_returnsTrue() {
        assertThat(CredentialChangeEffects.allSupportedTokenIdsCovered()).isTrue();
    }

    @Test
    @DisplayName("Shared Groq token maps to chat, STT, and TTS refresh targets")
    void forTokenId_whenGroq_returnsAllSharedTargets() {
        var effect = CredentialChangeEffects.forTokenId("GROQ_API_KEY");

        assertThat(effect.chatProviders()).containsExactly("Groq");
        assertThat(effect.speechToTextProviderIds()).containsExactly("groq");
        assertThat(effect.textToSpeechProviderIds()).containsExactly("groq");
    }

    @Test
    @DisplayName("Perplexity token does not invalidate fixed seed model cache")
    void forTokenId_whenPerplexity_returnsNoModelCacheTargets() {
        assertThat(CredentialChangeEffects.forTokenId("PERPLEXITY_API_KEY").chatProviders()).isEmpty();
    }

    @Test
    @DisplayName("Google AI alias maps to the same chat provider as the canonical token")
    void forTokenId_whenGoogleAlias_returnsGoogleAiTarget() {
        assertThat(CredentialChangeEffects.forTokenId("GOOGLEAI_API_KEY").chatProviders()).containsExactly("Google AI");
        assertThat(CredentialChangeEffects.forTokenId("GEMINI_API_KEY").chatProviders()).containsExactly("Google AI");
    }
}
