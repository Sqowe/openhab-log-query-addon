# AI rules — REST Resource (Java / JAX-RS on OSGi)

Scope: `src/main/java/org/openhab/io/rest/logs/internal/LogQueryResource.java` and the response DTOs
(`LogEntry`, `LogFileInfo`, `LogQueryResult`, `LogFilesResult`, `LogFileNotFoundException`) — the HTTP
edge of the bundle. It validates parameters, delegates, and maps exceptions to status codes; it never
touches the filesystem. See [ARCHITECTURE.md](ARCHITECTURE.md) §4.1 for where this sits; this file is
the coding contract. File reading, parsing and filtering rules live in
[AI_LOG_SERVICE.md](AI_LOG_SERVICE.md); packaging and config descriptions in
[AI_BUNDLE.md](AI_BUNDLE.md).

## Resource registration (do not change without reading ARCHITECTURE §6)

- The class-level annotation stack is load-bearing. Keep all of `@Component`, `@JaxrsResource`,
  `@JaxrsName("logs")`, `@JaxrsApplicationSelect(... RESTConstants.JAX_RS_NAME ...)`, `@JSONRequired`,
  `@Path("logs")`, `@RolesAllowed({ Role.ADMIN })`, `@SecurityRequirement`, `@Tag`, `@NonNullByDefault`.
  Dropping `@JaxrsApplicationSelect` silently detaches the resource from openHAB's authenticated JAX-RS
  application; dropping `@RolesAllowed` makes admin-only log content readable by any LAN user.
- Implement `RESTResource`. Inject collaborators through the `@Activate` constructor with `@Reference` —
  no field injection, no service lookups inside methods.
- Never add authentication, token parsing, or role checks by hand. Authorization is declarative only.

## Endpoint methods

- One method per endpoint, returning `Response`. `@Produces(MediaType.APPLICATION_JSON)` on every one.
- Every query parameter is `@QueryParam`, with `@DefaultValue` for optional ones and `@Parameter` for
  the OpenAPI description. Keep the description text in sync with the real limits.
- Every method carries `@Operation` with a stable `operationId` and an `@ApiResponse` for each status
  code it can actually return. Generated clients depend on `operationId`; treat it as public API.
- Validate cheap preconditions here (required parameters present, `lines >= 1`, `limit >= 1`) and return
  `400` immediately. Deeper validation (ceilings, glob allow-list, regex length) belongs to the service.
- No business logic in this class: no file access, no parsing, no filtering. Delegate to `LogFileService`.

## Error mapping

- Map exceptions to exactly these codes, and no others:
  `LogFileNotFoundException → 404`, `IllegalArgumentException → 400`, `IOException → 500`.
- Never let an exception escape a resource method — an uncaught throwable leaks a stack trace to the
  client. Catch the three above explicitly; if a new failure mode appears, add an explicit mapping.
- Error bodies are `{"error": "..."}` with the message passed through `escapeJson`. Never interpolate a
  raw message into JSON, and never include absolute filesystem paths or the log directory in an error.

## DTO contract (Gson serializes fields, not getters)

- openHAB serializes with Gson reflecting over **fields**. A field name *is* the JSON key — renaming one
  breaks every client. Getters are for internal callers and tests only.
- DTOs are immutable value holders: `final` fields set in the constructor, no setters. `LogEntry.message`
  is the one exception (continuation lines append to it during parsing) and stays package-visible in
  intent — do not widen it.
- Fields that must not reach the client (parse bookkeeping such as `messageLength`, size constants) are
  `transient`.
- Nullable fields are annotated `@Nullable` under `@NonNullByDefault`; absent values stay `null` so Gson
  omits them rather than emitting a placeholder.

## Tests

- `LogQueryResourceTest` uses JUnit 5 + Mockito with a mocked `LogFileService`; `jersey-common` is on the
  test classpath only so `Response.status()` has a `RuntimeDelegate`. Keep the resource tests free of
  filesystem access.
- Every new endpoint or status-code mapping needs a test asserting the status **and** that the body is the
  `{"error": ...}` shape. Assert the JSON field names of DTOs explicitly so a rename fails a test.
