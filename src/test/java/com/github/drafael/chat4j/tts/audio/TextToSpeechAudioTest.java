package com.github.drafael.chat4j.tts.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechAudioTest {

    @Test
    @DisplayName("Content type takes precedence over fallback format")
    void constructor_contentTypeAndFallbackDisagree_usesContentTypeFormat() {
        var subject = new TextToSpeechAudio(new byte[]{1, 2, 3}, "audio/mpeg", "wav");

        assertThat(subject.format()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("Groq WAV responses with unknown chunk sizes are normalized for Java Sound")
    void normalizedWavBytes_unknownRiffAndDataSizes_writesConcreteSizes() {
        byte[] wav = new byte[]{
                'R', 'I', 'F', 'F', -1, -1, -1, -1, 'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0, -64, 93, 0, 0, -128, -69, 0, 0, 2, 0, 16, 0,
                'd', 'a', 't', 'a', -1, -1, -1, -1, 1, 2, 3, 4
        };

        byte[] normalized = JavaSoundAudioPlaybackService.normalizedWavBytes(wav);

        assertThat(littleEndianInt(normalized, 4)).isEqualTo(normalized.length - 8);
        assertThat(littleEndianInt(normalized, 40)).isEqualTo(4);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
