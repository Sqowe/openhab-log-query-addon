# Implementation Guide: openHAB Log Query I/O Addon

**Document created:** 2026-06-09
**Last updated:** 2026-06-09
**Target:** Standalone openHAB addon providing REST API for log file search & analysis
**Bundle ID:** `org.openhab.io.rest.logs`
**Category:** I/O Extension (REST resource)
**Status:** Core implementation complete, ready for deployment testing

---

## Implementation Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1. Project Scaffolding | Maven, Karaf feature, OH-INF metadata, ARCHITECTURE.md | ✅ Done |
| 2. Core Implementation | DTOs, LogFileService, config, exception handling | ✅ Done |
| 3. REST Endpoint | LogQueryResource with OpenAPI annotations | ✅ Done |
| 4. Unit Tests | LogFileService tests (27 tests, all passing) | ✅ Done |
| 5. REST Resource Tests | LogQueryResource HTTP status mapping tests | ⬜ Not started |
| 6. Build & Deploy | Package JAR, deploy to openHAB, verify endpoints | ⬜ Not started |
| 7. MCP Server Integration | Python client methods for openhab-mcp | ⬜ Not started |
| 8. Future Enhancements | SSE streaming, .gz support, WebSocket tail | ⬜ Not started |

### Security Hardening (applied during review iterations)

| Mitigation | Status |
|-----------|--------|
| NOFOLLOW_LINKS file opens (TOCTOU prevention) | ✅ |
| InterruptibleCharSequence for regex DoS | ✅ |
| Path traversal + symlink rejection | ✅ |
| Bounded executor with AbortPolicy | ✅ |
| Message length cap before regex | ✅ |
| Byte budget (50MB) for tail reads | ✅ |
| Rotated file cap (max 10) | ✅ |
| Volatile config for thread safety | ✅ |
| Locale.ROOT for case comparisons | ✅ |
| StringBuilder message accumulation | ✅ |
| IOException propagation (404 vs 500) | ✅ |
| Config validation with clamping | ✅ |

---

## 1. Purpose

openHAB has no REST endpoint to query historical log content. The existing infrastructure:

| What exists | What it does | What it doesn't do |
|-------------|-------------|-------------------|
| `/rest/logging` | Get/set logger *levels* | Read log file content |
| `/ws/logs` | Stream live log events via WebSocket | Search history, filter past events |
| `logreader` binding | Count errors/warnings, trigger on match | Return log lines, search, paginate |

This addon fills the gap by exposing `/rest/logs` — a read-only REST API for searching, filtering, and tailing log file content.

---

## 2. Architecture

