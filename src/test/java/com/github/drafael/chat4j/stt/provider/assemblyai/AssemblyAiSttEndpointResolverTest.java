package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssemblyAiSttEndpointResolverTest {

    @Test
    @DisplayName("AssemblyAI endpoint resolver exposes upload and transcript endpoints")
    void resolve_whenDefaultEndpointUsed_returnsOfficialPaths() throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(AssemblyAiSttEndpointResolver.DEFAULT_BASE_URL);

        assertThat(endpoint.baseUri()).isEqualTo(URI.create("https://api.assemblyai.com"));
        assertThat(endpoint.uploadUri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/upload"));
        assertThat(endpoint.transcriptionUri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/transcript"));
        assertThat(endpoint.transcriptUri("abc 123/def")).isEqualTo(URI.create("https://api.assemblyai.com/v2/transcript/abc%20123%2Fdef"));
    }

    @Test
    @DisplayName("AssemblyAI endpoint resolver trims trailing slashes")
    void resolve_whenBaseUrlHasTrailingSlashes_normalizesBase() throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve("https://api.assemblyai.com///");

        assertThat(endpoint.baseUri()).isEqualTo(URI.create("https://api.assemblyai.com"));
    }

    @Test
    @DisplayName("AssemblyAI endpoint resolver accepts official EU host")
    void resolve_whenEuHostUsed_acceptsOfficialHost() throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(AssemblyAiSttEndpointResolver.EU_BASE_URL);

        assertThat(endpoint.transcriptionUri()).isEqualTo(URI.create("https://api.eu.assemblyai.com/v2/transcript"));
    }

    @Test
    @DisplayName("AssemblyAI endpoint resolver rejects unsafe or untrusted hosts")
    void resolve_whenEndpointInvalid_rejectsEndpoint() {
        assertThatThrownBy(() -> AssemblyAiSttEndpointResolver.resolve("http://api.assemblyai.com"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("absolute https");
        assertThatThrownBy(() -> AssemblyAiSttEndpointResolver.resolve("/v2/transcript"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("absolute https");
        assertThatThrownBy(() -> AssemblyAiSttEndpointResolver.resolve("https://example.com"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("official AssemblyAI");
    }

    @Test
    @DisplayName("AssemblyAI endpoint resolver rejects unsafe transcript IDs")
    void transcriptUri_whenTranscriptIdUnsafe_rejectsId() throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(AssemblyAiSttEndpointResolver.DEFAULT_BASE_URL);

        assertThatThrownBy(() -> endpoint.transcriptUri("bad\nid"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("transcript id");
    }
}
