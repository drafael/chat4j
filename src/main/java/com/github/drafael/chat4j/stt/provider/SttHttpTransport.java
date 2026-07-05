package com.github.drafael.chat4j.stt.provider;

public interface SttHttpTransport {

    SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception;
}
