package com.github.drafael.chat4j.tts.provider.system;

import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import java.util.List;

import static java.util.Collections.emptyList;

class UnavailableSystemTtsBackend implements SystemTtsBackend {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableMessage() {
        return "System Text to Speech is not available on this computer.";
    }

    @Override
    public String availableMessage() {
        return "Uses your operating system text-to-speech engine.";
    }

    @Override
    public String defaultResponseFormat() {
        return "wav";
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() {
        return emptyList();
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
        throw new IllegalStateException(unavailableMessage());
    }
}
