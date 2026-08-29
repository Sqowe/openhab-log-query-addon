# AI rules — Bundle & Packaging (Maven / OSGi / Karaf)

Scope: `pom.xml`, `src/main/feature/feature.xml`, `src/main/resources/OH-INF/**` — how the bundle is
built, described to openHAB, and deployed into Karaf. See [ARCHITECTURE.md](ARCHITECTURE.md) §3 and §6
for the deployment picture; this file is the coding contract. Java rules live in
[AI_REST_RESOURCE.md](AI_REST_RESOURCE.md) and [AI_LOG_SERVICE.md](AI_LOG_SERVICE.md).

## Maven

- `pom.xml` is the single source of truth for dependencies and versions. Java release is pinned to **21**
  (`maven.compiler.source/target` plus `<release>21</release>`) because the openHAB 5.x runtime is a Java 21
  JVM — do not raise it to match a newer local JDK. openHAB APIs track `${openhab.version}`.
- **Every openHAB, JAX-RS, OSGi, Swagger, JDT and SLF4J dependency stays `provided`.** They are supplied
  by the runtime. A `compile`-scoped dependency ends up embedded and breaks the bundle in Karaf.
- No new runtime dependencies. The bundle must ship with zero external runtime deps — if a task seems to
  need one, stop and ask. Test-scoped additions are fine.
- Versions are explicit, not ranges. The openHAB 5.x BOM is not published (see the comment in `pom.xml`);
  when it lands, switch to a `dependencyManagement` import rather than hand-bumping.
- Keep Mockito current. Its bundled Byte Buddy must be able to instrument whichever JVM runs the tests; an
  outdated Mockito fails with `Could not modify all classes` on newer JDKs. Bump Mockito in that case —
  do not pin `JAVA_HOME` backwards or lower the build's `release` level.
- `maven-bundle-plugin` config is deliberate: `Import-Package: *`, `Private-Package:
  org.openhab.io.rest.logs.internal.*`. Everything stays private — this bundle exports no API. Do not add
  an `Export-Package`.
- Build and test with `mvn clean package` and `mvn test`. Never commit `target/`.

## Versioning and licence headers

- The version lives in `pom.xml` only; `feature.xml`, `addon.xml` and the JAR name derive from
  `${project.version}`. Never hard-code a version string anywhere else.
- Every Java file starts with the openHAB EPL-2.0 copyright header, and every class carries an `@author`
  Javadoc tag. Copy the header from an existing file verbatim for new files.

## OH-INF descriptors

- `addon/addon.xml`: `id="logquery"`, `<type>misc</type>`, `service-id` = `org.openhab.io.rest.logs`,
  `config-description-ref uri="io:logquery"`. The `service-id` must equal the `configurationPid` on
  `LogFileService`, and the `config-description-ref` must match the URI declared in
  `config/logquery.xml` — a mismatch means the settings page renders empty with no error.
- `config/logquery.xml` defines the UI for the four settings. Each parameter's name, type and default must
  match the corresponding field in `LogQueryConfig` (see [AI_LOG_SERVICE.md](AI_LOG_SERVICE.md)). Adding a
  setting means touching config XML, `LogQueryConfig`, and `applyConfig` together in one change.
- Keep parameter descriptions user-facing and honest about limits — they are what an admin sees.

## Karaf feature

- `feature.xml` declares `openhab-io-rest-logs` depending on `openhab-runtime-base`, with the bundle at
  `start-level="80"`. Do not lower the start level; the JAX-RS whiteboard must be up first.
- Deployment is a JAR drop into `$OPENHAB_HOME/addons/`, hot-deployed by Karaf without a restart. Any
  change that would require a restart or manual Karaf command is a design smell — flag it.
