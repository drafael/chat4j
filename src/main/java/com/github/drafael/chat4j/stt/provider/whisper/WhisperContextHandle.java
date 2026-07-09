package com.github.drafael.chat4j.stt.provider.whisper;

public interface WhisperContextHandle extends AutoCloseable {
    @Override
    void close() throws Exception;
}
