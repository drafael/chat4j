package com.github.drafael.chat4j.stt.provider.whisper;

import io.github.freshsupasulley.whisperjni.WhisperFullParams;
import java.nio.file.Path;

public interface WhisperBinding extends AutoCloseable {
    void loadLibrary() throws Exception;

    WhisperContextHandle init(Path modelFile) throws Exception;

    int full(WhisperContextHandle context, WhisperFullParams params, float[] samples, int numSamples);

    int fullNSegments(WhisperContextHandle context);

    String fullGetSegmentText(WhisperContextHandle context, int index);

    @Override
    default void close() {
    }
}
