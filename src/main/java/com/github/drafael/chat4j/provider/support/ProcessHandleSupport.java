package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.util.SystemInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class ProcessHandleSupport {

    private ProcessHandleSupport() {
    }

    public static boolean isRunning(ProcessHandle process) {
        return process != null && process.isAlive() && !isLinuxZombie(process.pid());
    }

    private static boolean isLinuxZombie(long pid) {
        if (!SystemInfo.isLinux || pid <= 0) {
            return false;
        }
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"), StandardCharsets.UTF_8);
            int commandEnd = stat.lastIndexOf(')');
            int stateIndex = commandEnd + 2;
            return commandEnd >= 0 && stateIndex < stat.length() && stat.charAt(stateIndex) == 'Z';
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
