package com.github.drafael.chat4j.tts;

public interface AudioPlaybackService {

    void play(TextToSpeechAudio audio) throws Exception;

    void stop();
}
