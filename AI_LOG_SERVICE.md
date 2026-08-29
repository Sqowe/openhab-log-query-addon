# AI rules — Log File Service (Java / OSGi DS)

Scope: `src/main/java/org/openhab/io/rest/logs/internal/LogFileService.java` and
`LogQueryConfig.java` — all filesystem access, log-line parsing, filtering, and the security guards
around them. This is the only place in the bundle allowed to read files. See
[ARCHITECTURE.md](ARCHITECTURE.md) §4.2 for where this sits and §6 for the threat model; this file is
the coding contract. HTTP concerns live in [AI_REST_RESOURCE.md](AI_REST_RESOURCE.md).

## Security invariants (never relax to make something work)

Every one of these guards exists because of a specific attack. If a change requires weakening one,
stop and ask rather than removing it.

- **Path traversal.** `resolveAndValidate` is the single entry point to a `Path`. Reject any name
  containing `/`, `\` or `..` before anything else. Never accept a caller-supplied path, only a basename.
- **Allow-list before existence.** Check the `allowedFiles` globs before touching the filesystem, so a
  rejected name cannot be used to probe for files.
- **Glob matching is literal-safe.** `matchesGlob` translates only `*` and `?`; every other character
  goes through `Pattern.quote`. Never pass a config value straight to `Pattern.compile`.
- **Symlinks.** Reject symbolic links, open files with `LinkOption.NOFOLLOW_LINKS`, and re-verify with
  `toRealPath().startsWith(realLogDir)` after resolution. Both the pre-check and the post-check are
  required — the pair is what closes the TOCTOU window.
- **Containment.** After `resolve`, confirm the normalized path is still under the log directory.
- **Log directory resolution** is `openhab.logdir` system property, else `OpenHAB.getUserDataFolder()/log`.
  Do not add other fallbacks, and never make the directory caller-controlled.

## ReDoS and resource limits

- User regexes run in the bounded `regexExecutor` (`MAX_EXECUTOR_THREADS` / `EXECUTOR_QUEUE_SIZE`) with a
  `config.regexTimeoutMs` timeout, against an `InterruptibleCharSequence` so a runaway match responds to
  interruption. Never call `matcher.find()` on a user pattern on the request thread.
- The search pattern is compiled with `CASE_INSENSITIVE | UNICODE_CASE` unless the caller passes
  `caseSensitive`. Both flags travel together — `CASE_INSENSITIVE` alone is ASCII-only and would silently
  fail to fold accented item and thing labels. Case folding is not a resource-limit relaxation: the length
  bound, the timeout and the interruptible sequence all still apply, so do not add a guard for it.
- Patterns longer than `MAX_REGEX_LENGTH` are rejected with `IllegalArgumentException`. Messages longer
  than `MAX_MESSAGE_LENGTH_FOR_REGEX` are not matched.
- Every scan is bounded: `MAX_TAIL_BYTES` for reverse reads, `MAX_ROTATED_FILES` for rotated-file
  expansion, and a scan window derived from the requested line count. Any new read path needs its own
  explicit bound — no unbounded loop over a file.
- Reading is streaming or tail-first. Never load a whole log file into memory, and never build an
  unbounded `List` of entries: stop at the effective limit.

## Configuration handling

- `LogQueryConfig` is a plain mutable holder with defaults matching `OH-INF/config/logquery.xml`. When a
  default changes, change it in both places (see [AI_BUNDLE.md](AI_BUNDLE.md)).
- The config field is `volatile` and replaced wholesale in `applyConfig`; never mutate the live instance
  in place. Keep `@Activate`, `@Modified` and `@Deactivate` all wired — `@Modified` is what makes UI edits
  take effect without a bundle restart.
- Every incoming value goes through `parseIntConfig` + `clamp` against its `MIN_*`/`MAX_*` constants.
  Bad input is clamped and logged at warn level, never propagated as a crash and never trusted raw.
- Request parameters are clamped against the config ceiling (`maxLines`, `maxSearchResults`); a caller can
  ask for less, never more.
- `deactivate` must shut the executor down. Any new thread pool or resource is released there too.

## Parsing

- `LOG_LINE_PATTERN` and `LOG_TIMESTAMP_FORMAT` are `static final` and compiled once. Never compile a
  constant pattern per call.
- A line matching the timestamp pattern starts an entry; anything else is a continuation appended to the
  previous entry's message (stack traces), capped by the message-length limit. Continuation lines before
  the first parsed entry are dropped, not synthesized into a fake entry.
- Level filtering uses the `LEVEL_PRIORITY` map and is a *minimum-level* comparison, not equality.
  Logger filtering is substring. Time filtering is inclusive on both bounds.
- All three text-matching parameters ignore case by default — `level` upper-cases, `loggerFilter`
  lower-cases, `pattern` compiles with the case-insensitive flags. Keep them consistent: a caller (in
  practice an LLM) that learns lowercase works for one will assume it works for all. A new filter that
  compares text case-sensitively needs an explicit reason.
- A search that matches nothing returns `LogQueryResult.forEmptySearch` with the `buildNoMatchHint` text,
  which states how case was handled and names only the levers still open for that request. Keep the hint
  free of filesystem paths and log content, like every other message leaving this class.
- Unparseable timestamps and malformed lines are skipped or logged, never allowed to abort a whole query.

## Java and style

- `@NonNullByDefault` on every class; `@Nullable` on the parameters and fields that genuinely accept null.
  Do not silence a null warning with a cast or a suppression.
- Fields are `private final` where possible; helpers are `private`, or package-visible only when a test
  needs them (as `resolveAndValidate` is). Do not widen visibility for convenience.
- SLF4J only, one `Logger` per class, parameterized messages (`logger.warn("...{}", x)`), no string
  concatenation. Never log log-file *content* — it can contain the very data the endpoint gates behind
  admin auth.
- Throw `IllegalArgumentException` for bad input, `LogFileNotFoundException` for missing or disallowed
  files, and let `IOException` propagate. No returning empty results to paper over a failure.

## Tests

- `LogFileServiceTest` (JUnit 5) drives the service against `@TempDir` fixtures with `openhab.logdir` set —
  keep it hermetic, no reliance on a real openHAB install.
- Any change to a security guard needs a test that the malicious input is rejected: traversal, symlink,
  disallowed glob, oversized pattern. Any change to parsing needs a fixture with multi-line stack traces.
- Case handling is covered by tests for the lowercase-query default, the `caseSensitive` opt-out, an
  accented fixture proving `UNICODE_CASE` is in effect, and an inline `(?i)` pattern that must keep
  working. Do not drop the accented case — it is the only thing that catches a lone `CASE_INSENSITIVE`.
