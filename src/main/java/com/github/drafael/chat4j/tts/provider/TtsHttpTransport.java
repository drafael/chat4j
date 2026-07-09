package com.github.drafael.chat4j.tts.provider;

@FunctionalInterface
public interface TtsHttpTransport {
    TtsHttpResponse send(TtsHttpRequest request) throws Exception;
}
