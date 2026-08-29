# Architecture Overview

## 1. Purpose of This Document

This is the architectural source of truth for the **openHAB Log Query REST API add-on** — bundle
`org.openhab.io.rest.logs`, an I/O extension for openHAB 5.0+ running on Java 21 and Apache Karaf/OSGi.

openHAB has no REST endpoint for reading historical log content. `/rest/logging` gets and sets logger
levels but returns no lines; the `/ws/logs` WebSocket streams live events but cannot search the past; the
`logreader` binding counts errors and fires rules but never returns log text. This add-on closes that gap
with three read-only endpoints under `/rest/logs` that tail, regex-search, and enumerate log files
straight from the filesystem.

This document describes structure, components, contracts, and stability. It does **not** define coding
rules — see §8.

> Status: **implemented and in use.** — All three endpoints ship, backed by JUnit 5 tests over the service
> and resource layers. Manual verification against a live instance is described in
> [INTEGRATION-TEST.md](INTEGRATION-TEST.md).

## 2. High-Level System Overview

The central design idea: **one OSGi bundle, two layers, one direction of trust.** A JAX-RS resource sits at
the HTTP edge and owns nothing but parameter validation and status-code mapping. A single service owns
every filesystem byte, every compiled regex, and every guard. Nothing else in the bundle may open a file.
The bundle exports no packages and adds no runtime dependencies — it borrows openHAB's own JAX-RS
application, and with it openHAB's authentication and role enforcement.

Config arrives from OSGi ConfigAdmin (surfaced in the openHAB UI) and is hot-reapplied on change. The only
outbound interaction is reading files in `$OPENHAB_LOGDIR`.

```
┌──────────────────────────────────────────────────────────────────┐
│                  openHAB Runtime (Karaf / OSGi)                  │
│                                                                  │
│   HTTP  ──▶ Jetty ──▶ org.openhab.core.io.rest.auth              │
│                        (Basic / API token / OAuth2, @RolesAllowed)│
│                              │ admin only                        │
│  ┌───────────────────────────┼───────────────────────────────┐   │
│  │  org.openhab.io.rest.logs │        (this bundle)          │   │
│  │                           ▼                               │   │
│  │  ┌─────────────────────┐      ┌──────────────────────┐    │   │
│  │  │ LogQueryResource    │─────▶│ LogFileService       │    │   │
│  │  │ JAX-RS edge         │      │ all file access      │    │   │
│  │  │  GET /rest/logs     │      │  tail()   search()   │    │   │
│  │  │  GET  …/search      │      │  listFiles()         │    │   │
│  │  │  GET  …/files       │      │  resolveAndValidate()│    │   │
│  │  │  param checks,      │      │  parse / filter      │    │   │
│  │  │  error → status     │      │  regexExecutor       │    │   │
│  │  └──────────┬──────────┘      └─────┬──────────┬─────┘    │   │
│  │             │ DTOs (Gson)           │          │          │   │
│  │  ┌──────────▼──────────┐      ┌─────▼──────┐   │          │   │
│  │  │ LogEntry            │      │ LogQuery   │◀──┼── Config │   │
│  │  │ LogFileInfo         │      │ Config     │   │   Admin  │   │
│  │  │ LogQueryResult      │      │ (volatile) │   │   (OH-INF│   │
│  │  │ LogFilesResult      │      └────────────┘   │    /config)│ │
│  │  └─────────────────────┘                       │          │   │
│  └────────────────────────────────────────────────┼──────────┘   │
│                                    filesystem read│ (read-only)  │
│   ┌────────────────────────────────────────────────▼───────────┐ │
│   │  $OPENHAB_LOGDIR/  openhab.log, openhab.log.1, events.log  │ │
│   └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

## 3. Repository Structure

```
openhab-log-query-addon/
├── pom.xml                        Maven build, provided-scope deps      → AI_BUNDLE.md
├── CLAUDE.md                      project-global AI behavior + rule index
├── ARCHITECTURE.md                this file        (NOTICE: EPL-2.0 notice)
├── README.md                      user-facing: motivation, MCP integration, examples
├── INTEGRATION-TEST.md            manual verification against a live openHAB
├── openhab-log-query-addon.md     long-form design/development notes
├── docs/chats/                    development-plan conversation (design history)
├── .agents/skills/review-fix-loop/ review loop skill (also .kiro/hooks/)
├── src/main/
│   ├── feature/feature.xml        Karaf feature, start-level 80         → AI_BUNDLE.md
│   ├── java/org/openhab/io/rest/logs/internal/
│   │   ├── LogQueryResource.java     JAX-RS edge (212 lines)            → AI_REST_RESOURCE.md
│   │   ├── LogFileService.java       all file access, parsing (861)     → AI_LOG_SERVICE.md
│   │   ├── LogQueryConfig.java       ConfigAdmin holder                 → AI_LOG_SERVICE.md
│   │   ├── LogEntry.java             DTO: one parsed entry              → AI_REST_RESOURCE.md
│   │   ├── LogFileInfo.java          DTO: file metadata                 → AI_REST_RESOURCE.md
│   │   ├── LogQueryResult.java       DTO: tail/search response          → AI_REST_RESOURCE.md
│   │   ├── LogFilesResult.java       DTO: file-list response            → AI_REST_RESOURCE.md
│   │   └── LogFileNotFoundException.java  → HTTP 404                    → AI_REST_RESOURCE.md
│   └── resources/OH-INF/
│       ├── addon/addon.xml        add-on metadata, service-id           → AI_BUNDLE.md
│       └── config/logquery.xml    settings UI descriptor                → AI_BUNDLE.md
└── src/test/java/org/openhab/io/rest/logs/internal/
    ├── LogFileServiceTest.java    @TempDir fixtures, security guards
    └── LogQueryResourceTest.java  mocked service, status-code mapping
