package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.stt.provider.whisper.WhisperJniEngine;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;

final class SpeechToTextTestProviders {

    private SpeechToTextTestProviders() {
    }

    static SpeechToTextProviderRegistry createDefault() {
        return SpeechToTextProviderRegistry.createDefault(
                new WhisperJniEngine(WhisperNativeRuntime.shared())
        );
    }
}
