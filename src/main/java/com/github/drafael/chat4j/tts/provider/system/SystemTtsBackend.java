package com.github.drafael.chat4j.tts.provider.system;

import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import java.util.List;

interface SystemTtsBackend {

    boolean available();

    String unavailableMessage();

    String availableMessage();

    String defaultResponseFormat();

    List<TextToSpeechCatalogItem> fetchVoices() throws Exception;

    TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception;
}
