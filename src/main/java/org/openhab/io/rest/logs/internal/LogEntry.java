/**
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.io.rest.logs.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Data Transfer Object representing a single parsed log entry.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogEntry {

    private static final int MAX_MESSAGE_LENGTH = 100000;

    private final String timestamp;
    private final String level;
    private final String logger;
    private final @Nullable String thread;
    private final StringBuilder messageBuilder;
    private final @Nullable Integer lineNumber;

    public LogEntry(String timestamp, String level, String logger, @Nullable String thread, String message,
            @Nullable Integer lineNumber) {
        this.timestamp = timestamp;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.messageBuilder = new StringBuilder(message);
        this.lineNumber = lineNumber;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getLevel() {
        return level;
    }

    public String getLogger() {
        return logger;
    }

    public @Nullable String getThread() {
        return thread;
    }

    public String getMessage() {
        return messageBuilder.toString();
    }

    public void appendMessage(String continuation) {
        if (messageBuilder.length() < MAX_MESSAGE_LENGTH) {
            messageBuilder.append('\n').append(continuation);
            // Truncate if we exceed the limit
            if (messageBuilder.length() > MAX_MESSAGE_LENGTH) {
                messageBuilder.setLength(MAX_MESSAGE_LENGTH);
            }
        }
    }

    public @Nullable Integer getLineNumber() {
        return lineNumber;
    }
}
