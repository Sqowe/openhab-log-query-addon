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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.OpenHAB;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for reading, parsing, and searching openHAB log files.
 *
 * <p>Handles:
 * <ul>
 *   <li>Reading log files from tail (efficient reverse reading, UTF-8 aware)</li>
 *   <li>Parsing openHAB log format into structured LogEntry objects</li>
 *   <li>Multi-line entry handling (stack traces)</li>
 *   <li>Filtering by level, logger, and time range</li>
 *   <li>Regex search with timeout protection using bounded thread pool</li>
 *   <li>File name validation including symlink rejection (security)</li>
 * </ul>
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@Component(service = LogFileService.class, configurationPid = "org.openhab.io.rest.logs")
@NonNullByDefault
public class LogFileService {

    private final Logger logger = LoggerFactory.getLogger(LogFileService.class);

    /**
     * openHAB log line pattern:
     * 2026-06-09 12:34:56.789 [ERROR] [org.openhab.binding.zwave] - Message
     */
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
                    + "\\[(\\w+)\\s*\\]\\s+"
                    + "\\[([^\\]]+)\\]\\s*-\\s*(.*)$");

    private static final DateTimeFormatter LOG_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Map<String, Integer> LEVEL_PRIORITY = Map.of(
            "TRACE", 0,
            "DEBUG", 1,
            "INFO", 2,
            "WARN", 3,
            "ERROR", 4);

    private static final int MAX_REGEX_LENGTH = 500;
    private static final int MAX_EXECUTOR_THREADS = 4;
    private static final int EXECUTOR_QUEUE_SIZE = 2;
    private static final int MIN_CONFIG_VALUE = 1;
    private static final int MAX_CONFIG_LINES = 10000;
    private static final int MAX_CONFIG_SEARCH_RESULTS = 10000;
    private static final int MIN_REGEX_TIMEOUT_MS = 100;
    private static final int MAX_REGEX_TIMEOUT_MS = 60000;
    private static final int MAX_MESSAGE_LENGTH_FOR_REGEX = 100000;
    /** Below this length a pattern is already minimal, so suggesting a shorter one is unhelpful. */
    private static final int MIN_PATTERN_LENGTH_FOR_SHORTEN_HINT = 8;
    private static final long MAX_TAIL_BYTES = 50 * 1024 * 1024; // 50 MB max scan
    private static final int MAX_ROTATED_FILES = 10;

    private final ExecutorService regexExecutor;

    private volatile LogQueryConfig config = new LogQueryConfig();

    public LogFileService() {
        this.regexExecutor = new ThreadPoolExecutor(
                1, MAX_EXECUTOR_THREADS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(EXECUTOR_QUEUE_SIZE),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Activate
    public void activate(Map<String, Object> properties) {
        applyConfig(properties);
    }

    @Modified
    public void modified(Map<String, Object> properties) {
        applyConfig(properties);
    }

    @Deactivate
    public void deactivate() {
        regexExecutor.shutdownNow();
        try {
            if (!regexExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Regex executor did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyConfig(Map<String, Object> properties) {
        LogQueryConfig newConfig = new LogQueryConfig();

        Object maxLines = properties.get("maxLines");
        if (maxLines != null) {
            int value = parseIntConfig(maxLines, "maxLines", newConfig.maxLines);
            newConfig.maxLines = clamp(value, MIN_CONFIG_VALUE, MAX_CONFIG_LINES, "maxLines");
        }

        Object maxSearchResults = properties.get("maxSearchResults");
        if (maxSearchResults != null) {
            int value = parseIntConfig(maxSearchResults, "maxSearchResults", newConfig.maxSearchResults);
            newConfig.maxSearchResults = clamp(value, MIN_CONFIG_VALUE, MAX_CONFIG_SEARCH_RESULTS, "maxSearchResults");
        }

        Object allowedFiles = properties.get("allowedFiles");
        if (allowedFiles instanceof String && !((String) allowedFiles).isBlank()) {
            newConfig.allowedFiles = (String) allowedFiles;
        }

        Object regexTimeoutMs = properties.get("regexTimeoutMs");
        if (regexTimeoutMs != null) {
            int value = parseIntConfig(regexTimeoutMs, "regexTimeoutMs", newConfig.regexTimeoutMs);
            newConfig.regexTimeoutMs = clamp(value, MIN_REGEX_TIMEOUT_MS, MAX_REGEX_TIMEOUT_MS, "regexTimeoutMs");
        }

        this.config = newConfig;
        logger.debug("Log Query config updated: maxLines={}, maxSearchResults={}, allowedFiles={}, regexTimeoutMs={}",
                newConfig.maxLines, newConfig.maxSearchResults, newConfig.allowedFiles, newConfig.regexTimeoutMs);
    }

    private int parseIntConfig(Object value, String paramName, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                logger.warn("Configuration '{}' has invalid value '{}', using default {}", paramName, value,
                        defaultValue);
            }
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max, String paramName) {
        if (value < min) {
            logger.warn("Configuration '{}' value {} is below minimum {}, using {}", paramName, value, min, min);
            return min;
        }
        if (value > max) {
            logger.warn("Configuration '{}' value {} is above maximum {}, using {}", paramName, value, max, max);
            return max;
        }
        return value;
    }

    /**
     * Returns the most recent log entries from a file (tail operation).
     * When filters (level, logger, since, until) are specified, reads backwards through the file
     * until enough matching entries are found or the byte budget is exhausted.
     *
     * @throws LogFileNotFoundException if the file does not exist or is not allowed
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if parameters are invalid
     */
    public LogQueryResult tail(String fileName, int lines, @Nullable String level, @Nullable String loggerFilter,
            @Nullable String since, @Nullable String until) throws LogFileNotFoundException, IOException {
        if (lines < 1) {
            throw new IllegalArgumentException("lines must be at least 1");
        }
        int effectiveLines = Math.min(lines, config.maxLines);
        Path filePath = resolveAndValidate(fileName);

        boolean hasFilters = (level != null && !level.isBlank())
                || (loggerFilter != null && !loggerFilter.isBlank())
                || (since != null && !since.isBlank())
                || (until != null && !until.isBlank());

        if (!hasFilters) {
            // No filters: just read last N lines directly
            List<String> rawLines = readTail(filePath, effectiveLines);
            List<LogEntry> entries = parseLogLines(rawLines, 0);
            return LogQueryResult.forTail(fileName, entries);
        }

        // With filters: progressively scan backwards for enough matching entries.
        // Start with a window, expand if not enough matches found.
        int scanWindow = effectiveLines * 4; // Read more lines than needed to find matches
        int maxScanWindow = effectiveLines * 50; // Upper bound to avoid scanning entire huge file
        List<LogEntry> filtered = List.of();

        while (scanWindow <= maxScanWindow) {
            List<String> rawLines = readTail(filePath, scanWindow);
            List<LogEntry> allEntries = parseLogLines(rawLines, 0);
            filtered = applyFilters(allEntries, level, loggerFilter, since, until, effectiveLines);

            if (filtered.size() >= effectiveLines || rawLines.size() < scanWindow) {
                // Found enough, or we've read the entire file
                break;
            }
            scanWindow *= 2;
        }

        // Return only the last N matching entries
        if (filtered.size() > effectiveLines) {
            filtered = filtered.subList(filtered.size() - effectiveLines, filtered.size());
        }

        return LogQueryResult.forTail(fileName, filtered);
    }

    /**
     * Searches log file content with a regex pattern, matching case-insensitively.
     *
     * @throws LogFileNotFoundException if the file does not exist or is not allowed
     * @throws IllegalArgumentException if parameters are invalid
     */
    public LogQueryResult search(String fileName, String pattern, @Nullable String level,
            @Nullable String loggerFilter, @Nullable String since, @Nullable String until, int limit,
            boolean includeRotated) throws LogFileNotFoundException, IOException {
        return search(fileName, pattern, level, loggerFilter, since, until, limit, includeRotated, false);
    }

    /**
     * Searches log file content with a regex pattern.
     *
     * <p>
     * Matching is case-insensitive unless {@code caseSensitive} is {@code true}, which keeps the
     * {@code pattern} parameter consistent with the case-insensitive {@code level} and
     * {@code loggerFilter} parameters. Case folding is Unicode-aware, so accented log content
     * (item labels, thing names) folds as well.
     *
     * @throws LogFileNotFoundException if the file does not exist or is not allowed
     * @throws IllegalArgumentException if parameters are invalid
     */
    public LogQueryResult search(String fileName, String pattern, @Nullable String level,
            @Nullable String loggerFilter, @Nullable String since, @Nullable String until, int limit,
            boolean includeRotated, boolean caseSensitive) throws LogFileNotFoundException, IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (pattern.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException(
                    "Pattern too long (max " + MAX_REGEX_LENGTH + " characters)");
        }

        // Case folding adds no backtracking risk; the regex timeout and length bounds still apply.
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern compiledPattern;
        try {
            compiledPattern = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
        }

        int effectiveLimit = Math.min(limit, config.maxSearchResults);
        List<LogEntry> allMatches = new ArrayList<>();

        // Collect files to search
        List<Path> filesToSearch = new ArrayList<>();
        Path primaryFile = resolveAndValidate(fileName);
        filesToSearch.add(primaryFile);

        if (includeRotated) {
            filesToSearch.addAll(findRotatedFiles(fileName));
        }

        for (Path file : filesToSearch) {
            if (allMatches.size() >= effectiveLimit) {
                break;
            }
            List<LogEntry> fileMatches = searchFileStreaming(file, compiledPattern, level, loggerFilter, since, until,
                    effectiveLimit - allMatches.size());
            allMatches.addAll(fileMatches);
        }

        if (allMatches.isEmpty()) {
            return LogQueryResult.forEmptySearch(fileName, pattern,
                    buildNoMatchHint(pattern, caseSensitive, since, until, includeRotated));
        }
        return LogQueryResult.forSearch(fileName, pattern, allMatches);
    }

    /**
     * Builds a recovery hint for a search that matched nothing, so a caller (in particular an LLM
     * driving this API) can correct the query without guessing. Mentions only the levers that are
     * still available for the request that was made.
     */
    private String buildNoMatchHint(String pattern, boolean caseSensitive, @Nullable String since,
            @Nullable String until, boolean includeRotated) {
        StringBuilder hint = new StringBuilder("No entries matched. ");
        if (caseSensitive) {
            hint.append("Matching was case-sensitive (caseSensitive=true) - drop that parameter to ignore case. ");
        } else {
            hint.append("Matching was case-insensitive, so letter case is not the cause. ");
        }
        if (pattern.length() > MIN_PATTERN_LENGTH_FOR_SHORTEN_HINT) {
            hint.append("Try a shorter substring of the message "
                    + "(e.g. 'unifi' instead of 'UniFi Controller is OFFLINE')");
        } else {
            hint.append("Try a broader pattern");
        }
        if ((since != null && !since.isBlank()) || (until != null && !until.isBlank())) {
            hint.append(", widen the since/until range");
        }
        if (!includeRotated) {
            hint.append(", set includeRotated=true to also search rotated files");
        }
        hint.append(", or use pattern='.' with a level or logger filter to see what the file contains.");
        return hint.toString();
    }

    /**
     * Lists available log files with metadata.
     */
    public LogFilesResult listFiles() {
        Path logDir = getLogDirectory();
        List<LogFileInfo> files = new ArrayList<>();

        if (!Files.isDirectory(logDir)) {
            logger.warn("Log directory does not exist: {}", logDir);
            return new LogFilesResult(logDir.toString(), files);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(entry)
                        && isAllowedFile(entry.getFileName().toString())) {
                    long size = Files.size(entry);
                    FileTime lastModifiedTime = Files.getLastModifiedTime(entry);
                    String lastModified = ZonedDateTime
                            .ofInstant(lastModifiedTime.toInstant(), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    files.add(new LogFileInfo(entry.getFileName().toString(), size, lastModified));
                }
            }
        } catch (IOException e) {
            logger.error("Error listing log directory: {}", e.getMessage());
        }

        files.sort(Comparator.comparing(LogFileInfo::getName));
        return new LogFilesResult(logDir.toString(), files);
    }

    // --- Internal methods ---

    private Path getLogDirectory() {
        String logDirProperty = System.getProperty("openhab.logdir");
        if (logDirProperty != null && !logDirProperty.isBlank()) {
            return Path.of(logDirProperty);
        }
        return Path.of(OpenHAB.getUserDataFolder(), "log");
    }

    /**
     * Resolves a file name to a path within the log directory and validates it.
     * Rejects path traversal, disallowed patterns, and symbolic links.
     *
     * @throws LogFileNotFoundException if the file is not allowed or does not exist
     */
    Path resolveAndValidate(String fileName) throws LogFileNotFoundException {
        // Security: reject path traversal attempts
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new LogFileNotFoundException(
                    "Invalid file name: path separators and '..' not allowed");
        }

        if (!isAllowedFile(fileName)) {
            throw new LogFileNotFoundException(
                    "File not allowed by configuration: " + fileName);
        }

        Path logDir = getLogDirectory();
        Path filePath = logDir.resolve(fileName);

        // Double-check the resolved path is still within log directory
        if (!filePath.normalize().startsWith(logDir.normalize())) {
            throw new LogFileNotFoundException("Invalid file path resolved outside log directory");
        }

        // Security: reject symbolic links to prevent reading arbitrary files
        if (Files.isSymbolicLink(filePath)) {
            throw new LogFileNotFoundException("Symbolic links are not allowed: " + fileName);
        }

        if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new LogFileNotFoundException("Log file not found: " + fileName);
        }

        // Final check: compare real path to ensure no symlink shenanigans in parent dirs
        try {
            Path realLogDir = logDir.toRealPath();
            Path realFile = filePath.toRealPath();
            if (!realFile.startsWith(realLogDir)) {
                throw new LogFileNotFoundException("File resolves outside the log directory");
            }
        } catch (IOException e) {
            throw new LogFileNotFoundException("Cannot resolve file path: " + e.getMessage());
        }

        return filePath;
    }

    /**
     * Checks if a file name matches the allowed file patterns from configuration.
     */
    private boolean isAllowedFile(String fileName) {
        String[] patterns = config.allowedFiles.split(",");
        for (String pattern : patterns) {
            String trimmed = pattern.trim();
            if (matchesGlob(fileName, trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simple glob matching supporting '*' and '?' wildcards.
     * All other characters are treated as literals using Pattern.quote.
     */
    private boolean matchesGlob(String fileName, String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append(".");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return fileName.matches(regex.toString());
    }

    /**
     * Reads the last N lines from a file using reverse byte reading, then decodes as UTF-8.
     * Opens file with NOFOLLOW_LINKS to prevent symlink TOCTOU attacks.
     * Stops reading once the requested number of lines is collected or the byte budget is exhausted.
     *
     * @throws IOException if the file cannot be read
     */
    private List<String> readTail(Path filePath, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();

        try (var channel = FileChannel.open(filePath,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long fileLength = channel.size();
            if (fileLength == 0) {
                return lines;
            }

            int chunkSize = 8192;
            long position = fileLength;
            byte[] remainder = new byte[0];
            int linesFound = 0;
            long bytesRead = 0;

            while (position > 0 && linesFound < maxLines && bytesRead < MAX_TAIL_BYTES) {
                int readSize = (int) Math.min(chunkSize, position);
                position -= readSize;

                ByteBuffer buf = ByteBuffer.allocate(readSize);
                int totalRead = 0;
                while (totalRead < readSize) {
                    int n = channel.read(buf, position + totalRead);
                    if (n <= 0) {
                        break;
                    }
                    totalRead += n;
                }
                bytesRead += totalRead;

                buf.flip();
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);

                byte[] buffer = new byte[chunk.length + remainder.length];
                System.arraycopy(chunk, 0, buffer, 0, chunk.length);
                System.arraycopy(remainder, 0, buffer, chunk.length, remainder.length);

                int end = buffer.length;
                // Skip trailing newline at very end of file
                if (position == fileLength - readSize && remainder.length == 0 && end > 0
                        && buffer[end - 1] == '\n') {
                    end--;
                }

                for (int i = end - 1; i >= 0 && linesFound < maxLines; i--) {
                    if (buffer[i] == '\n') {
                        if (i < end - 1) {
                            String line = new String(buffer, i + 1, end - i - 1, StandardCharsets.UTF_8);
                            if (line.endsWith("\r")) {
                                line = line.substring(0, line.length() - 1);
                            }
                            lines.add(line);
                            linesFound++;
                        }
                        end = i;
                    }
                }

                if (linesFound < maxLines) {
                    remainder = new byte[end];
                    System.arraycopy(buffer, 0, remainder, 0, end);
                } else {
                    remainder = new byte[0];
                }
            }

            // First line in file (no preceding newline)
            if (remainder.length > 0 && linesFound < maxLines) {
                String line = new String(remainder, StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                lines.add(line);
            }
        }

        Collections.reverse(lines);
        return lines;
    }

    /**
     * Searches a file in a streaming fashion, parsing and filtering line-by-line,
     * stopping once the result limit is reached. Avoids loading entire file into memory.
     * Opens the file with NOFOLLOW_LINKS to prevent TOCTOU symlink attacks.
     *
     * @throws IOException if the file cannot be read
     */
    private List<LogEntry> searchFileStreaming(Path filePath, Pattern pattern, @Nullable String level,
            @Nullable String loggerFilter, @Nullable String since, @Nullable String until,
            int maxResults) throws IOException {

        LocalDateTime sinceTime = parseIsoTimestamp(since);
        LocalDateTime untilTime = parseIsoTimestamp(until);

        Future<List<LogEntry>> future;
        try {
            future = regexExecutor.submit(() -> {
            List<LogEntry> matches = new ArrayList<>();

            try (var is = Files.newInputStream(filePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                LogEntry currentEntry = null;
                int lineNumber = 0;

                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    lineNumber++;
                    Matcher logMatcher = LOG_LINE_PATTERN.matcher(line);

                    if (logMatcher.matches()) {
                        if (currentEntry != null) {
                            if (matchesSearch(currentEntry, pattern, level, loggerFilter, sinceTime, untilTime)) {
                                matches.add(currentEntry);
                                if (matches.size() >= maxResults) {
                                    return matches;
                                }
                            }
                        }

                        currentEntry = new LogEntry(
                                logMatcher.group(1),
                                logMatcher.group(2).trim(),
                                logMatcher.group(3).trim(),
                                null,
                                logMatcher.group(4),
                                lineNumber);
                    } else if (currentEntry != null && !line.isEmpty()) {
                        currentEntry.appendMessage(line);
                    }
                }

                if (currentEntry != null && matches.size() < maxResults) {
                    if (matchesSearch(currentEntry, pattern, level, loggerFilter, sinceTime, untilTime)) {
                        matches.add(currentEntry);
                    }
                }
            }

            return matches;
        });
        } catch (RejectedExecutionException e) {
            logger.warn("Search request rejected — server is overloaded");
            throw new IOException("Search service is busy — please try again later");
        }

        try {
            return future.get(config.regexTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Regex search timed out after {}ms for pattern: {}", config.regexTimeoutMs,
                    pattern.pattern());
            throw new IllegalArgumentException("Regex search timed out — pattern may be too complex");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Search interrupted — service may be shutting down");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Error during search: " + (cause != null ? cause.getMessage() : e.getMessage()));
        }
    }

    /**
     * Checks if a log entry matches all search criteria (pattern, level, logger, time range).
     * Message is truncated to MAX_MESSAGE_LENGTH_FOR_REGEX before regex matching to prevent
     * catastrophic backtracking on very large messages.
     */
    private boolean matchesSearch(LogEntry entry, Pattern pattern, @Nullable String level,
            @Nullable String loggerFilter, @Nullable LocalDateTime sinceTime, @Nullable LocalDateTime untilTime) {

        if (level != null && !level.isBlank() && !meetsMinimumLevel(entry.getLevel(), level)) {
            return false;
        }

        if (loggerFilter != null && !loggerFilter.isBlank()
                && !entry.getLogger().toLowerCase(Locale.ROOT).contains(loggerFilter.toLowerCase(Locale.ROOT))) {
            return false;
        }

        if (sinceTime != null || untilTime != null) {
            LocalDateTime entryTime = parseLogTimestamp(entry.getTimestamp());
            if (entryTime != null) {
                if (sinceTime != null && entryTime.isBefore(sinceTime)) {
                    return false;
                }
                if (untilTime != null && entryTime.isAfter(untilTime)) {
                    return false;
                }
            }
        }

        // Truncate message to limit regex evaluation time on very large entries
        String message = entry.getMessage();
        if (message.length() > MAX_MESSAGE_LENGTH_FOR_REGEX) {
            message = message.substring(0, MAX_MESSAGE_LENGTH_FOR_REGEX);
        }

        // Use interruptible CharSequence to allow cancellation during catastrophic backtracking
        CharSequence interruptible = new InterruptibleCharSequence(message);
        Matcher matcher = pattern.matcher(interruptible);
        try {
            return matcher.find();
        } catch (RuntimeException e) {
            // InterruptibleCharSequence throws RuntimeException on interrupt
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            throw e;
        }
    }

    /**
     * A CharSequence wrapper that checks for thread interruption on each charAt() call.
     * This makes regex matching interruptible even during catastrophic backtracking,
     * because the regex engine calls charAt() for every character access.
     */
    private static class InterruptibleCharSequence implements CharSequence {
        private final CharSequence inner;

        InterruptibleCharSequence(CharSequence inner) {
            this.inner = inner;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Regex matching interrupted");
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(inner.subSequence(start, end));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }

    /**
     * Parses raw lines into LogEntry objects and applies filters.
     */
    private List<LogEntry> parseAndFilter(List<String> rawLines, @Nullable String level,
            @Nullable String loggerFilter, @Nullable String since, @Nullable String until, int maxEntries) {
        List<LogEntry> entries = parseLogLines(rawLines, 0);
        return applyFilters(entries, level, loggerFilter, since, until, maxEntries);
    }

    /**
     * Parses raw log lines into structured LogEntry objects, handling multi-line entries.
     *
     * @param lines raw text lines from the log file
     * @param startLineNumber the line number offset (for reporting lineNumber in results)
     * @return list of parsed log entries
     */
    List<LogEntry> parseLogLines(List<String> lines, int startLineNumber) {
        List<LogEntry> entries = new ArrayList<>();
        LogEntry currentEntry = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = LOG_LINE_PATTERN.matcher(line);

            if (matcher.matches()) {
                currentEntry = new LogEntry(
                        matcher.group(1),
                        matcher.group(2).trim(),
                        matcher.group(3).trim(),
                        null,
                        matcher.group(4),
                        startLineNumber + i + 1);
                entries.add(currentEntry);
            } else if (currentEntry != null && !line.isEmpty()) {
                currentEntry.appendMessage(line);
            }
        }

        return entries;
    }

    /**
     * Applies level, logger, and time range filters to a list of entries.
     */
    private List<LogEntry> applyFilters(List<LogEntry> entries, @Nullable String level,
            @Nullable String loggerFilter, @Nullable String since, @Nullable String until, int maxEntries) {
        List<LogEntry> filtered = new ArrayList<>();

        LocalDateTime sinceTime = parseIsoTimestamp(since);
        LocalDateTime untilTime = parseIsoTimestamp(until);

        for (LogEntry entry : entries) {
            if (filtered.size() >= maxEntries) {
                break;
            }

            if (level != null && !level.isBlank() && !meetsMinimumLevel(entry.getLevel(), level)) {
                continue;
            }

            if (loggerFilter != null && !loggerFilter.isBlank()
                    && !entry.getLogger().toLowerCase(Locale.ROOT).contains(loggerFilter.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (sinceTime != null || untilTime != null) {
                LocalDateTime entryTime = parseLogTimestamp(entry.getTimestamp());
                if (entryTime != null) {
                    if (sinceTime != null && entryTime.isBefore(sinceTime)) {
                        continue;
                    }
                    if (untilTime != null && entryTime.isAfter(untilTime)) {
                        continue;
                    }
                }
            }

            filtered.add(entry);
        }

        return filtered;
    }

    /**
     * Finds rotated log files (e.g., openhab.log.1, openhab.log.2).
     * Limited to MAX_ROTATED_FILES to prevent unbounded request duration.
     */
    private List<Path> findRotatedFiles(String baseName) {
        Path logDir = getLogDirectory();
        List<Path> rotated = new ArrayList<>();

        if (!Files.isDirectory(logDir)) {
            return rotated;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(baseName + ".")
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(entry)
                        && isAllowedFile(name)
                        && !name.endsWith(".gz")) {
                    rotated.add(entry);
                }
            }
        } catch (IOException e) {
            logger.error("Error finding rotated log files: {}", e.getMessage());
        }

        rotated.sort(Comparator.comparing(p -> p.getFileName().toString()));
        // Limit to prevent unbounded request duration
        if (rotated.size() > MAX_ROTATED_FILES) {
            rotated = rotated.subList(0, MAX_ROTATED_FILES);
        }
        return rotated;
    }

    /**
     * Checks if a log entry's level meets the minimum level threshold.
     * E.g., if minLevel is WARN, then WARN and ERROR pass; INFO, DEBUG, TRACE do not.
     */
    boolean meetsMinimumLevel(String entryLevel, String minLevel) {
        Integer entryPriority = LEVEL_PRIORITY.get(entryLevel.toUpperCase(Locale.ROOT));
        Integer minPriority = LEVEL_PRIORITY.get(minLevel.toUpperCase(Locale.ROOT));

        if (entryPriority == null || minPriority == null) {
            return true;
        }

        return entryPriority >= minPriority;
    }

    /**
     * Parses an ISO 8601 timestamp string to LocalDateTime in the system timezone.
     * When an offset is present, the instant is converted to the system timezone
     * so comparisons against local log timestamps are correct.
     */
    private @Nullable LocalDateTime parseIsoTimestamp(@Nullable String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) {
            return null;
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(isoTimestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return zdt.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(isoTimestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Invalid timestamp format: " + isoTimestamp
                        + ". Expected ISO 8601 (e.g., 2026-06-09T12:00:00 or 2026-06-09T12:00:00+02:00)");
            }
        }
    }

    /**
     * Parses the log file timestamp format (yyyy-MM-dd HH:mm:ss.SSS) to LocalDateTime.
     */
    private @Nullable LocalDateTime parseLogTimestamp(String logTimestamp) {
        try {
            return LocalDateTime.parse(logTimestamp, LOG_TIMESTAMP_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
