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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LogFileService}.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
class LogFileServiceTest {

    @TempDir
    Path tempDir;

    private LogFileService service;

    @BeforeEach
    void setUp() {
        service = new LogFileService();
        System.setProperty("openhab.logdir", tempDir.toString());
        service.activate(Map.of(
                "maxLines", 1000,
                "maxSearchResults", 1000,
                "allowedFiles", "openhab.log*,events.log*,test.log*",
                "regexTimeoutMs", 5000));
    }

    @AfterEach
    void tearDown() {
        service.deactivate();
        System.clearProperty("openhab.logdir");
    }

    // --- Log line parsing tests ---

    @Test
    void testParseStandardLogLine() {
        String line = "2026-06-09 12:34:56.789 [ERROR] [org.openhab.binding.zwave] - Node 5: Timeout";
        List<LogEntry> entries = service.parseLogLines(List.of(line), 0);

        assertEquals(1, entries.size());
        LogEntry entry = entries.get(0);
        assertEquals("2026-06-09 12:34:56.789", entry.getTimestamp());
        assertEquals("ERROR", entry.getLevel());
        assertEquals("org.openhab.binding.zwave", entry.getLogger());
        assertEquals("Node 5: Timeout", entry.getMessage());
        assertEquals(1, entry.getLineNumber());
    }

