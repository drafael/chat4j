package com.github.drafael.chat4j.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;
import com.github.drafael.chat4j.App;
import com.github.drafael.chat4j.startup.NativeStderrNoiseFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingBootstrapFixtureMain {

    private LoggingBootstrapFixtureMain() {
    }

    public static void main(String[] args) throws ClassNotFoundException {
        Class.forName(App.class.getName());
        Path fallbackLog = Path.of(
                System.getProperty("user.home"),
                ".local",
                "state",
                "chat4j",
                "logs",
                "chat4j.log"
        );
        LoggingBootstrap.initialize();
        Class.forName(NativeStderrNoiseFilter.class.getName());
        System.out.println("FALLBACK_EXISTS_AFTER_STARTUP_LOGGING=" + Files.exists(fallbackLog));

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        FileAppender<?> fileAppender = (FileAppender<?>) loggerContext
                .getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender("FILE");
        System.out.println("APPENDER_FILE=" + fileAppender.getFile());
    }
}
