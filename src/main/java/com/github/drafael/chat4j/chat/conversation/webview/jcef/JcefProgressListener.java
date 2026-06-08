package com.github.drafael.chat4j.chat.conversation.webview.jcef;

@FunctionalInterface
public interface JcefProgressListener {

    JcefProgressListener NO_OP = progress -> {
    };

    void onProgress(JcefInitializationProgress progress);
}