```
┌──────────────────────────────────────────────┐
│            openHAB Runtime (Karaf/OSGi)       │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │  org.openhab.io.rest.logs              │  │
│  │                                        │  │
│  │  LogQueryResource.java (JAX-RS)        │  │
│  │    @Path("/logs")                      │  │
│  │    GET /rest/logs                      │  │
│  │    GET /rest/logs/search               │  │
│  │    GET /rest/logs/files                │  │
│  │                                        │  │
│  │  LogFileService.java                   │  │
│  │    - Read/tail log files               │  │
│  │    - Parse log lines into structured   │  │
│  │    - Regex search with time filtering  │  │
│  │                                        │  │
│  │  LogEntry.java (DTO)                   │  │
│  │  LogQueryConfig.java                   │  │
│  └────────────────────┬───────────────────┘  │
│                       │ filesystem            │
│                       ▼                       │
│  ┌────────────────────────────────────────┐  │
│  │  $OPENHAB_LOGDIR/                      │  │
│  │    openhab.log                         │  │
│  │    openhab.log.1 (rotated)             │  │
│  │    events.log                          │  │
│  │    events.log.1                        │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

---

## 3. REST API Design

### 3.1 `GET /rest/logs` — Tail / Recent entries

Returns the most recent log entries (like `tail -n`).

**Query parameters:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file name (basename only) |
| `lines` | int | `100` | Number of lines to return (max 1000) |
| `level` | string | — | Filter by minimum level: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `logger` | string | — | Filter by logger name (substring match) |
| `since` | string | — | ISO 8601 timestamp — only entries after this time |
| `until` | string | — | ISO 8601 timestamp — only entries before this time |

**Response (200 OK):**

```json
{
  "file": "openhab.log",
  "totalEntries": 87,
  "entries": [
    {
      "timestamp": "2026-06-09T12:34:56.789+0200",
      "level": "ERROR",
      "logger": "org.openhab.binding.zwave",
      "thread": "ZWave Controller",
      "message": "Node 5: Timeout waiting for response"
    }
  ]
}
```

### 3.2 `GET /rest/logs/search` — Full-text / regex search

Searches log file content with regex pattern matching.

**Query parameters:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file name |
| `pattern` | string | *required* | Java regex pattern to search for |
| `level` | string | — | Filter by minimum level |
| `logger` | string | — | Filter by logger name (substring) |
| `since` | string | — | ISO 8601 — entries after this time |
| `until` | string | — | ISO 8601 — entries before this time |
| `limit` | int | `200` | Max results to return (max 1000) |
| `includeRotated` | boolean | `false` | Also search rotated files (.log.1, .log.2, ...) |

**Response (200 OK):**

```json
{
  "file": "openhab.log",
  "pattern": "timeout|Timeout",
  "totalMatches": 12,
  "entries": [
    {
      "timestamp": "2026-06-09T12:34:56.789+0200",
      "level": "WARN",
      "logger": "org.openhab.binding.mqtt",
      "thread": "MQTT Connection",
      "message": "Connection timeout after 30s",
      "lineNumber": 4523
    }
  ]
}
```

### 3.3 `GET /rest/logs/files` — List available log files

**Response (200 OK):**

```json
{
  "logDirectory": "/var/log/openhab",
  "files": [
    {
      "name": "openhab.log",
      "size": 2456789,
      "lastModified": "2026-06-09T12:35:00+0200"
    },
    {
      "name": "openhab.log.1",
      "size": 10485760,
      "lastModified": "2026-06-09T00:00:01+0200"
    },
    {
      "name": "events.log",
      "size": 1234567,
      "lastModified": "2026-06-09T12:34:58+0200"
    }
  ]
}
```

### 3.4 Error responses

| Status | When |
|--------|------|
| `400` | Invalid parameters (bad regex, invalid level, lines > max) |
| `403` | Non-admin user |
| `404` | Requested log file not found |
| `500` | I/O error reading file |

---

## 4. Project Structure

```
org.openhab.io.rest.logs/
├── pom.xml                          # ✅ Maven build (standalone, openHAB 5.0 BOM, Java 21)
├── NOTICE                           # ✅ EPL-2.0 notice
├── .gitignore                       # ✅ Excludes target/, IDE files
├── ARCHITECTURE.md                  # ✅ Full technical design document
├── README.md                        # ✅ User documentation
├── src/main/
│   ├── feature/
│   │   └── feature.xml              # ✅ Karaf feature definition
│   └── java/org/openhab/io/rest/logs/internal/
│       ├── LogQueryResource.java    # ✅ JAX-RS REST endpoint
│       ├── LogFileService.java      # ✅ File reading & parsing logic (hardened)
│       ├── LogEntry.java            # ✅ Parsed log entry DTO
│       ├── LogFileInfo.java         # ✅ File metadata DTO
│       ├── LogFilesResult.java      # ✅ File listing response DTO
│       ├── LogQueryResult.java      # ✅ Query response wrapper DTO
│       ├── LogQueryConfig.java      # ✅ Configuration (ConfigAdmin)
│       └── LogFileNotFoundException.java  # ✅ Custom exception
├── src/main/resources/
│   └── OH-INF/
│       ├── addon/
│       │   └── addon.xml            # ✅ Add-on metadata
│       └── config/
│           └── logquery.xml         # ✅ Config description for service
└── src/test/java/org/openhab/io/rest/logs/internal/
    └── LogFileServiceTest.java      # ✅ 27 unit tests
```

---

## 5. Implementation Details

### 5.1 LogQueryResource.java

```java
package org.openhab.io.rest.logs.internal;

import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.auth.Role;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Component
@JaxrsResource
@JaxrsName("logs")
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "="
        + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path("logs")
@RolesAllowed({ Role.ADMIN })
@SecurityRequirement(name = "oauth2", scopes = { "admin" })
@Tag(name = "logs")
@NonNullByDefault
public class LogQueryResource implements RESTResource {

    private final LogFileService logFileService;

