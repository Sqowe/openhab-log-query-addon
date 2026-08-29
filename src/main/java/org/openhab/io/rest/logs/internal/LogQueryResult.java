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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Response wrapper DTO for log query results.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogQueryResult {

    private final String file;
    private final @Nullable String pattern;
    private final int totalEntries;
    private final List<LogEntry> entries;
    private final @Nullable String hint;

    private LogQueryResult(String file, @Nullable String pattern, int totalEntries, List<LogEntry> entries,
            @Nullable String hint) {
        this.file = file;
        this.pattern = pattern;
        this.totalEntries = totalEntries;
        this.entries = entries;
        this.hint = hint;
    }

    public static LogQueryResult forTail(String file, List<LogEntry> entries) {
        return new LogQueryResult(file, null, entries.size(), entries, null);
    }

    public static LogQueryResult forSearch(String file, String pattern, List<LogEntry> entries) {
        return new LogQueryResult(file, pattern, entries.size(), entries, null);
    }

    /**
     * Result of a search that matched nothing, carrying a hint on how to broaden the query.
     * The hint is only present when there are no entries, so Gson omits the field otherwise.
     */
    public static LogQueryResult forEmptySearch(String file, String pattern, String hint) {
        return new LogQueryResult(file, pattern, 0, List.of(), hint);
    }

    public String getFile() {
        return file;
    }

    public @Nullable String getPattern() {
        return pattern;
    }

    public int getTotalEntries() {
        return totalEntries;
    }

    public List<LogEntry> getEntries() {
        return entries;
    }

    public @Nullable String getHint() {
        return hint;
    }
}
