/*
 * LogCapture.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;


/**
 * Captures the log events of a class in a test.
 *
 * @author patrick
 */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;


    /**
     * Constructor for LogCapture
     *
     * @param loggerClass the class whose logger is captured
     */
    public LogCapture(Class<?> loggerClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerClass);
        this.appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }


    /**
     * Get the captured messages of a level that contain a text.
     *
     * @param level the level
     * @param text the text
     * @return the formatted messages
     */
    public List<String> getMessages(Level level, String text) {
        List<String> result = new ArrayList<>();
        synchronized (appender) {
            for (ILoggingEvent event : appender.list) {
                if (event.getLevel().equals(level) && event.getFormattedMessage().contains(text)) {
                    result.add(event.getFormattedMessage());
                }
            }
        }
        return result;
    }


    /**
     * Test if no captured event of a level or above has a stack trace: INFO, WARN and ERROR never log one.
     *
     * @param level the lowest level to check
     * @return true if no such event has a stack trace
     */
    public boolean hasNoStackTraceFrom(Level level) {
        synchronized (appender) {
            for (ILoggingEvent event : appender.list) {
                if (event.getLevel().isGreaterOrEqual(level) && event.getThrowableProxy() != null) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
