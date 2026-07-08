package com.github.drafael.chat4j.stt.provider.sphinx4;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.SpeechResult;
import edu.cmu.sphinx.api.StreamSpeechRecognizer;
import java.io.InputStream;

public interface Sphinx4RecognizerAdapter {

    RecognizerSession create(Configuration configuration) throws Exception;

    interface RecognizerSession extends AutoCloseable {
        void startRecognition(InputStream inputStream) throws Exception;

        String nextHypothesis() throws Exception;

        @Override
        void close();
    }

    static Sphinx4RecognizerAdapter defaultAdapter() {
        return configuration -> new StreamRecognizerSession(new StreamSpeechRecognizer(configuration));
    }

    final class StreamRecognizerSession implements RecognizerSession {
        private final StreamSpeechRecognizer recognizer;

        private StreamRecognizerSession(StreamSpeechRecognizer recognizer) {
            this.recognizer = recognizer;
        }

        @Override
        public void startRecognition(InputStream inputStream) {
            recognizer.startRecognition(inputStream);
        }

        @Override
        public String nextHypothesis() {
            SpeechResult result = recognizer.getResult();
            return result == null ? null : result.getHypothesis();
        }

        @Override
        public void close() {
            try {
                recognizer.stopRecognition();
            } catch (Exception ignored) {
            }
        }
    }
}
