/*
 * LogText.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;


/**
 * Makes values from outside safe for log messages, e.g. values read from the database table, the holder of a Kubernetes
 * Lease or an id passed to the administration API: control characters (e.g. line breaks that would forge log lines) are
 * replaced and the length is limited, since whoever writes the value controls it.
 *
 * @author patrick
 */
public final class LogText {
    // the maximum length of a value in a log message
    private static final int MAX_LENGTH = 256;


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final LogText INSTANCE = new LogText();
    }


    /**
     * Constructor
     */
    private LogText() {
        // NOP
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LogText getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Make a value safe for a log message.
     *
     * @param value the value
     * @return the text for the log, or null if the value is null
     */
    public String of(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(Math.min(value.length(), MAX_LENGTH) + 3);
        for (int i = 0; i < value.length() && i < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                text.append('?');
            } else {
                text.append(c);
            }
        }
        if (value.length() > MAX_LENGTH) {
            text.append("...");
        }
        return text.toString();
    }
}
