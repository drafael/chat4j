package com.github.drafael.chat4j.stt.provider.whisper;

import io.github.freshsupasulley.whisperjni.WhisperContext;
import io.github.freshsupasulley.whisperjni.WhisperFullParams;
import io.github.freshsupasulley.whisperjni.WhisperJNI;
import java.nio.file.Path;

public class WhisperJniBindingFactory implements WhisperBindingFactory {
    @Override
    public WhisperBinding create() {
        return new JniBinding(new WhisperJNI());
    }

    private record JniBinding(WhisperJNI whisper) implements WhisperBinding {
        @Override
        public void loadLibrary() throws Exception {
            whisper.loadLibrary();
        }

        @Override
        public WhisperContextHandle init(Path modelFile) throws Exception {
            WhisperContext context = whisper.init(modelFile);
            return context == null ? null : new JniContextHandle(context);
        }

        @Override
        public int full(WhisperContextHandle context, WhisperFullParams params, float[] samples, int numSamples) {
            return whisper.full(((JniContextHandle) context).context(), params, samples, numSamples);
        }

        @Override
        public int fullNSegments(WhisperContextHandle context) {
            return whisper.fullNSegments(((JniContextHandle) context).context());
        }

        @Override
        public String fullGetSegmentText(WhisperContextHandle context, int index) {
            return whisper.fullGetSegmentText(((JniContextHandle) context).context(), index);
        }
    }

    private record JniContextHandle(WhisperContext context) implements WhisperContextHandle {
        @Override
        public void close() {
            context.close();
        }
    }
}
