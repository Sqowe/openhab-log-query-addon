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
 * <p>Note: openHAB uses Gson for JSON serialization which serializes fields directly.
 * Field names must match the desired JSON property names.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogEntry {

    private static final transient int MAX_MESSAGE_LENGTH = 100000;

    private final String timestamp;
    private final String level;
    private final String logger;
    private final @Nullable String thread;
    private String message;
    private final @Nullable Integer lineNumber;

    // Transient — not serialized to JSON, used only during parsing
    private transient int messageLength;

    public LogEntry(String timestamp, String level, String logger, @Nullable String thread, String message,
            @Nullable Integer lineNumber) {
        this.timestamp = timestamp;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.messageLength = message.length();
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
        return message;
    }

    public void appendMessage(String continuation) {
        if (messageLength < MAX_MESSAGE_LENGTH) {
            this.message = this.message + "\n" + continuation;
            this.messageLength = this.message.length();
            // Truncate if we exceed the limit
            if (this.messageLength > MAX_MESSAGE_LENGTH) {
                this.message = this.message.substring(0, MAX_MESSAGE_LENGTH);
                this.messageLength = MAX_MESSAGE_LENGTH;
            }
        }
    }

    public @Nullable Integer getLineNumber() {
        return lineNumber;
    }
}
