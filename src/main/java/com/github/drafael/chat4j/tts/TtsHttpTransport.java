package com.github.drafael.chat4j.tts;

@FunctionalInterface
public interface TtsHttpTransport {
    TtsHttpResponse send(TtsHttpRequest request) throws Exception;
}
