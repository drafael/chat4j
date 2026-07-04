package com.github.drafael.chat4j.tts;

import java.util.List;

interface SystemTtsBackend {

    boolean available();

    String unavailableMessage();

    String availableMessage();

    String defaultResponseFormat();

    List<TextToSpeechCatalogItem> fetchVoices() throws Exception;

    TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception;
}
