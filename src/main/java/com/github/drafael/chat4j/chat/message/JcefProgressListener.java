package com.github.drafael.chat4j.chat.message;

@FunctionalInterface
public interface JcefProgressListener {

    JcefProgressListener NO_OP = progress -> {
    };

    void onProgress(JcefInitializationProgress progress);
}
