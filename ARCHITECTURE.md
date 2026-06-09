# Architecture: openHAB Log Query REST API Add-on

**Bundle ID:** `org.openhab.io.rest.logs`
**Category:** I/O Extension (REST resource)
**Target Platform:** openHAB 5.0+, Java 17+, OSGi (Apache Karaf)

---

## 1. Purpose

openHAB lacks a REST endpoint to query historical log content. This addon fills that gap by exposing `/rest/logs` — a read-only REST API for searching, filtering, and tailing log file content directly from the filesystem.

### What already exists (and what this addon adds)

| Existing | Does | Does NOT |
|----------|------|----------|
| `/rest/logging` | Get/set logger levels | Read log file content |
| `/ws/logs` | Stream live log events via WebSocket | Search history, filter past events |
| `logreader` binding | Count errors/warnings, trigger on match | Return log lines, search, paginate |

This addon: reads log files, parses structured entries, filters by level/logger/time, supports regex search.

---

## 2. Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                  openHAB Runtime (Karaf/OSGi)                │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  org.openhab.io.rest.logs (this bundle)               │  │
│  │                                                       │  │
│  │  ┌─────────────────────┐    ┌──────────────────────┐  │  │
│  │  │ LogQueryResource    │───▶│ LogFileService       │  │  │
│  │  │ (JAX-RS endpoint)   │    │ (business logic)     │  │  │
│  │  │                     │    │                      │  │  │
│  │  │ GET /rest/logs      │    │ - tail()             │  │  │
│  │  │ GET /rest/logs/     │    │ - search()           │  │  │
│  │  │     search          │    │ - listFiles()        │  │  │
│  │  │ GET /rest/logs/     │    │ - parseLine()        │  │  │
│  │  │     files           │    │ - validateFile()     │  │  │
│  │  └─────────────────────┘    └──────────┬───────────┘  │  │
│  │                                        │              │  │
│  │  ┌─────────────────────┐    ┌──────────┴───────────┐  │  │
│  │  │ LogQueryConfig      │    │ DTOs                 │  │  │
│  │  │ (ConfigAdmin)       │    │ - LogEntry           │  │  │
│  │  │                     │    │ - LogFileInfo        │  │  │
│  │  │ - maxLines          │    │ - LogQueryResult     │  │  │
│  │  │ - maxSearchResults  │    └──────────────────────┘  │  │
│  │  │ - allowedFiles      │                              │  │
│  │  │ - regexTimeoutMs    │                              │  │
│  │  └─────────────────────┘                              │  │
│  └───────────────────────────────────────────────────────┘  │
│                           │ filesystem read                  │
│                           ▼                                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  $OPENHAB_LOGDIR/                                     │  │
│  │    openhab.log, openhab.log.1, events.log, ...        │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Class Responsibilities

### LogQueryResource.java
- **Role:** JAX-RS REST controller
- **Annotations:** `@Component`, `@JaxrsResource`, `@Path("logs")`, `@RolesAllowed(ADMIN)`
- **Responsibilities:**
  - Define REST endpoints with OpenAPI/Swagger annotations
  - Validate request parameters (bounds checking, required fields)
  - Delegate to `LogFileService` for business logic
  - Map exceptions to appropriate HTTP status codes
- **Does NOT:** Read files, parse logs, or contain business logic

### LogFileService.java
- **Role:** OSGi service component providing log file operations
- **Responsibilities:**
  - Resolve log directory path (`$OPENHAB_LOGDIR` or system property `openhab.logdir`)
  - Read log files from tail (efficient reverse reading)
  - Parse openHAB log format into structured `LogEntry` objects
  - Handle multi-line entries (stack traces appended to previous entry)
  - Apply filters: level hierarchy, logger substring, time range
  - Compile and apply regex patterns with timeout protection (ReDoS prevention)
  - Validate file names against allowed patterns (security)
  - List available log files with metadata

### LogEntry.java
- **Role:** Data Transfer Object for a single parsed log entry
- **Fields:** `timestamp`, `level`, `logger`, `thread` (nullable), `message`, `lineNumber` (nullable)

### LogFileInfo.java
- **Role:** DTO for log file metadata
- **Fields:** `name`, `size`, `lastModified`

### LogQueryResult.java
- **Role:** Response wrapper DTO
- **Fields:** `file`, `totalEntries`/`totalMatches`, `pattern` (for search), `entries[]`

### LogQueryConfig.java
- **Role:** Configuration holder, populated via OSGi ConfigAdmin
- **Fields:** `maxLines`, `maxSearchResults`, `allowedFiles`, `regexTimeoutMs`

### LogFileNotFoundException.java
- **Role:** Custom exception for missing log files (maps to HTTP 404)

---

## 4. REST API

### GET /rest/logs — Tail recent entries

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file basename |
| `lines` | int | `100` | Number of entries (max from config) |
| `level` | string | — | Minimum level: ERROR, WARN, INFO, DEBUG, TRACE |
| `logger` | string | — | Logger name substring filter |
| `since` | string | — | ISO 8601 start time |
| `until` | string | — | ISO 8601 end time |

### GET /rest/logs/search — Regex search

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file basename |
| `pattern` | string | *required* | Java regex |
| `level` | string | — | Minimum level filter |
| `logger` | string | — | Logger name substring |
| `since` | string | — | ISO 8601 start time |
| `until` | string | — | ISO 8601 end time |
| `limit` | int | `200` | Max results (max from config) |
| `includeRotated` | boolean | `false` | Search rotated files too |

### GET /rest/logs/files — List log files

Returns file names, sizes, and last-modified timestamps.

### Error Codes