    @Activate
    public LogQueryResource(@Reference LogFileService logFileService) {
        this.logFileService = logFileService;
    }

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getLogEntries",
        summary = "Get recent log entries (tail)",
        responses = {
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Invalid parameters"),
            @ApiResponse(responseCode = "404", description = "Log file not found")
        })
    public Response getLogEntries(
            @QueryParam("file") @DefaultValue("openhab.log")
                @Parameter(description = "Log file name") String file,
            @QueryParam("lines") @DefaultValue("100")
                @Parameter(description = "Number of lines (max 1000)") int lines,
            @QueryParam("level") @Parameter(description = "Minimum log level filter") String level,
            @QueryParam("logger") @Parameter(description = "Logger name substring filter") String logger,
            @QueryParam("since") @Parameter(description = "ISO 8601 start time") String since,
            @QueryParam("until") @Parameter(description = "ISO 8601 end time") String until,
            @Context UriInfo uriInfo) {

        if (lines < 1 || lines > 1000) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"lines must be between 1 and 1000\"}")
                    .build();
        }

        try {
            LogQueryResult result = logFileService.tail(file, lines, level, logger, since, until);
            return Response.ok(result).build();
        } catch (LogFileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "searchLogs",
        summary = "Search log entries by regex pattern",
        responses = {
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "400", description = "Invalid pattern or parameters"),
            @ApiResponse(responseCode = "404", description = "Log file not found")
        })
    public Response searchLogs(
            @QueryParam("file") @DefaultValue("openhab.log")
                @Parameter(description = "Log file name") String file,
            @QueryParam("pattern") @Parameter(description = "Java regex pattern", required = true) String pattern,
            @QueryParam("level") @Parameter(description = "Minimum log level filter") String level,
            @QueryParam("logger") @Parameter(description = "Logger name substring filter") String logger,
            @QueryParam("since") @Parameter(description = "ISO 8601 start time") String since,
            @QueryParam("until") @Parameter(description = "ISO 8601 end time") String until,
            @QueryParam("limit") @DefaultValue("200")
                @Parameter(description = "Max results (max 1000)") int limit,
            @QueryParam("includeRotated") @DefaultValue("false")
                @Parameter(description = "Include rotated log files") boolean includeRotated,
            @Context UriInfo uriInfo) {

        if (pattern == null || pattern.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"pattern parameter is required\"}")
                    .build();
        }

        try {
            LogQueryResult result = logFileService.search(
                    file, pattern, level, logger, since, until, limit, includeRotated);
            return Response.ok(result).build();
        } catch (LogFileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/files")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listLogFiles",
        summary = "List available log files",
        responses = {
            @ApiResponse(responseCode = "200", description = "OK")
        })
    public Response listLogFiles(@Context UriInfo uriInfo) {
        return Response.ok(logFileService.listFiles()).build();
    }
}
```

### 5.2 LogFileService.java

Core logic — registered as an OSGi `@Component` service.

**Key responsibilities:**
- Resolve `$OPENHAB_LOGDIR` (from `ConfigConstants.getUserDataFolder() + "/log"` or system property `openhab.logdir`)
- Read log files from tail (use `RandomAccessFile` or `ReversedLinesFileReader` from commons-io)
- Parse openHAB log format: `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] [logger] - message`
- Handle multi-line entries (stack traces) — lines without timestamp belong to previous entry
- Regex compilation with timeout protection (prevent ReDoS)
- Time range filtering (parse timestamps, compare with since/until)
- Level hierarchy filtering (WARN includes WARN + ERROR)

```java
@Component(service = LogFileService.class)
@NonNullByDefault
public class LogFileService {

    // openHAB log line pattern:
    // 2026-06-09 12:34:56.789 [WARN ] [org.openhab.binding.mqtt] - Connection timeout
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
        + "\\[(\\w+)\\s*\\]\\s+"
        + "\\[([^\\]]+)\\]\\s*-\\s*(.*)$"
    );

    private static final Map<String, Integer> LEVEL_PRIORITY = Map.of(
        "TRACE", 0, "DEBUG", 1, "INFO", 2, "WARN", 3, "ERROR", 4
    );

    // ... implementation
}
```

### 5.3 LogEntry.java

```java
@NonNullByDefault
public class LogEntry {
    public String timestamp;
    public String level;
    public String logger;
    public @Nullable String thread;
    public String message;
    public @Nullable Integer lineNumber;

    // Constructor, builder pattern
}
```

### 5.4 Log line parsing

openHAB log format (default from log4j2.xml):
```
2026-06-09 12:34:56.789 [ERROR] [org.openhab.binding.zwave.handler] - Node 5: Timeout
    at org.openhab.binding.zwave.internal.ZWaveController.send(ZWaveController.java:234)
    at org.openhab.binding.zwave.internal.ZWaveController.poll(ZWaveController.java:123)