    @Test
    void testParseMultiLineEntry() {
        List<String> lines = List.of(
                "2026-06-09 12:34:56.789 [ERROR] [org.openhab.core] - Something failed",
                "    at org.openhab.core.Something.method(Something.java:42)",
                "    at org.openhab.core.Other.call(Other.java:10)",
                "2026-06-09 12:34:57.000 [INFO ] [org.openhab.core] - Recovery complete");

        List<LogEntry> entries = service.parseLogLines(lines, 0);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).getMessage().contains("Something failed"));
        assertTrue(entries.get(0).getMessage().contains("Something.java:42"));
        assertTrue(entries.get(0).getMessage().contains("Other.java:10"));
        assertEquals("Recovery complete", entries.get(1).getMessage());
    }

    @Test
    void testParseEmptyLines() {
        List<LogEntry> entries = service.parseLogLines(List.of(), 0);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testParseLevelWithTrailingSpace() {
        String line = "2026-06-09 12:34:56.789 [WARN ] [org.openhab.binding.mqtt] - Connection lost";
        List<LogEntry> entries = service.parseLogLines(List.of(line), 0);

        assertEquals(1, entries.size());
        assertEquals("WARN", entries.get(0).getLevel());
    }

    // --- Level filtering tests ---

    @Test
    void testLevelFilteringWarnIncludesError() {
        assertTrue(service.meetsMinimumLevel("ERROR", "WARN"));
        assertTrue(service.meetsMinimumLevel("WARN", "WARN"));
        assertFalse(service.meetsMinimumLevel("INFO", "WARN"));
        assertFalse(service.meetsMinimumLevel("DEBUG", "WARN"));
        assertFalse(service.meetsMinimumLevel("TRACE", "WARN"));
    }

    @Test
    void testLevelFilteringErrorOnly() {
        assertTrue(service.meetsMinimumLevel("ERROR", "ERROR"));
        assertFalse(service.meetsMinimumLevel("WARN", "ERROR"));
        assertFalse(service.meetsMinimumLevel("INFO", "ERROR"));
    }

    @Test
    void testLevelFilteringUnknownLevelPassesThrough() {
        assertTrue(service.meetsMinimumLevel("CUSTOM", "WARN"));
    }

    // --- File validation tests ---

    @Test
    void testPathTraversalRejected() {
        assertThrows(LogFileNotFoundException.class,
                () -> service.resolveAndValidate("../../etc/passwd"));
    }

    @Test
    void testPathSeparatorRejected() {
        assertThrows(LogFileNotFoundException.class,
                () -> service.resolveAndValidate("subdir/openhab.log"));
    }

    @Test
    void testBackslashRejected() {
        assertThrows(LogFileNotFoundException.class,
                () -> service.resolveAndValidate("subdir\\openhab.log"));
    }

    @Test
    void testDisallowedFileRejected() {
        assertThrows(LogFileNotFoundException.class,
                () -> service.resolveAndValidate("secret.txt"));
    }

    @Test
    void testSymbolicLinkRejected() throws IOException {
        // Create a real file outside the log dir
        Path outsideFile = Files.createTempFile("outside", ".log");
        outsideFile.toFile().deleteOnExit();
        Files.writeString(outsideFile, "secret data");

        // Create a symlink inside the log dir pointing outside
        Path symlink = tempDir.resolve("openhab.log");
        Files.createSymbolicLink(symlink, outsideFile);

        assertThrows(LogFileNotFoundException.class,
                () -> service.resolveAndValidate("openhab.log"));

        Files.deleteIfExists(outsideFile);
    }

    @Test
    void testValidFileAccepted() throws IOException, LogFileNotFoundException {
        Path logFile = tempDir.resolve("openhab.log");
        Files.writeString(logFile, "2026-06-09 12:00:00.000 [INFO ] [test] - hello\n");

        Path result = service.resolveAndValidate("openhab.log");
        assertEquals(logFile, result);
    }

    // --- Tail tests ---

    @Test
    void testTailReturnsLastLines() throws IOException, LogFileNotFoundException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            content.append(String.format("2026-06-09 12:00:%02d.000 [INFO ] [test] - Line %d%n", i, i));
        }
        Files.writeString(tempDir.resolve("test.log"), content.toString());

        LogQueryResult result = service.tail("test.log", 5, null, null, null, null);
        assertEquals(5, result.getEntries().size());
        assertEquals("Line 20", result.getEntries().get(4).getMessage());
        assertEquals("Line 16", result.getEntries().get(0).getMessage());
    }

    @Test
    void testTailWithLevelFilter() throws IOException, LogFileNotFoundException {
        String content = """
                2026-06-09 12:00:01.000 [INFO ] [test] - Info message
                2026-06-09 12:00:02.000 [WARN ] [test] - Warn message
                2026-06-09 12:00:03.000 [ERROR] [test] - Error message
                2026-06-09 12:00:04.000 [DEBUG] [test] - Debug message
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.tail("test.log", 100, "WARN", null, null, null);
        assertEquals(2, result.getEntries().size());
        assertEquals("Warn message", result.getEntries().get(0).getMessage());
        assertEquals("Error message", result.getEntries().get(1).getMessage());
    }

    @Test
    void testTailWithLoggerFilter() throws IOException, LogFileNotFoundException {
        String content = """
                2026-06-09 12:00:01.000 [INFO ] [org.openhab.binding.mqtt] - MQTT message
                2026-06-09 12:00:02.000 [INFO ] [org.openhab.binding.zwave] - ZWave message
                2026-06-09 12:00:03.000 [INFO ] [org.openhab.binding.mqtt] - Another MQTT
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.tail("test.log", 100, null, "mqtt", null, null);
        assertEquals(2, result.getEntries().size());
    }

    @Test
    void testTailWithTimeFilter() throws IOException, LogFileNotFoundException {
        String content = """
                2026-06-09 10:00:00.000 [INFO ] [test] - Morning
                2026-06-09 12:00:00.000 [INFO ] [test] - Noon
                2026-06-09 14:00:00.000 [INFO ] [test] - Afternoon
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.tail("test.log", 100, null, null, "2026-06-09T11:00:00", "2026-06-09T13:00:00");
        assertEquals(1, result.getEntries().size());
        assertEquals("Noon", result.getEntries().get(0).getMessage());
    }

    // --- Search tests ---

    @Test
    void testSearchByPattern() throws IOException, LogFileNotFoundException {
        String content = """
                2026-06-09 12:00:01.000 [INFO ] [test] - Connection established
                2026-06-09 12:00:02.000 [WARN ] [test] - Connection timeout after 30s
                2026-06-09 12:00:03.000 [ERROR] [test] - Connection refused
                2026-06-09 12:00:04.000 [INFO ] [test] - Data received
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.search("test.log", "timeout|refused", null, null, null, null, 100, false);
        assertEquals(2, result.getTotalEntries());
        assertEquals("timeout|refused", result.getPattern());
    }

    @Test
    void testSearchInvalidRegexRejected() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-06-09 12:00:00.000 [INFO ] [test] - hello\n");
        assertThrows(IllegalArgumentException.class,
                () -> service.search("test.log", "[invalid", null, null, null, null, 100, false));
    }

    @Test
    void testSearchPatternTooLongRejected() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-06-09 12:00:00.000 [INFO ] [test] - hello\n");
        String longPattern = "a".repeat(501);
        assertThrows(IllegalArgumentException.class,
                () -> service.search("test.log", longPattern, null, null, null, null, 100, false));
    }

    // --- Case-insensitive search (default) ---

    /** Real-world fixture: a lowercase query must find mixed-case thing labels. */
    @Test
    void testSearchIsCaseInsensitiveByDefault() throws IOException, LogFileNotFoundException {
        String content = """
                2026-08-23 08:45:01.787 [INFO ] [org.openhab.rule.GeneralTestRule    ] - Thing with label UniFi Controller is OFFLINE
                2026-08-23 09:45:00.401 [INFO ] [org.openhab.rule.GeneralTestRule    ] - Thing with label UniFi Site Korosbanya is UNKNOWN
                2026-08-23 09:46:00.401 [INFO ] [org.openhab.rule.GeneralTestRule    ] - Thing with label Shelly Plug is ONLINE
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.search("test.log", "unifi", null, null, null, null, 100, false);
        assertEquals(2, result.getTotalEntries());
    }

    @Test
    void testSearchCaseSensitiveOptOut() throws IOException, LogFileNotFoundException {
        String content = """
                2026-08-23 08:45:01.787 [INFO ] [test] - Thing with label UniFi Controller is OFFLINE
                2026-08-23 08:45:02.787 [INFO ] [test] - unifi client roamed
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult sensitive = service.search("test.log", "UniFi", null, null, null, null, 100, false, true);
        assertEquals(1, sensitive.getTotalEntries());
        assertEquals("Thing with label UniFi Controller is OFFLINE",
                sensitive.getEntries().get(0).getMessage());

        LogQueryResult insensitive = service.search("test.log", "UniFi", null, null, null, null, 100, false, false);
        assertEquals(2, insensitive.getTotalEntries());
    }

    /** CASE_INSENSITIVE alone is ASCII-only; UNICODE_CASE is what folds accented labels. */
    @Test
    void testSearchCaseInsensitiveIsUnicodeAware() throws IOException, LogFileNotFoundException {
        String content = """
                2026-08-23 09:45:00.401 [INFO ] [test] - Thing with label UniFi Site Kőrösbánya is UNKNOWN
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.search("test.log", "kŐRÖSBÁNYA", null, null, null, null, 100, false);
        assertEquals(1, result.getTotalEntries());
    }

    /** An explicit inline (?i) flag must keep working for existing callers. */
    @Test
    void testSearchInlineCaseInsensitiveFlagStillWorks() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "(?i)unifi", null, null, null, null, 100, false);
        assertEquals(1, result.getTotalEntries());
    }

    /**
     * An inline (?-i) flag overrides the case-insensitive compile flags, giving callers per-pattern
     * control without the caseSensitive parameter. Standard Java behaviour, pinned here on purpose.
     */
    @Test
    void testSearchInlineCaseSensitiveFlagOverridesDefault() throws IOException, LogFileNotFoundException {
        String content = """
                2026-08-23 08:45:01.787 [INFO ] [test] - Thing with label UniFi Controller is OFFLINE
                2026-08-23 08:45:02.787 [INFO ] [test] - unifi client roamed
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        LogQueryResult result = service.search("test.log", "(?-i)UniFi", null, null, null, null, 100, false);
        assertEquals(1, result.getTotalEntries());
        assertEquals("Thing with label UniFi Controller is OFFLINE",
                result.getEntries().get(0).getMessage());
    }

    // --- Empty-result hint ---

    @Test
    void testSearchWithNoMatchesReturnsHint() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "zwave", null, null, null, null, 100, false);
        assertEquals(0, result.getTotalEntries());
        String hint = result.getHint();
        assertNotNull(hint);
        assertTrue(hint.contains("case-insensitive"), "hint should state how case was handled: " + hint);
        assertTrue(hint.contains("includeRotated=true"), "hint should suggest rotated files: " + hint);
    }

    @Test
    void testSearchWithNoMatchesHintMentionsCaseSensitiveOptOut()
            throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "unifi", null, null, null, null, 100, false, true);
        assertEquals(0, result.getTotalEntries());
        String hint = result.getHint();
        assertNotNull(hint);
        assertTrue(hint.contains("caseSensitive=true"), "hint should name the opt-out: " + hint);
    }

    @Test
    void testSearchWithMatchesHasNoHint() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "unifi", null, null, null, null, 100, false);
        assertEquals(1, result.getTotalEntries());
        assertNull(result.getHint(), "hint must be absent when there are matches");
    }

    @Test
    void testSearchWithNoMatchesHintMentionsTimeRangeWhenBounded()
            throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "zwave", null, null,
                "2026-08-23T00:00:00", "2026-08-23T23:59:59", 100, false);
        assertEquals(0, result.getTotalEntries());
        String hint = result.getHint();
        assertNotNull(hint);
        assertTrue(hint.contains("widen the since/until range"),
                "a bounded query should be told to widen the range: " + hint);
    }

    @Test
    void testSearchWithNoMatchesHintOmitsTimeRangeWhenUnbounded()
            throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "zwave", null, null, null, null, 100, false);
        assertEquals(0, result.getTotalEntries());
        String hint = result.getHint();
        assertNotNull(hint);
        assertFalse(hint.contains("since/until"),
                "an unbounded query has no range to widen: " + hint);
    }

    /** Telling a caller to shorten 'a' is nonsense; short patterns get the broader-pattern wording. */
    @Test
    void testSearchWithNoMatchesHintDoesNotSuggestShorteningShortPattern()
            throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-08-23 08:45:01.787 [INFO ] [test] - UniFi Controller is OFFLINE\n");

        LogQueryResult result = service.search("test.log", "zwave", null, null, null, null, 100, false);
        String shortHint = result.getHint();
        assertNotNull(shortHint);
        assertTrue(shortHint.contains("broader pattern"), "short pattern hint: " + shortHint);
        assertFalse(shortHint.contains("shorter substring"), "short pattern hint: " + shortHint);

        LogQueryResult longResult = service.search("test.log", "zwave node 5 did not respond",
                null, null, null, null, 100, false);
        String longHint = longResult.getHint();
        assertNotNull(longHint);
        assertTrue(longHint.contains("shorter substring"), "long pattern hint: " + longHint);
    }

    // --- UTF-8 handling ---

    @Test
    void testTailWithUtf8Content() throws IOException, LogFileNotFoundException {
        String content = """
                2026-06-09 12:00:01.000 [INFO ] [test] - Сообщение на русском
                2026-06-09 12:00:02.000 [INFO ] [test] - Nachricht auf Deutsch: Ü Ö Ä
                2026-06-09 12:00:03.000 [INFO ] [test] - 日本語メッセージ
                """;
        Files.writeString(tempDir.resolve("test.log"), content, StandardCharsets.UTF_8);

        LogQueryResult result = service.tail("test.log", 10, null, null, null, null);
        assertEquals(3, result.getEntries().size());
        assertEquals("Сообщение на русском", result.getEntries().get(0).getMessage());
        assertEquals("Nachricht auf Deutsch: Ü Ö Ä", result.getEntries().get(1).getMessage());
        assertEquals("日本語メッセージ", result.getEntries().get(2).getMessage());
    }

    // --- File listing ---

    @Test
    void testListFiles() throws IOException {
        Files.writeString(tempDir.resolve("openhab.log"), "content");
        Files.writeString(tempDir.resolve("openhab.log.1"), "old content");
        Files.writeString(tempDir.resolve("events.log"), "events");
        Files.writeString(tempDir.resolve("secret.txt"), "should not appear");

        LogFilesResult result = service.listFiles();
        assertEquals(3, result.getFiles().size());

        List<String> names = result.getFiles().stream().map(LogFileInfo::getName).sorted().toList();
        assertTrue(names.contains("openhab.log"));
        assertTrue(names.contains("openhab.log.1"));
        assertTrue(names.contains("events.log"));
        assertFalse(names.contains("secret.txt"));
    }

    // --- Config validation ---

    @Test
    void testConfigClampsInvalidValues() {
        service.activate(Map.of(
                "maxLines", -5,
                "maxSearchResults", 0,
                "allowedFiles", "openhab.log*,events.log*,test.log*",
                "regexTimeoutMs", 50));

        // Service should still function (values clamped to minimums)
        assertDoesNotThrow(() -> {
            Files.writeString(tempDir.resolve("test.log"),
                    "2026-06-09 12:00:00.000 [INFO ] [test] - hello\n");
            service.tail("test.log", 1, null, null, null, null);
        });
    }

    @Test
    void testConfigAcceptsStringValues() {
        service.activate(Map.of(
                "maxLines", "500",
                "maxSearchResults", "200",
                "allowedFiles", "openhab.log*,events.log*,test.log*",
                "regexTimeoutMs", "3000"));

        assertDoesNotThrow(() -> {
            Files.writeString(tempDir.resolve("test.log"),
                    "2026-06-09 12:00:00.000 [INFO ] [test] - hello\n");
            service.tail("test.log", 1, null, null, null, null);
        });
    }

    // --- Rotated file search ---

    @Test
    void testSearchIncludesRotatedFiles() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-06-09 14:00:00.000 [ERROR] [test] - Current error\n");
        Files.writeString(tempDir.resolve("test.log.1"),
                "2026-06-09 10:00:00.000 [ERROR] [test] - Old error\n");

        LogQueryResult result = service.search("test.log", "error", null, null, null, null, 100, true);
        assertEquals(2, result.getTotalEntries());
    }

    @Test
    void testSearchExcludesGzFiles() throws IOException, LogFileNotFoundException {
        Files.writeString(tempDir.resolve("test.log"),
                "2026-06-09 14:00:00.000 [ERROR] [test] - Current\n");
        Files.writeString(tempDir.resolve("test.log.1.gz"), "compressed data");

        LogQueryResult result = service.search("test.log", ".", null, null, null, null, 100, true);
        // Only the primary file should match, not the .gz
        assertEquals(1, result.getTotalEntries());
    }

    // --- Timestamp offset handling ---

    @Test
    void testTimeFilterWithOffsetTimestamp() throws IOException, LogFileNotFoundException {
        // Log entries are in local time (system timezone)
        String content = """
                2026-06-09 10:00:00.000 [INFO ] [test] - Morning
                2026-06-09 12:00:00.000 [INFO ] [test] - Noon
                2026-06-09 14:00:00.000 [INFO ] [test] - Afternoon
                """;
        Files.writeString(tempDir.resolve("test.log"), content);

        // Using local time filter (no offset) — should work straightforwardly
        LogQueryResult result = service.tail("test.log", 100, null, null, "2026-06-09T11:00:00", "2026-06-09T13:00:00");
        assertEquals(1, result.getEntries().size());
        assertEquals("Noon", result.getEntries().get(0).getMessage());
    }
}