```

Everything lives in the `internal` package and stays private to the bundle — no exported API.

## 4. Core Components

### 4.1 LogQueryResource — the HTTP edge
A JAX-RS resource registered onto openHAB's own JAX-RS application via the OSGi whiteboard
(`@JaxrsResource` + `@JaxrsApplicationSelect` on `RESTConstants.JAX_RS_NAME`), which is what makes
openHAB's auth filter and `@RolesAllowed({ Role.ADMIN })` apply automatically. It owns three things:
cheap parameter validation, delegation to `LogFileService`, and mapping exceptions to status codes
(`LogFileNotFoundException`→404, `IllegalArgumentException`→400, `IOException`→500). It reads no files and
implements no business logic. Its DTOs are serialized by openHAB's Gson, which reflects over **fields**,
so field names are the public JSON contract. Coding rules: [AI_REST_RESOURCE.md](AI_REST_RESOURCE.md).

### 4.2 LogFileService — file access, parsing, and every guard
An OSGi Declarative Services component (`configurationPid = "org.openhab.io.rest.logs"`) and the only
code permitted to touch the filesystem. It resolves the log directory (`openhab.logdir` system property,
else `<userdata>/log`), validates a requested basename through `resolveAndValidate` (traversal rejection,
glob allow-list, symlink refusal, `NOFOLLOW_LINKS` opens, and a `toRealPath` containment re-check), reads
tails by scanning backwards under a byte budget, streams searches, parses lines into `LogEntry` objects
(continuation lines append to the previous entry's message), and applies level/logger/time filters. User
regexes execute on a bounded `ExecutorService` against an `InterruptibleCharSequence` under a
configurable timeout. Config is a `volatile LogQueryConfig` replaced wholesale on `@Activate`/`@Modified`,
with every value clamped to a `MIN_*`/`MAX_*` range. Coding rules:
[AI_LOG_SERVICE.md](AI_LOG_SERVICE.md).

### 4.3 Bundle & packaging
`pom.xml` (Maven, Java 21, `maven-bundle-plugin` with `Private-Package` only and no exports),
`src/main/feature/feature.xml` (Karaf feature `openhab-io-rest-logs`, start-level 80, depends on
`openhab-runtime-base`), and `src/main/resources/OH-INF/` (`addon.xml` metadata whose `service-id` must
equal the service's `configurationPid`; `config/logquery.xml` whose parameters must mirror
`LogQueryConfig` fields and defaults). Deployment is a JAR drop into `$OPENHAB_HOME/addons/`, hot-deployed
by Karaf with no restart. Coding rules: [AI_BUNDLE.md](AI_BUNDLE.md).

### 4.4 External Integrations

- **openHAB core REST + auth** (`org.openhab.core`, `org.openhab.core.io.rest`, provided scope). Supplies
  the JAX-RS application, `RESTResource`, `RESTConstants`, `Role.ADMIN`, and `OpenHAB.getUserDataFolder()`.
  Authentication (Basic, API token, OAuth2 bearer) and role enforcement are entirely inherited — this
  bundle contains no auth code. See §6 for the resulting access matrix.
- **OSGi ConfigAdmin** — settings flow from the openHAB UI (Settings → Add-on Settings) into
  `@Activate`/`@Modified`, so changes apply without a bundle restart.
- **The log4j2 log format** is a de-facto input contract: the parser expects openHAB's default
  `yyyy-MM-dd HH:mm:ss.SSS [LEVEL] [logger] - message` layout. A user who customizes `log4j2.xml` changes
  this contract and parsing degrades to unparsed lines.
- **openhab-mcp** (external, optional) — the MCP server that lets AI assistants query these endpoints. It
  is the primary consumer the API shape is designed for, but nothing here depends on it.
- **No outbound network calls, no database, no message broker, no external runtime dependency.**

## 5. Data Flow & Runtime Model

One process, one bundle, no background work. Every request is handled synchronously on a Jetty worker
thread, except regex matching which is offloaded to a small bounded pool so it can be timed out and
interrupted. There is no cache, no index, and no state between requests — the only mutable state in the
bundle is the `volatile` config reference.

```
client ──▶ Jetty ──▶ auth filter ──▶ @RolesAllowed(ADMIN)
                       │ 401                │ 403
                       ▼                    ▼
              LogQueryResource
                 1. cheap param checks ────────────▶ 400
                 2. delegate
                       │
                       ▼
              LogFileService
                 3. resolveAndValidate(basename) ───▶ 404  (traversal / not allowed /
                       │                                    symlink / missing)
                 4. clamp lines|limit to config ceiling
                 5. read: tail = backward scan under MAX_TAIL_BYTES
                          search = forward stream, optional rotated files (MAX_ROTATED_FILES)
                 6. parse lines → LogEntry (continuations appended to previous message)
                 7. filter: min level, logger substring, since/until
                 8. regex (search only) on regexExecutor + InterruptibleCharSequence
                       │ timeout / bad pattern / too long ─▶ 400
                       ▼
                 9. stop at effective limit → LogQueryResult / LogFilesResult
                       │                                     IOException ──▶ 500
                       ▼
              Gson (openHAB) serializes DTO fields ──▶ 200 application/json
