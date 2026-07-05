package com.github.drafael.chat4j.stt.error;

public class SpeechToTextException extends Exception {

    public SpeechToTextException(String message) {
        super(message);
    }

    public SpeechToTextException(String message, Throwable cause) {
        super(message, cause);
    }
}
