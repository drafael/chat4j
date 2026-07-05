package com.github.drafael.chat4j.stt.model;

public interface SpeechToTextModelDownloader {

    void download(SpeechToTextModelDescriptor descriptor) throws Exception;
}