```

Step order matters and is part of the design: validation precedes existence checks (so a rejected name
cannot probe the filesystem), clamping precedes reading (so a caller cannot request an unbounded scan), and
filtering precedes the regex (so the expensive step runs on the smallest possible set).

Config changes take a separate path: ConfigAdmin → `@Modified` → `applyConfig` builds a fresh clamped
`LogQueryConfig` and swaps the `volatile` reference. In-flight requests finish against the old snapshot.

## 6. Configuration & Environment Assumptions

**Runtime:** openHAB 5.0+, Java 21+, Apache Karaf/OSGi. Built with Maven (`mvn clean package`), deployed
by copying the JAR into `$OPENHAB_HOME/addons/`. **Log directory:** the `openhab.logdir` system property
if set and non-blank, otherwise `OpenHAB.getUserDataFolder()/log` — never caller-controlled.

**Settings** (OSGi ConfigAdmin, PID `org.openhab.io.rest.logs`, UI at Settings → Add-on Settings). Each is
clamped on ingest; the request parameters `lines` and `limit` are additionally clamped against these
ceilings:

| Key | Type | Default | Role |
|---|---|---|---|
| `maxLines` | int | 1000 | Ceiling for the `lines` parameter |
| `maxSearchResults` | int | 1000 | Ceiling for the `limit` parameter |
| `allowedFiles` | string | `openhab.log*,events.log*` | Comma-separated globs; the file allow-list |
| `regexTimeoutMs` | int | 5000 | Per-file regex execution timeout |

**Dependencies:** all `provided` — `org.openhab.core`, `org.openhab.core.io.rest`, `javax.ws.rs-api`,
`javax.annotation-api`, `swagger-annotations`, the two OSGi service annotation bundles,
`org.eclipse.jdt.annotation`, `slf4j-api`. Test-only: JUnit 5, Mockito, `jersey-common`. **Zero external
runtime dependencies.** The openHAB 5.x BOM is not published, so versions are pinned explicitly.

**Secrets:** none in the repo — no `.env`, no credential file, no token in source or docs. All credentials
are openHAB's (API tokens, OAuth2), held by the runtime and never seen by this bundle.

### Security model

Authentication and authorization are **inherited, not implemented**: registering into openHAB's JAX-RS
application puts the endpoints behind `org.openhab.core.io.rest.auth` (Basic auth, API tokens, OAuth2
bearer, plus the `org.openhab.restauth` transport settings), and `@RolesAllowed({ Role.ADMIN })` is
enforced by openHAB's security filter before any endpoint code runs.

| Caller | Result |
|---|---|
| Anonymous LAN user (implicit user role) | `403` — endpoints are admin-only |
| Authenticated regular user | `403` — not in the admin group |
| Authenticated admin / admin API token | access granted |
| No auth configured, implicit user role disabled | `401` |

Application-level threats and the mitigations that must never be weakened (enforcement details in
[AI_LOG_SERVICE.md](AI_LOG_SERVICE.md)):

| Threat | Mitigation |
|---|---|
| Path traversal (`../../etc/passwd`) | Basenames only; reject `/`, `\`, `..`; normalized-path containment check |
| Symlink escape / TOCTOU | Symlink refusal, `LinkOption.NOFOLLOW_LINKS`, `toRealPath` re-verification |
| Arbitrary file read | `allowedFiles` glob allow-list, checked before the filesystem is touched |
| ReDoS | Bounded executor + `regexTimeoutMs` + `InterruptibleCharSequence`; pattern length cap |
| Memory exhaustion / huge payloads | `MAX_TAIL_BYTES` scan budget, `MAX_ROTATED_FILES`, clamped `lines`/`limit`, message-length caps |
| Sensitive data exposure | Admin-only, same trust level as the Karaf console; log content is never echoed into the bundle's own logs |
| Error-message leakage | Errors are `{"error": ...}` with escaped messages; no stack traces or absolute paths returned |

## 7. Stability Zones

- **✅ Stable** — the **REST contract**: the three paths, their query parameters, the `operationId` values,
  and the DTO **field names** that Gson turns into JSON keys. External consumers (openhab-mcp, dashboards,
  scripts) are coupled to these; renaming a field is a breaking change with no deprecation path. Also
  stable: the security guards in `resolveAndValidate` and the `provided`-only dependency policy.
- **🔄 Semi-stable** — `LogFileService` internals: the reverse-tail reader, the streaming search, the
  scan-window heuristics and the `MIN_*`/`MAX_*` constants. Free to change for performance as long as the
  guards and the observable results hold. Also the config keys — additive changes are fine, renames are not.
- **⚠️ Experimental** — log-line parsing beyond openHAB's default log4j2 layout. The single
  `LOG_LINE_PATTERN` plus the "no timestamp means continuation" rule handles the stock format and stack
  traces; custom layouts, non-default timestamps and multi-line message bodies degrade to unparsed text.
  Rotated-file handling (`includeRotated`) is likewise best-effort and skips compressed `.gz` files.
- **🔮 Planned** — none of these exist: SSE streaming for large result sets, Lucene-backed indexing for very
  large files, filtered WebSocket tail on top of `/ws/logs`, raw log download, `.log.N.gz` support, and
  retention/rotation control. Treat each as a new component rather than a patch to `LogFileService`.

## 8. AI Coding Rules and Behavioral Contracts

AI-assisted development in this project is governed by dedicated rule files.

**This document (ARCHITECTURE.md) does NOT redefine coding rules.**

All AI coders MUST:

- Locate and read the relevant rule files before making any changes.
- Apply those rules strictly and consistently.
- Resolve conflicts conservatively, or stop and escalate as an open question.

### Authoritative rule files

- [CLAUDE.md](CLAUDE.md) — project-global behavioral rules (confirm-before-action, never
  stage/commit unprompted, what to read first). *Fills the global `AI.md` role for this repo.*
- [AI_REST_RESOURCE.md](AI_REST_RESOURCE.md) — `LogQueryResource.java` and the response DTOs: JAX-RS on the
  OSGi whiteboard, parameter validation, error mapping, the Gson field-name contract (Java).
- [AI_LOG_SERVICE.md](AI_LOG_SERVICE.md) — `LogFileService.java` and `LogQueryConfig.java`: filesystem
  access, security guards, parsing, filtering, config clamping (Java / OSGi DS).
- [AI_BUNDLE.md](AI_BUNDLE.md) — `pom.xml`, `src/main/feature/`, `src/main/resources/OH-INF/`: build,
  bundle manifest, config descriptors, Karaf deployment (Maven / OSGi).

### Rule precedence (highest → lowest)

1. Explicit instructions from the user in the current task.
2. Stack-specific `AI_*.md` for the code being touched.
3. Project-global behavioral rules ([CLAUDE.md](CLAUDE.md)).
4. This `ARCHITECTURE.md` (architecture constraints only).
5. Implicit conventions inferred from the codebase.

If any rule conflicts or ambiguity is detected, **stop and ask for clarification.**

## 9. Quick Start for AI Assistants

**Read order:** [CLAUDE.md](CLAUDE.md) → this file §2 and §4 → the one `AI_*.md` covering the file you are
about to touch. [README.md](README.md) explains why the add-on exists and how openhab-mcp consumes it;
`docs/chats/` holds the design conversation.

**Orient in 60 seconds:** two classes carry the system. `LogQueryResource` (212 lines) is the HTTP edge —
annotations, validation, status codes. `LogFileService` (861 lines) is everything else — file access,
parsing, filtering, guards. The rest are DTOs and one exception.

**The two invariants never to break:**

1. **Only `LogFileService` touches the filesystem, and only through `resolveAndValidate`.** Every guard in
   §6 lives on that path. Do not add a second way to open a file, and do not relax a guard to make a
   feature or a test work.
2. **DTO field names are the public JSON contract.** Gson serializes fields, not getters. A rename breaks
   every client silently — no compiler error, no test failure unless a test asserts the key.

**Where the stable contract lives:** the query parameters and `operationId`s in `LogQueryResource`, and the
field declarations in `LogEntry`, `LogFileInfo`, `LogQueryResult`, `LogFilesResult`.

**Gates:** `mvn test` (49 JUnit 5 tests, hermetic — `@TempDir` plus the `openhab.logdir` property) and
`mvn clean package` for the deployable bundle. Run both on a **JDK 21** toolchain; on a newer JDK Mockito's
inline mock maker cannot instrument `LogFileService` and the resource tests error out. There is no linter or
formatter; match the surrounding openHAB style by hand (EPL-2.0 header, `@author` tag, `@NonNullByDefault`).
For live verification follow [INTEGRATION-TEST.md](INTEGRATION-TEST.md); an autonomous review loop is
available in [.agents/skills/review-fix-loop/SKILL.md](.agents/skills/review-fix-loop/SKILL.md).
