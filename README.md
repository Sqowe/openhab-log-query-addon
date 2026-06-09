# openHAB Log Query REST API Add-on

A standalone openHAB I/O add-on that exposes a read-only REST API (`/rest/logs`) for searching, filtering, and tailing log file content.

## Features

- **Tail logs** — get the most recent N entries with optional level/logger/time filters
- **Search logs** — regex pattern matching across log files with time range support
- **List files** — discover available log files and their metadata

## Installation

1. Build: `mvn clean package`
2. Copy `target/org.openhab.io.rest.logs-1.0.0-SNAPSHOT.jar` to `$OPENHAB_HOME/addons/`
3. The addon auto-deploys and registers the `/rest/logs` endpoints

## Requirements

- openHAB 5.0+
- Java 21+
- Admin role required for all endpoints

## API Endpoints

| Endpoint | Description | Best for |
|----------|-------------|----------|
| `GET /rest/logs` | Tail recent log entries | "What's happening now" |
| `GET /rest/logs/search` | Search by regex pattern | Historical queries, time ranges |
| `GET /rest/logs/files` | List available log files | Discovery |

**Tip:** For time-range queries ("find errors from last night"), use `/rest/logs/search?pattern=.&level=ERROR&since=...&until=...` — it scans the full file. The tail endpoint only reads backward from the end.

See [ARCHITECTURE.md](ARCHITECTURE.md) for full technical details.

## License

Eclipse Public License 2.0
