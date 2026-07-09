package com.github.drafael.chat4j.tts.audio;

public interface AudioPlaybackService {

    void play(TextToSpeechAudio audio) throws Exception;

    void stop();
}