```

**Rules:**
- A line starting with a timestamp pattern starts a new log entry
- Subsequent lines without timestamp are continuation (stack trace / multiline message)
- Append continuation lines to the previous entry's message

---

## 6. Configuration

Via openHAB Config Admin (shows up in Settings → Add-on Settings):

```xml
<!-- src/main/resources/OH-INF/config/logquery.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<config-description:config-descriptions
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:config-description="https://openhab.org/schemas/config-description/v1.0.0"
    xsi:schemaLocation="https://openhab.org/schemas/config-description/v1.0.0
        https://openhab.org/schemas/config-description-1.0.0.xsd">

    <config-description uri="io:logquery">
        <parameter name="maxLines" type="integer">
            <label>Maximum Lines</label>
            <description>Maximum number of lines returned per request</description>
            <default>1000</default>
        </parameter>
        <parameter name="maxSearchResults" type="integer">
            <label>Maximum Search Results</label>
            <description>Maximum entries returned by search</description>
            <default>1000</default>
        </parameter>
        <parameter name="allowedFiles" type="text">
            <label>Allowed File Patterns</label>
            <description>Glob patterns for allowed log files (comma-separated).
                Prevents reading arbitrary files.</description>
            <default>openhab.log*,events.log*</default>
        </parameter>
        <parameter name="regexTimeoutMs" type="integer">
            <label>Regex Timeout (ms)</label>
            <description>Maximum time for regex pattern matching per file</description>
            <default>5000</default>
        </parameter>
    </config-description>
</config-description:config-descriptions>
```

---

## 7. Security Considerations

| Concern | Mitigation |
|---------|-----------|
| Path traversal | Only allow basenames (no `/` or `..`); resolve against `$OPENHAB_LOGDIR` |
| Arbitrary file read | Validate file name matches `allowedFiles` glob patterns |
| ReDoS (regex bomb) | Compile patterns with timeout; reject patterns longer than 500 chars |
| Large responses | Hard cap on `lines`/`limit` (max 1000); streaming not needed for typical logs |
| Auth | `@RolesAllowed({ Role.ADMIN })` — only admin users |
| Sensitive data in logs | Logs may contain tokens/passwords — same risk as Karaf console access |

---

## 8. Build & Deployment

> **Status:** ✅ Build verified — compiles and passes all tests with JDK 21.
> BOM not available for openHAB 5.x; explicit dependency versions used instead.

### 8.1 Prerequisites

- **Java:** JDK 21+ (openHAB 5.x core is compiled with Java 21)
- **Maven:** 3.9+
- **JAVA_HOME** must point to JDK 21

### 8.2 Build

```bash
export JAVA_HOME="/path/to/openjdk@21/libexec/openjdk.jdk/Contents/Home"
mvn clean package
```

Output: `target/org.openhab.io.rest.logs-1.0.0-SNAPSHOT.jar`

### 8.3 Deploy

Copy the JAR to the openHAB addons directory:

```bash
cp target/org.openhab.io.rest.logs-1.0.0-SNAPSHOT.jar $OPENHAB_HOME/addons/
```

Karaf auto-deploys the bundle. No restart required.

### 8.4 Verify

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/rest/logs/files
```

### 8.5 Notes

- The openHAB BOM (`org.openhab.core.bom.openhab-core`) is not published for 5.x.
  Dependency versions are pinned explicitly in pom.xml.
- Compiles against openHAB 5.0.0 core APIs — compatible with all 5.x releases.

---

## 9. Testing

> **Status:** ✅ 27 unit tests implemented and passing

### 9.1 Unit tests (implemented)

`src/test/java/org/openhab/io/rest/logs/internal/LogFileServiceTest.java`

| Test | Category |
|------|----------|
| testParseStandardLogLine | Parsing |
| testParseMultiLineEntry | Parsing |
| testParseEmptyLines | Parsing |
| testParseLevelWithTrailingSpace | Parsing |
| testLevelFilteringWarnIncludesError | Filtering |
| testLevelFilteringErrorOnly | Filtering |
| testLevelFilteringUnknownLevelPassesThrough | Filtering |
| testPathTraversalRejected | Security |
| testPathSeparatorRejected | Security |
| testBackslashRejected | Security |
| testDisallowedFileRejected | Security |
| testSymbolicLinkRejected | Security |
| testValidFileAccepted | Security |
| testTailReturnsLastLines | Tail |
| testTailWithLevelFilter | Tail |
| testTailWithLoggerFilter | Tail |
| testTailWithTimeFilter | Tail |
| testSearchByPattern | Search |
| testSearchInvalidRegexRejected | Search |
| testSearchPatternTooLongRejected | Search |
| testTailWithUtf8Content | UTF-8 |
| testListFiles | File listing |
| testConfigClampsInvalidValues | Config |
| testConfigAcceptsStringValues | Config |
| testSearchIncludesRotatedFiles | Rotated files |
| testSearchExcludesGzFiles | Rotated files |
| testTimeFilterWithOffsetTimestamp | Time filtering |

