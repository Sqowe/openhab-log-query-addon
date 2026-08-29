# Changelog

All notable changes to the openHAB Log Query REST API add-on are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Versions refer to the
OSGi bundle version in `pom.xml`; the Karaf feature inherits it via `${project.version}`.

## [1.1.0] - 2026-08-29

Search patterns now ignore letter case by default. This is the headline change: it makes the
`pattern` parameter behave like the `level` and `logger` filters, which have always been
case-insensitive.

### Added

- `caseSensitive` query parameter on `GET /rest/logs/search` (default `false`). Set it to `true`
  to require exact letter case, for example to tell the word `ERROR` in a message apart from
  `Error`.
- `hint` field on the search response, present only when `totalEntries` is `0`. It states how the
  query was interpreted and which levers are still open — widening `since`/`until`, setting
  `includeRotated=true`, or broadening the pattern. Absent when there are matches, so successful
  responses are unchanged.
- `LogFileService.search(...)` overload taking a `caseSensitive` flag. The previous 8-argument
  signature is retained and delegates with `false`, so existing callers still compile.

### Changed

- **`pattern` is matched case-insensitively unless `caseSensitive=true`.** Previously
  `pattern=unifi` returned nothing for the log line
  `Thing with label UniFi Controller is OFFLINE`, while `logger=zwave` and `level=error` both
  worked in lowercase. The inconsistency made the endpoint unreliable for callers that cannot know
  a thing label's capitalisation in advance — LLM clients in particular.
- Case folding is Unicode-aware (`CASE_INSENSITIVE | UNICODE_CASE`), so accented item and thing
  labels fold correctly. `CASE_INSENSITIVE` alone is ASCII-only and would have silently failed on
  names such as `Kőrösbánya`.
- OpenAPI `@Parameter` descriptions for `pattern` and `caseSensitive` now spell out the matching
  behaviour, since that text is the only thing an API client sees before calling.

### Notes

- Inline regex flags keep working and take precedence over the compile-time flags: `(?i)` is now
  redundant but harmless, and `(?-i)` re-enables case sensitivity for a single pattern without the
  `caseSensitive` parameter.
- Security guards and resource limits are untouched. `MAX_REGEX_LENGTH`, the bounded
  `regexExecutor`, `InterruptibleCharSequence` and `regexTimeoutMs` all still apply; case folding
  introduces no additional backtracking risk.
- Callers that relied on case-sensitive matching will see more results than before. Pass
  `caseSensitive=true` to restore the previous behaviour exactly.

### Tests

- 62 tests pass, up from 47. New coverage: the lowercase-query default, the `caseSensitive`
  opt-out, an accented fixture that fails if `UNICODE_CASE` is ever dropped, inline `(?i)` and
  `(?-i)`, and each branch of the no-match hint builder.

## [1.0.2] - 2026-06-09

Never deployed as a release; shipped as part of 1.1.0.

### Fixed

- Tail requests with a `level` or `logger` filter now scan backward progressively until enough
  matching entries are found, instead of filtering only the last N lines. Previously a query such
  as `?lines=10&level=ERROR` could return fewer entries than requested — or none — when the most
  recent lines held no matches.
- Gson serialized the internal `messageBuilder` field instead of `message`, so clients received a
  `messageBuilder` key in every log entry. `LogEntry` now holds a plain `String message` with the
  parse bookkeeping (`messageLength`) marked `transient`.

### Added

- Endpoint usage guidance in the README: tail for recent activity, `/search` for time-range
  historical queries, because tail reads backward from the end of the file and may not reach far
  enough into history.

## [1.0.0] - 2026-06-09

Initial implementation.

### Added

- `GET /rest/logs` — tail recent log entries, with `file`, `lines`, `level`, `logger`, `since` and
  `until` filters. `level` is a minimum-level comparison, `logger` a case-insensitive substring
  match, and time bounds are inclusive.
- `GET /rest/logs/search` — regex search over log content, with time-range filtering and optional
  rotated-file expansion via `includeRotated`.
- `GET /rest/logs/files` — list readable log files with size and last-modified metadata.
- Multi-line entry parsing: a line matching the timestamp pattern starts an entry, anything else
  is appended to the previous entry's message, so stack traces stay intact.
- OSGi configuration via `OH-INF/config/logquery.xml`: `maxLines`, `maxSearchResults`,
  `allowedFiles` and `regexTimeoutMs`, all clamped against internal bounds and applied without a
  bundle restart.
- Karaf feature definition for deployment as an openHAB add-on.

### Security

- Admin role required on every endpoint, enforced declaratively through openHAB's authenticated
  JAX-RS application.
- Path traversal rejected: only basenames are accepted, never a caller-supplied path.
- Allow-list glob check runs before any filesystem access, so a rejected name cannot be used to
  probe for files. Glob translation quotes every character except `*` and `?`.
- Symlinks refused, files opened with `LinkOption.NOFOLLOW_LINKS`, and the resolved path
  re-verified against the real log directory to close the TOCTOU window.
- ReDoS bounded: user patterns are length-capped, run on a bounded executor with a timeout, and
  matched against an interruptible character sequence so a runaway match responds to cancellation.
- Every scan is bounded — a byte budget for reverse reads and a cap on rotated-file expansion.

[1.1.0]: https://gitlab.obodnikov.com/mike/openhab-log-query-addon/-/tags/v1.1.0
[1.0.2]: https://gitlab.obodnikov.com/mike/openhab-log-query-addon/-/commit/f8b0cf9
[1.0.0]: https://gitlab.obodnikov.com/mike/openhab-log-query-addon/-/commit/97af3c3
