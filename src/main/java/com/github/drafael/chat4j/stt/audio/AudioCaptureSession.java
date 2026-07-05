package com.github.drafael.chat4j.stt.audio;

import java.util.concurrent.CompletableFuture;

public interface AudioCaptureSession {

    CompletableFuture<CapturedAudio> completion();

    void stop();

    void cancel();
}