Run tests: `mvn test`

### 9.2 Integration test (manual, after deployment)

```bash
curl -X GET -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs?lines=10&level=ERROR"

curl -X GET -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs/search?pattern=timeout&since=2026-06-09T00:00:00"

curl -X GET -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs/files"
```

### 9.3 Not yet implemented

- REST resource tests (HTTP status mapping, parameter validation)
- Regex timeout behavior with pathological patterns
- Large file performance tests

---

## 10. MCP Server Integration

Once this addon is deployed, the MCP server (`openhab-mcp`) calls it like any REST endpoint:

```python
# openhab_client.py additions

async def get_log_entries(
    self,
    file: str = "openhab.log",
    lines: int = 100,
    level: Optional[str] = None,
    logger: Optional[str] = None,
    since: Optional[str] = None,
    until: Optional[str] = None,
) -> Dict[str, Any]:
    """Get recent log entries from openHAB."""
    params = {"file": file, "lines": lines}
    if level:
        params["level"] = level
    if logger:
        params["logger"] = logger
    if since:
        params["since"] = since
    if until:
        params["until"] = until
    response = await self.client.get("/rest/logs", params=params)
    response.raise_for_status()
    return response.json()

async def search_logs(
    self,
    pattern: str,
    file: str = "openhab.log",
    level: Optional[str] = None,
    logger: Optional[str] = None,
    since: Optional[str] = None,
    until: Optional[str] = None,
    limit: int = 200,
    include_rotated: bool = False,
) -> Dict[str, Any]:
    """Search log entries by regex pattern."""
    params = {"file": file, "pattern": pattern, "limit": limit,
              "includeRotated": str(include_rotated).lower()}
    if level:
        params["level"] = level
    if logger:
        params["logger"] = logger
    if since:
        params["since"] = since
    if until:
        params["until"] = until
    response = await self.client.get("/rest/logs/search", params=params)
    response.raise_for_status()
    return response.json()

async def list_log_files(self) -> Dict[str, Any]:
    """List available log files."""
    response = await self.client.get("/rest/logs/files")
    response.raise_for_status()
    return response.json()
```

MCP tools:
- `openhab_get_log_entries` — tail with filters
- `openhab_search_logs` — regex search with time range
- `openhab_list_log_files` — discover available files

---

## 11. References

- [LoggerResource.java](https://github.com/openhab/openhab-core/blob/main/bundles/org.openhab.core.karaf/src/main/java/org/openhab/core/karaf/internal/LoggerResource.java) — existing `/rest/logging` endpoint (pattern to follow)
- [openHAB Binding Developer Guide](https://www.openhab.org/docs/developer/bindings/) — general addon structure
- [Log Reader Binding source](https://github.com/openhab/openhab-addons/tree/main/bundles/org.openhab.binding.logreader) — log file parsing reference
- [openHAB Logging docs](https://www.openhab.org/docs/administration/logging) — log format, file locations
- [JAX-RS Whiteboard in openHAB](https://github.com/openhab/openhab-core/issues/726) — REST resource registration pattern
- [GitHub Issue #4946](https://github.com/openhab/openhab-core/issues/4946) — `/rest/logging` endpoint behavior reference

---

## 12. Open Questions / Future Enhancements

| Item | Status | Notes |
|------|--------|-------|
| RE2/J for guaranteed linear-time regex | Recommended | Java regex is not reliably interruptible; RE2/J eliminates catastrophic backtracking |
| REST resource unit tests | Next | Test HTTP status mapping, parameter validation |
| Deploy & verify on openHAB 5.x | Next | Integration testing on real instance |
| Stream large results via SSE | Future | For very large searches, consider SSE streaming |
| Index-based search (Lucene) | Future | For production use with large logs, an index would help |
| WebSocket live tail with filters | Future | Extend `/ws/logs` with filter params |
| Log file download | Future | `GET /rest/logs/download?file=openhab.log` |
| Retention / cleanup API | Future | Manage log rotation from REST |
| Support compressed rotated logs (.gz) | Future | Read `.log.1.gz` files |
| MCP server integration | Future | Python client methods for `openhab-mcp` |
