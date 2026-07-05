package com.github.drafael.chat4j.stt.audio;

@FunctionalInterface
public interface AudioLevelListener {

    void onLevel(double rms, double peak);
}
