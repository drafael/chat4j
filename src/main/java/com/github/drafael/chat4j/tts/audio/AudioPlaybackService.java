package com.github.drafael.chat4j.tts.audio;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public interface AudioPlaybackService {

    void play(TextToSpeechAudio audio) throws Exception;

    default void play(TextToSpeechAudio audio, BooleanSupplier isCancelled) throws Exception {
        if (!isCancelled.getAsBoolean()) {
            play(audio);
        }
    }

    void stop();

    default CompletableFuture<Void> stopAsync() {
        stop();
        return CompletableFuture.completedFuture(null);
    }
}
