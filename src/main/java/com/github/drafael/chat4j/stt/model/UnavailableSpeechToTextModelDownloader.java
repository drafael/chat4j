package com.github.drafael.chat4j.stt.model;

public class UnavailableSpeechToTextModelDownloader implements SpeechToTextModelDownloader {

    @Override
    public void download(SpeechToTextModelDescriptor descriptor) {
        throw new UnsupportedOperationException("Local model downloads are not available yet.");
    }
}
