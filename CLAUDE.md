# openHAB Log Query REST API Add-on

A standalone openHAB I/O add-on (`org.openhab.io.rest.logs`) that exposes a read-only REST API at
`/rest/logs` for tailing, filtering, and regex-searching openHAB log file content. It fills a real gap:
openHAB can stream live log events and change logger levels, but has no way to ask "what errors happened
last night?" over REST. The defining characteristic is that it reads the log directory directly from an
admin-only endpoint, so every filesystem and regex path is hardened against traversal, symlink and ReDoS
abuse.

> Status: **implemented and in use.** — Three endpoints (tail, search, list files) run in an OSGi bundle
> with 689 lines of JUnit 5 tests over the service and resource layers; security guards and config
> clamping are covered by tests.
> [ARCHITECTURE.md](ARCHITECTURE.md) is the authoritative design source; [INTEGRATION-TEST.md](INTEGRATION-TEST.md)
> covers manual verification against a live openHAB instance.
>
> Build & test: `mvn clean package`, `mvn test`.

## Read before making changes

1. [ARCHITECTURE.md](ARCHITECTURE.md) — system structure, components, stability zones.
2. The relevant `AI_*.md` file(s) for the code you are touching — coding rules (see below).
3. [README.md](README.md) — the motivation, the MCP integration story, and the user-facing API examples.
4. [docs/chats/](docs/chats/) — the development-plan conversation behind the current design.

## Coding rules live in `AI_*.md` (do not duplicate them here or in ARCHITECTURE.md)

| File | Scope |
| --- | --- |
| [AI_REST_RESOURCE.md](AI_REST_RESOURCE.md) | `LogQueryResource.java` + response DTOs — JAX-RS on the OSGi whiteboard, parameter validation, error mapping, the Gson field-name contract (Java) |
| [AI_LOG_SERVICE.md](AI_LOG_SERVICE.md) | `LogFileService.java`, `LogQueryConfig.java` — filesystem access, security guards, log parsing, filtering, config clamping (Java / OSGi DS) |
| [AI_BUNDLE.md](AI_BUNDLE.md) | `pom.xml`, `src/main/feature/`, `src/main/resources/OH-INF/` — build, bundle manifest, config descriptors, Karaf deployment (Maven / OSGi) |

`ARCHITECTURE.md` and the `AI_*.md` files must not redefine or duplicate each other's content.

## Working agreement

- **Confirm before acting.** Never create, edit, or delete files, run state-changing commands,
  or write to external systems without explicit user approval. First explain the situation,
  propose specifics (which files, what changes, what commands), then wait for a clear "yes."
  Read-only work (reading, searching, analyzing, answering) needs no confirmation.
  Exception: if the user says "just do it" / "go ahead," proceed directly.
- **Don't commit unprompted.** Run `git add` / `git commit` / `git push` only when the user
  explicitly asks — never as an unrequested side-effect of another task.
- **Never weaken a security guard or a resource limit to make something work.** This endpoint hands log
  file content to whoever gets past it; the path-traversal, symlink, allow-list, regex-timeout and scan
  bounds are the product. If a change appears to need one relaxed, stop and ask instead of loosening it,
  and never delete or skip the test that proves it.
- **Stop and ask** if anything is unclear or contradictory.

## Tooling

- **Java 21** is the build and runtime target, pinned via `maven.compiler.source/target` and
  `<release>21</release>` in `pom.xml`. openHAB 5.x requires a Java 21 JVM (Java 25+ is not yet
  supported by the runtime), so never raise `release` to match a newer local JDK. Building on a newer
  JDK is harmless — `release 21` cross-compiles correctly — and the test suite passes on both 21 and 26.
- **Maven** is the build and dependency manager: `mvn clean package` produces the deployable OSGi bundle,
  `mvn test` runs the JUnit 5 suite through surefire. No wrapper script — use `mvn` directly.
- **Target runtime:** openHAB 5.0+ on Apache Karaf/OSGi. All openHAB, JAX-RS, OSGi and SLF4J dependencies
  are `provided`; the bundle has zero external runtime dependencies.
- **Tests:** JUnit 5 + Mockito 5.20, with `jersey-common` test-scoped so `Response.status()` resolves a
  `RuntimeDelegate`. Tests are hermetic — `@TempDir` fixtures plus the `openhab.logdir` system property,
  never a real openHAB install. Mockito must stay recent enough for its bundled Byte Buddy to instrument
  the running JVM; if mocks start failing with `Could not modify all classes` on a newer JDK, bump
  Mockito rather than pinning `JAVA_HOME` backwards or reworking the tests to avoid mocking.
- **Quality gate:** `mvn test` is the only automated gate. There is no formatter, linter, or static
  analysis plugin configured — match the surrounding style by hand (openHAB conventions: EPL-2.0 header,
  `@author` tag, `@NonNullByDefault`).
- **No secrets in this repo.** There is no `.env` and no credentials file; auth is entirely delegated to
  openHAB's REST security layer. Never add a hard-coded token, and keep real tokens out of docs and
  examples.
- **No CI, no Docker, no Node/npm are part of this project.** Verification is local `mvn` plus the manual
  steps in [INTEGRATION-TEST.md](INTEGRATION-TEST.md).
- **Review loop:** [.agents/skills/review-fix-loop/SKILL.md](.agents/skills/review-fix-loop/SKILL.md)
  (also wired as the Kiro hook `.kiro/hooks/review-fix-loop.kiro.hook`).