| Status | Condition |
|--------|-----------|
| 400 | Invalid parameters (bad regex, out-of-range values) |
| 403 | Non-admin user (enforced by JAX-RS security) |
| 404 | Requested log file not found |
| 500 | I/O error reading file |

---

## 5. Log Line Parsing

### openHAB default format (log4j2)

```
2026-06-09 12:34:56.789 [ERROR] [org.openhab.binding.zwave.handler] - Node 5: Timeout
    at org.openhab.binding.zwave.internal.ZWaveController.send(ZWaveController.java:234)
    at org.openhab.binding.zwave.internal.ZWaveController.poll(ZWaveController.java:123)
```

### Parsing rules

1. A line matching the timestamp pattern starts a new log entry
2. Lines without a leading timestamp are continuation lines (stack traces)
3. Continuation lines are appended to the previous entry's `message` field

### Regex pattern

```
^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\[(\w+)\s*\]\s+\[([^\]]+)\]\s*-\s*(.*)$
```

Captures: `timestamp`, `level`, `logger`, `message`

---

## 6. Security Model

### 6.1 REST API Authentication & Authorization (inherited from openHAB)

The `/rest/logs` endpoints are **automatically protected** by openHAB's existing REST security infrastructure. No custom auth code is needed.

**How it works — three layers:**

1. **Transport-level:** openHAB's Jetty server handles HTTP/HTTPS. The "API Security" settings (`org.openhab.restauth`) control whether basic auth is enabled and whether implicit user role is granted on LAN. This applies to *all* `/rest/*` endpoints regardless of which bundle provides them.

2. **Authentication:** The `org.openhab.core.io.rest.auth` bundle intercepts all requests to the JAX-RS application. It validates Basic auth credentials, API tokens, and OAuth2 bearer tokens *before* endpoint code runs. Our resource participates in this automatically via:
   ```java
   @JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "="
       + RESTConstants.JAX_RS_NAME + ")")
   ```
   This registers the resource into the same JAX-RS application as all other openHAB REST endpoints (e.g., `/rest/items`, `/rest/things`).

3. **Authorization:** The `@RolesAllowed({ Role.ADMIN })` annotation is processed by openHAB's JAX-RS security filter. Non-admin users receive `403 Forbidden` automatically.

**Access matrix:**

| Caller | Result |
|--------|--------|
| Anonymous LAN user (implicit user role) | `403` — admin-only endpoints |
| Authenticated regular user | `403` — not in admin group |
| Authenticated admin / admin API token | ✓ Access granted |
| No auth configured + implicit user role disabled | `401` |

### 6.2 Application-Level Security

| Threat | Mitigation |
|--------|-----------|
| Path traversal (`../../etc/passwd`) | Only allow basenames — reject any `/` or `..` |
| Arbitrary file read | Validate filename against `allowedFiles` glob config |
| ReDoS (regex denial of service) | Timeout on pattern execution; reject patterns > 500 chars |
| Large response payloads | Hard caps on `lines`/`limit` (configurable max) |
| Sensitive data exposure | Same access level as Karaf console (admin only) |

---

## 7. Configuration

Managed via OSGi ConfigAdmin. Exposed in openHAB UI at Settings → Add-on Settings.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `maxLines` | int | 1000 | Ceiling for `lines` parameter |
| `maxSearchResults` | int | 1000 | Ceiling for `limit` parameter |
| `allowedFiles` | string | `openhab.log*,events.log*` | Comma-separated glob patterns |
| `regexTimeoutMs` | int | 5000 | Regex execution timeout per file |

---

## 8. Deployment

### Build

```bash
mvn clean package
```

### Install

Copy the output JAR to the openHAB addons directory:

```bash
cp target/org.openhab.io.rest.logs-1.0.0-SNAPSHOT.jar $OPENHAB_HOME/addons/
```

Karaf auto-deploys the bundle. No restart required.

### Verify

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/rest/logs/files
```

---

## 9. Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `org.openhab.core` | provided | Core APIs, ConfigConstants |
| `org.openhab.core.io.rest` | provided | RESTResource interface, RESTConstants |
| `jakarta.ws.rs-api` | provided | JAX-RS annotations |
| `swagger-annotations` | provided | OpenAPI documentation |
| `osgi.service.component.annotations` | provided | OSGi DS annotations |
| `osgi.service.jaxrs` | provided | JAX-RS whiteboard |
| `org.eclipse.jdt.annotation` | provided | @NonNullByDefault, @Nullable |
| `slf4j-api` | provided | Logging |

No external runtime dependencies — the bundle uses only APIs already present in the openHAB runtime.

---

## 10. Project Structure

```
org.openhab.io.rest.logs/
├── pom.xml
├── NOTICE
├── ARCHITECTURE.md            ← this file
├── README.md
├── src/main/
│   ├── feature/
│   │   └── feature.xml
│   ├── java/org/openhab/io/rest/logs/internal/
│   │   ├── LogQueryResource.java
│   │   ├── LogFileService.java
│   │   ├── LogQueryConfig.java
│   │   ├── LogEntry.java
│   │   ├── LogFileInfo.java
│   │   ├── LogQueryResult.java
│   │   └── LogFileNotFoundException.java
│   └── resources/OH-INF/
│       ├── addon/
│       │   └── addon.xml
│       └── config/
│           └── logquery.xml
└── src/test/java/org/openhab/io/rest/logs/internal/
    └── LogFileServiceTest.java
```

---

## 11. Future Enhancements

| Feature | Notes |
|---------|-------|
| SSE streaming for large results | For searches returning thousands of entries |
| Lucene-based indexing | Performance for very large log files |
| WebSocket tail with filters | Extend existing `/ws/logs` |
| Log file download | `GET /rest/logs/download?file=...` |
| Compressed rotated logs | Support `.log.1.gz` files |
| Retention management | REST API for log rotation control |
