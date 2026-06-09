# openHAB Log Query REST API Add-on

A standalone openHAB I/O add-on that exposes a read-only REST API (`/rest/logs`) for searching, filtering, and tailing log file content.

## Why This Exists

openHAB has no way to query log history through its REST API. You can stream live events via WebSocket, you can change logger levels — but you can't ask "what errors happened last night?" without SSH-ing into the server and grepping files manually.

This matters when you want to:

- **Let an AI assistant troubleshoot your smart home** — "Why did the lights not turn off last night?" requires reading logs
- **Build dashboards** that surface recent errors without direct server access
- **Automate monitoring** — poll for errors from an external system without filesystem access

### The Gap

| What exists today | What it does | What it can't do |
|-------------------|-------------|------------------|
| `GET /rest/logging` | Get/set logger levels | Read log file content |
| WebSocket `/ws/logs` | Stream live events | Search history, filter past events |
| `logreader` binding | Count errors, trigger rules | Return log lines, search, paginate |

This addon fills that gap with three simple endpoints.

## MCP Integration

This addon is designed to work with [openhab-mcp](https://github.com/Sqowe/openhab-mcp) — a Model Context Protocol server that gives AI assistants (Claude, GPT, etc.) access to your openHAB instance.

With both installed, an AI can:

```
User: "What errors happened in my smart home last night?"

AI → calls: GET /rest/logs/search?pattern=.&level=ERROR&since=2026-06-08T22:00:00&until=2026-06-09T06:00:00

AI: "I found 3 errors last night:
  1. ZWave Node 5 timeout at 23:15 — the motion sensor stopped responding
  2. MQTT connection lost at 01:30 — broker restarted
  3. Rule execution failed at 03:45 — null pointer in lighting automation"
```

Without this addon, the AI has no way to answer questions about past events.

## Installation

### Requirements

- openHAB 5.0+
- Java 21+
- Admin role required for all endpoints

### Build & Deploy

```bash
# Clone and build
git clone https://github.com/Sqowe/openhab-log-query-addon.git
cd openhab-log-query-addon
mvn clean package

# Deploy (no restart needed)
cp target/org.openhab.io.rest.logs-*.jar $OPENHAB_HOME/addons/
```

### Verify

```bash
# Check bundle is active (Karaf console)
bundle:list | grep "Log Query"
# [xxx] [Active] [80] openHAB Add-ons :: I/O :: REST Log Query

# Test endpoint
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/rest/logs/files | jq .
```

## API

### Endpoints

| Endpoint | Description | Best for |
|----------|-------------|----------|
| `GET /rest/logs` | Tail recent log entries | "What's happening now" |
| `GET /rest/logs/search` | Search by regex pattern | Historical queries, time ranges |
| `GET /rest/logs/files` | List available log files | Discovery |

### Which endpoint to use?

- **Recent activity** → `/rest/logs?lines=20`
- **Recent errors** → `/rest/logs?lines=10&level=ERROR`
- **Historical time range** → `/rest/logs/search?pattern=.&level=ERROR&since=...&until=...`
- **Find specific text** → `/rest/logs/search?pattern=timeout|refused`

> **Tip:** For time-range queries ("find errors from last night"), always use `/search` — it scans the full file forward. The tail endpoint reads backward from the end and may not reach far enough into history.

### Examples

**Tail — last 10 entries:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs?lines=10" | jq .
```

**Tail — recent errors only:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs?lines=20&level=ERROR" | jq .
```

**Tail — filter by binding:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs?lines=20&logger=zwave" | jq .
```

**Search — find timeouts in the last 24 hours:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs/search?pattern=timeout&since=2026-06-08T16:00:00&limit=20" | jq .
```

**Search — all errors from last night, including rotated files:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs/search?pattern=.&level=ERROR&since=2026-06-08T22:00:00&until=2026-06-09T06:00:00&includeRotated=true" | jq .
```

**List available log files:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/rest/logs/files" | jq .
```

### Response Format

```json
{
  "file": "openhab.log",
  "totalEntries": 3,
  "entries": [
    {
      "timestamp": "2026-06-09 12:34:56.789",
      "level": "ERROR",
      "logger": "org.openhab.binding.zwave",
      "message": "Node 5: Timeout waiting for response\n  at org.openhab....",
      "lineNumber": 4523
    }
  ]
}
```

### Parameters Reference

#### `GET /rest/logs` (tail)

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file name (basename only) |
| `lines` | int | `100` | Number of entries to return |
| `level` | string | — | Minimum level: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `logger` | string | — | Logger name substring filter (case-insensitive) |
| `since` | string | — | ISO 8601 timestamp — entries after this time |
| `until` | string | — | ISO 8601 timestamp — entries before this time |

#### `GET /rest/logs/search`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `file` | string | `openhab.log` | Log file name |
| `pattern` | string | *required* | Java regex pattern (use `.` to match all) |
| `level` | string | — | Minimum level filter |
| `logger` | string | — | Logger name substring filter |
| `since` | string | — | ISO 8601 start time |
| `until` | string | — | ISO 8601 end time |
| `limit` | int | `200` | Max results to return |
| `includeRotated` | boolean | `false` | Also search rotated files (.log.1, .log.2, ...) |

### Error Responses

| Status | When |
|--------|------|
| `400` | Invalid parameters (bad regex, invalid level, lines < 1) |
| `403` | Non-admin user |
| `404` | Requested log file not found |
| `500` | I/O error reading file |

## Configuration

After installation, configure via openHAB UI: **Settings → Add-on Settings → Log Query REST API**

| Setting | Default | Description |
|---------|---------|-------------|
| Maximum Lines | `1000` | Ceiling for `lines` parameter per request |
| Maximum Search Results | `1000` | Ceiling for `limit` parameter per request |
| Allowed File Patterns | `openhab.log*,events.log*` | Glob patterns for readable files (security) |
| Regex Timeout (ms) | `5000` | Max time for regex search before aborting |

## Security

- All endpoints require **admin role** — enforced by openHAB's REST API security layer
- Same authentication methods as other `/rest/*` endpoints (API token, Basic auth, OAuth2)
- Path traversal protection — only basenames allowed, no `/` or `..`
- Symbolic links rejected — prevents reading files outside the log directory
- Regex timeout protection — prevents denial-of-service from expensive patterns
- File access restricted to configured glob patterns

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for technical details including component diagram, class responsibilities, security model, and deployment notes.

## License

[Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
