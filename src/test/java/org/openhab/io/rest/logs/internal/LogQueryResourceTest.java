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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LogQueryResource}.
 * Tests HTTP status code mapping, parameter validation, and error handling.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
class LogQueryResourceTest {

    @Mock
    private LogFileService logFileService;

    private LogQueryResource resource;

    @BeforeEach
    void setUp() {
        resource = new LogQueryResource(logFileService);
    }

    // ==============================
    // GET /rest/logs — Tail endpoint
    // ==============================

    @Test
    void testGetLogEntries_success() throws Exception {
        LogQueryResult result = LogQueryResult.forTail("openhab.log", List.of(
                new LogEntry("2026-06-09 12:00:00.000", "INFO", "test", null, "hello", 1)));
        when(logFileService.tail(eq("openhab.log"), eq(100), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(result);

        Response response = resource.getLogEntries("openhab.log", 100, null, null, null, null, null);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    void testGetLogEntries_invalidLinesZero_returns400() {
        Response response = resource.getLogEntries("openhab.log", 0, null, null, null, null, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("lines must be at least 1"));
    }

    @Test
    void testGetLogEntries_invalidLinesNegative_returns400() {
        Response response = resource.getLogEntries("openhab.log", -5, null, null, null, null, null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void testGetLogEntries_fileNotFound_returns404() throws Exception {
        when(logFileService.tail(eq("missing.log"), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new LogFileNotFoundException("Log file not found: missing.log"));

        Response response = resource.getLogEntries("missing.log", 10, null, null, null, null, null);

        assertEquals(404, response.getStatus());
        assertTrue(response.getEntity().toString().contains("not found"));
    }

    @Test
    void testGetLogEntries_invalidTimestamp_returns400() throws Exception {
        when(logFileService.tail(eq("openhab.log"), anyInt(), isNull(), isNull(), eq("bad-date"), isNull()))
                .thenThrow(new IllegalArgumentException("Invalid timestamp format: bad-date"));

        Response response = resource.getLogEntries("openhab.log", 10, null, null, "bad-date", null, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Invalid timestamp"));
    }

    @Test
    void testGetLogEntries_ioError_returns500() throws Exception {
        when(logFileService.tail(eq("openhab.log"), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new IOException("Permission denied"));

        Response response = resource.getLogEntries("openhab.log", 10, null, null, null, null, null);

        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("I/O error"));
    }

    @Test
    void testGetLogEntries_pathTraversal_returns404() throws Exception {
        when(logFileService.tail(eq("../../etc/passwd"), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new LogFileNotFoundException("Invalid file name: path separators and '..' not allowed"));

        Response response = resource.getLogEntries("../../etc/passwd", 10, null, null, null, null, null);

        assertEquals(404, response.getStatus());
    }

    @Test
    void testGetLogEntries_withFilters() throws Exception {
        LogQueryResult result = LogQueryResult.forTail("openhab.log", List.of());
        when(logFileService.tail(eq("openhab.log"), eq(50), eq("WARN"), eq("mqtt"),
                eq("2026-06-09T10:00:00"), eq("2026-06-09T12:00:00")))
                .thenReturn(result);

        Response response = resource.getLogEntries("openhab.log", 50, "WARN", "mqtt",
                "2026-06-09T10:00:00", "2026-06-09T12:00:00", null);

        assertEquals(200, response.getStatus());
    }

    // =================================
    // GET /rest/logs/search — Search endpoint
    // =================================

    @Test
    void testSearchLogs_success() throws Exception {
        LogQueryResult result = LogQueryResult.forSearch("openhab.log", "timeout", List.of(
                new LogEntry("2026-06-09 12:00:00.000", "WARN", "test", null, "Connection timeout", 42)));
        when(logFileService.search(eq("openhab.log"), eq("timeout"), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenReturn(result);

        Response response = resource.searchLogs("openhab.log", "timeout", null, null, null, null, 200, false, null);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    void testSearchLogs_missingPattern_returns400() {
        Response response = resource.searchLogs("openhab.log", null, null, null, null, null, 200, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("pattern parameter is required"));
    }

    @Test
    void testSearchLogs_blankPattern_returns400() {
        Response response = resource.searchLogs("openhab.log", "   ", null, null, null, null, 200, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("pattern parameter is required"));
    }

    @Test
    void testSearchLogs_invalidLimit_returns400() {
        Response response = resource.searchLogs("openhab.log", "test", null, null, null, null, 0, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("limit must be at least 1"));
    }

    @Test
    void testSearchLogs_negativeLimit_returns400() {
        Response response = resource.searchLogs("openhab.log", "test", null, null, null, null, -1, false, null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void testSearchLogs_invalidRegex_returns400() throws Exception {
        when(logFileService.search(eq("openhab.log"), eq("[bad"), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenThrow(new IllegalArgumentException("Invalid regex pattern: Unclosed character class"));

        Response response = resource.searchLogs("openhab.log", "[bad", null, null, null, null, 200, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Invalid regex"));
    }

    @Test
    void testSearchLogs_patternTooLong_returns400() throws Exception {
        String longPattern = "a".repeat(501);
        when(logFileService.search(eq("openhab.log"), eq(longPattern), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenThrow(new IllegalArgumentException("Pattern too long (max 500 characters)"));

        Response response = resource.searchLogs("openhab.log", longPattern, null, null, null, null, 200, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Pattern too long"));
    }

    @Test
    void testSearchLogs_regexTimeout_returns400() throws Exception {
        when(logFileService.search(eq("openhab.log"), eq("(a+)+$"), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenThrow(new IllegalArgumentException("Regex search timed out — pattern may be too complex"));

        Response response = resource.searchLogs("openhab.log", "(a+)+$", null, null, null, null, 200, false, null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("timed out"));
    }

    @Test
    void testSearchLogs_fileNotFound_returns404() throws Exception {
        when(logFileService.search(eq("missing.log"), eq("test"), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenThrow(new LogFileNotFoundException("Log file not found: missing.log"));

        Response response = resource.searchLogs("missing.log", "test", null, null, null, null, 200, false, null);

        assertEquals(404, response.getStatus());
    }

    @Test
    void testSearchLogs_ioError_returns500() throws Exception {
        when(logFileService.search(eq("openhab.log"), eq("test"), isNull(), isNull(),
                isNull(), isNull(), eq(200), eq(false)))
                .thenThrow(new IOException("Disk read error"));

        Response response = resource.searchLogs("openhab.log", "test", null, null, null, null, 200, false, null);

        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("I/O error"));
    }

    @Test
    void testSearchLogs_withAllFilters() throws Exception {
        LogQueryResult result = LogQueryResult.forSearch("events.log", "item.*changed", List.of());
        when(logFileService.search(eq("events.log"), eq("item.*changed"), eq("INFO"), eq("openhab"),
                eq("2026-06-09T00:00:00"), eq("2026-06-09T23:59:59"), eq(50), eq(true)))
                .thenReturn(result);

        Response response = resource.searchLogs("events.log", "item.*changed", "INFO", "openhab",
                "2026-06-09T00:00:00", "2026-06-09T23:59:59", 50, true, null);

        assertEquals(200, response.getStatus());
    }

    // ===================================
    // GET /rest/logs/files — List endpoint
    // ===================================

    @Test
    void testListLogFiles_success() {
        LogFilesResult result = new LogFilesResult("/var/log/openhab", List.of(
                new LogFileInfo("openhab.log", 1234567, "2026-06-09T12:00:00+02:00"),
                new LogFileInfo("events.log", 456789, "2026-06-09T11:55:00+02:00")));
        when(logFileService.listFiles()).thenReturn(result);

        Response response = resource.listLogFiles(null);

        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    void testListLogFiles_emptyDirectory() {
        LogFilesResult result = new LogFilesResult("/var/log/openhab", List.of());
        when(logFileService.listFiles()).thenReturn(result);

        Response response = resource.listLogFiles(null);

        assertEquals(200, response.getStatus());
    }

    // ===================================
    // Error message escaping
    // ===================================

    @Test
    void testErrorMessageWithSpecialChars_escaped() throws Exception {
        when(logFileService.tail(eq("test.log"), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new LogFileNotFoundException("File \"test\nlog\" not found"));

        Response response = resource.getLogEntries("test.log", 10, null, null, null, null, null);

        assertEquals(404, response.getStatus());
        String body = response.getEntity().toString();
        // Should not contain raw quotes or newlines that would break JSON
        assertFalse(body.contains("\n"));
        assertTrue(body.contains("\\n"));
        assertTrue(body.contains("\\\""));
    }
}
