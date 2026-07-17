# Idiomatic OTel logging for a Java library: direct Logs API vs. SLF4J bridging

Research for [pgenie-io/java.gen-design#9](https://github.com/pgenie-io/java.gen-design/issues/9), a child of the observability wayfinder map ([#6](https://github.com/pgenie-io/java.gen-design/issues/6)).

Researched 2026-07-10 against primary sources only: the rendered spec at `opentelemetry.io/docs/specs/otel/logs/*` and `opentelemetry.io/docs/specs/status/`, the OpenTelemetry blog (`opentelemetry.io/blog/2026/deprecating-span-events/`), the `open-telemetry/opentelemetry-java` GitHub repo (source of `io.opentelemetry.api.logs`, `io.opentelemetry.api.incubator.logs`, `sdk/logs`, and the top-level `README.md` artifact table, all fetched at `main` tip), Maven Central metadata for `io.opentelemetry:opentelemetry-api`, `io.opentelemetry:opentelemetry-api-incubator`, and `io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17`, the `open-telemetry/opentelemetry-java-instrumentation` GitHub repo (`opentelemetry-jdbc`/`instrumentation-api` source, the Log4j2 appender module and README, and `docs/logger-mdc-instrumentation.md`), and this repo's own `music-catalogue/` source (`music-catalogue/pom.xml`, `MusicCatalogueSession.java`, `StatementExecutor.java`) at commit `822f84c` (branch `more-features`). This is a sibling to [`docs/research/otel-instrumentation-survey.md`](./otel-instrumentation-survey.md) and [`docs/research/db-client-semconv-audit.md`](./db-client-semconv-audit.md); it does not re-litigate spans/metrics naming, only logging.

## 1. Summary

The OpenTelemetry Java Logs API (`io.opentelemetry.api.logs.{Logger,LoggerProvider,LogRecordBuilder}`, shipped inside the already-stable `opentelemetry-api` artifact since v1.27.0) is marked **Stable** in the spec, but its own Javadoc and the project's own `status.md` both say, in almost identical words, that it exists **"to enable the creation of log appenders"** and **"is NOT a replacement log framework"** / **"is NOT meant to be called directly by end users."** There is no separate, graduated "direct Logs API for libraries" distinct from the bridge API — it is the same interface, and the project explicitly discourages application/library authors from calling it directly, even though the Javadoc on `Logger.logRecordBuilder()` also concedes it "can... be directly called by instrumentation libraries." A genuinely separate **Events API** does exist by the project's own naming (`opentelemetry-api-incubator`'s module description literally reads *"API incubator, including pass through propagator, and extended tracer, and Event API"*), but it has published only as `-alpha` for every one of its 31 releases (`1.37.0-alpha` through the current `1.63.0-alpha`) and has never had a stable release; a light-weight sliver of event support (`setEventName`, `setException`) has migrated into the stable base `LogRecordBuilder` interface as of v1.50.0/v1.60.0 respectively, as default (no-op-by-default) methods. Crucially, OpenTelemetry's own Java instrumentation libraries **practice what the spec preaches**: `opentelemetry-jdbc` and the shared `instrumentation-api` framework it's built on contain zero calls to any logging framework at all — no SLF4J, no JUL, no OTel Logs API — they emit spans and metrics only, confirming the "libraries emit telemetry, applications own logging plumbing" split by omission as much as by documentation. Trace/span correlation on log records is automatic and requires no manual work in the common case: both the raw SDK (`SdkLogRecordBuilder.emit()`) and the Log4j2 library appender (`OpenTelemetryAppender.getContext()`) fall back to `Context.current()` when `setContext(...)` isn't called explicitly, so a log emitted on the same thread as an active span picks up its `trace_id`/`span_id` for free — the only wrinkle is async/cross-thread logging, which needs an explicit context-data injector. Given all of this, and that `music-catalogue`'s five SLF4J call sites (slow-query warning, session open/close lifecycle, redacted-URL logging, pool-drain-timeout warning) are exactly the kind of "diagnostic/operational log a human reads," not structured telemetry an ingestion pipeline consumes — **the recommendation is to keep SLF4J and let consuming applications bridge it**, not to switch to the OTel Logs API directly. That mirrors what OTel's own library instrumentations do (nothing — no direct logging at all, from either framework), avoids hard-depending on the still-alpha Events-API surface, and avoids taking on the OTel Logs API's own stated audience mismatch (appenders, not application/library log statements).

## 2. Maturity/stability of the OTel Logs API and Events API in Java

### 2.1 Spec-level stability

The rendered [Logs API spec](https://opentelemetry.io/docs/specs/otel/logs/api/) carries the document-level status **"Status: Stable, except where otherwise specified"**. Its purpose statement:

> "The Logs API is provided for logging library authors to build log appenders, which use this API to bridge between existing logging libraries and the OpenTelemetry log data model."

immediately followed by:

> "The Logs API can also be directly called by instrumentation libraries as well as instrumented libraries or applications."
>
> "However, languages are also free to provide a more ergonomic API for direct usage."

The spec's own document-wide "Ergonomic API" section is separately marked **Status: Development** — an acknowledgment that the base Logs API is not meant to be pleasant for direct application use, and that a nicer wrapper is an open, unstable design space, not something the project has settled on for Java.

The [project's own status summary](https://opentelemetry.io/docs/specs/status/), which functions as the higher-level, authoritative cross-language answer (not the per-doc spec text), is unambiguous about the intended caller and is the more recent/current framing to trust here:

> "The OpenTelemetry Log Bridge API allows for writing appenders which bridge logs from existing log frameworks into OpenTelemetry. **The Logs Bridge API is not meant to be called directly by end users.** Log appenders are under development in many languages."
>
> "The OpenTelemetry Log SDK is the standard implementation of the Log Bridge API. Applications configure the SDK to indicate how logs are processed and exported (e.g. using OTLP)."
>
> "The OpenTelemetry Log Bridge API contains experimental support for emitting log records which conform to the [event semantic conventions](https://opentelemetry.io/docs/specs/semconv/general/events/)."

— [`content/en/docs/specs/status.md`](https://raw.githubusercontent.com/open-telemetry/opentelemetry.io/main/content/en/docs/specs/status.md), "Logging" section. Both this table's "Bridge API" row and the standalone spec doc's own status line resolve to Stable — the *shape* of the API (LoggerProvider → Logger → LogRecordBuilder → emit) is a stable, non-breaking contract. The tension is entirely about **audience**, not stability: "stable" here means "safe for appender authors to build against," not "recommended for library authors to call directly to replace SLF4J."

There is a genuine, notable inconsistency between the two primary sources on that audience question — the `api/logs` module's own Javadoc goes further and flat-out says libraries/applications should *not* call it directly (§2.2 below), while the spec doc's prose grants that it "can" be called directly by instrumented libraries. This document treats the stronger, more specific, more recently-emphasized claim (status.md's "not meant to be called directly by end users," matching the Java source's own Javadoc verbatim) as the operative guidance, since it's consistent across both the cross-language status page and the actual Java implementation source (§2.2), whereas the spec-doc's permissive aside is the outlier.

### 2.2 Java implementation: what's actually stable, verbatim from source

`opentelemetry-java`'s own top-level [`README.md`](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java/main/README.md) artifact table (current tip, versions pinned to `1.63.0`/`1.63.0-alpha` as of this writing) states:

| Component | Artifact ID | Version | Description (verbatim) |
|---|---|---|---|
| API | `opentelemetry-api` | `1.63.0` (**stable**) | "OpenTelemetry API, including metrics, traces, baggage, context" |
| API Incubator | `opentelemetry-api-incubator` | `1.63.0-alpha` (**unstable**) | "API incubator, including pass through propagator, and extended tracer, **and Event API**" |
| Log SDK | `opentelemetry-sdk-logs` | `1.63.0` (**stable**) | "OpenTelemetry log SDK" |

This is the most concrete confirmation available that OpenTelemetry itself names a distinct **"Event API"**, and that by the project's own module table it lives entirely inside the alpha/incubator artifact, not the stable one. Maven Central confirms `opentelemetry-api-incubator` has published **exclusively as `-alpha`** across all 31 releases on record, from `1.37.0-alpha` through the current `1.63.0-alpha` (`https://repo1.maven.org/maven2/io/opentelemetry/opentelemetry-api-incubator/maven-metadata.xml`) — there has never been a GA release of this module.

Inside the stable `opentelemetry-api` artifact, the base Logs API lives in package `io.opentelemetry.api.logs` ([`api/all/src/main/java/io/opentelemetry/api/logs/`](https://github.com/open-telemetry/opentelemetry-java/tree/main/api/all/src/main/java/io/opentelemetry/api/logs)): `Logger`, `LoggerProvider`, `LogRecordBuilder`, `Severity`, plus `DefaultLogger`/`DefaultLoggerProvider` no-op implementations. `Logger` has been present `@since 1.27.0`. Its Javadoc is unambiguous and is the single strongest, most specific primary-source statement on audience found in this research:

```java
/**
 * A {@link Logger} is the entry point into a log pipeline.
 * ...
 * <p>The OpenTelemetry logs bridge API exists to enable bridging logs from other log frameworks
 * (e.g. SLF4J, Log4j, JUL, Logback, etc) into OpenTelemetry and is <b>NOT</b> a replacement log
 * API.
 */
```
— [`Logger.java`](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java/main/api/all/src/main/java/io/opentelemetry/api/logs/Logger.java)

and on the entry-point method itself:

```java
  /**
   * Return a {@link LogRecordBuilder} to emit a log record.
   *
   * <p><b>IMPORTANT:</b> this should be used to write appenders to bridge logs from logging
   * frameworks (e.g. SLF4J, Log4j, JUL, Logback, etc). It is <b>NOT</b> a replacement for an
   * application logging framework, and should not be used by application developers.
   */
  LogRecordBuilder logRecordBuilder();
```
— same file. The package-level Javadoc in [`package-info.java`](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java/main/api/all/src/main/java/io/opentelemetry/api/logs/package-info.java) repeats the same framing: *"API for writing log appenders... It is NOT a replacement log framework."* This is source, not spec prose — it is the literal contract library authors compile against, and it explicitly says **"should not be used by application developers."** By extension it applies at least as strongly to library authors, who ship code into unknown application contexts and have even less basis to assume their `Logger` call is appropriate for the deploying app's log pipeline.

The event-related surface has partially graduated: `LogRecordBuilder.setEventName(String)` — *"A log record with a non-empty event name is an Event"* — was added directly to the stable base interface `@since 1.50.0` (as a default method returning `this`, i.e. a safe no-op on older SDK implementations), and `LogRecordBuilder.setException(Throwable)` `@since 1.60.0` similarly. But the richer, purpose-built event surface — `ExtendedLogRecordBuilder`/`ExtendedLogger` in [`api/incubator/src/main/java/io/opentelemetry/api/incubator/logs/`](https://github.com/open-telemetry/opentelemetry-java/tree/main/api/incubator/src/main/java/io/opentelemetry/api/incubator/logs) — remains incubator-only; its own Javadoc says *"Extended {@link LogRecordBuilder} with experimental APIs"* and *"keep this class even if it is empty, since experimental methods may be added in the future."* `music-catalogue/pom.xml:41-43` currently pins `opentelemetry-api:1.49.0`, one minor version behind where `setEventName` graduated into the stable interface (1.50.0) — so even the "cheap," now-stable event convenience isn't available to the artifact at its current pin without a bump.

`sdk/logs` (`opentelemetry-sdk-logs`, package `io.opentelemetry.sdk.logs`) is the reference implementation of the Logs Bridge API and is itself **stable** (`1.63.0`, non-alpha, per the README table above) — this is what an application wires up to actually export log records (via OTLP, a logging exporter, etc.); it is not something a library needs to touch.

### 2.3 Events: no standalone spec page, folded into Logs, and being actively pushed there by the project

There is no live `specification/logs/event-api.md` or `/docs/specs/otel/logs/event-api/` page — a direct fetch 404s. Events are handled as a **property of a log record** (`event.name`, formerly a dedicated "Event API" concept) rather than as a separate signal with its own spec doc, consistent with the status page's framing of events as "experimental support... within the Log Bridge API," not an independent signal alongside Tracing/Metrics/Logging/Baggage.

This is reinforced — and dated — by an official OpenTelemetry blog post, [**"Deprecating Span Events API"**](https://opentelemetry.io/blog/2026/deprecating-span-events/), published **2026-03-17**:

> "New code should write events as logs that are correlated with the current span."
>
> "OTLP support for log-based events is already stable, and the Logs API can capture everything span events historically carried, with richer metadata and more flexible export and filtering."
>
> Guidance for instrumentation authors: "For the next major versions, plan to migrate events and exceptions to the Logs API following updated semantic conventions, rather than adding new span events."

So as of four months before this research (2026-03 vs. 2026-07), OpenTelemetry's own direction is that `Span.addEvent`/`Span.recordException` are being deprecated *in favor of* log-based events via the Logs API — but note this guidance is aimed at **instrumentation authors deciding between span events and log events for structured telemetry**, not a statement that libraries should replace their SLF4J diagnostic logging with the Logs API. It doesn't change §2.1/§2.2's audience finding; it only clarifies that "Events," where they exist going forward, are a variant of log records (`event.name` set), not a fourth pillar with its own stable API contract.

## 3. Official guidance: direct Logs API vs. SLF4J/Log4j2/JUL bridging, for library authors specifically

### 3.1 What the spec and status page say

Restated from §2.1: the OpenTelemetry project's own status summary explicitly frames the Java (and cross-language) Logs Bridge API around **appenders**, i.e. code that receives already-formatted log events from SLF4J/Log4j2/Logback/JUL and forwards them into OpenTelemetry — not code that originates log statements. The words "not meant to be called directly by end users" are about as direct an answer to this research question's "crux" as the primary sources offer.

### 3.2 What OpenTelemetry's own Java instrumentation libraries actually do (the strongest evidence)

Since documentation can lag practice, this was checked against source, not just prose. `open-telemetry/opentelemetry-java-instrumentation`'s `instrumentation/jdbc/library` module (the `opentelemetry-jdbc` artifact already surveyed in [`otel-instrumentation-survey.md`](./otel-instrumentation-survey.md)) — a close analog to `music-catalogue` itself, a JDBC-adjacent, hand-written library instrumentation — was grepped file-by-file for any logging-framework import:

```
$ grep -rn "import.*log\|Logger\|logger\." instrumentation/jdbc/library/src/main/java/...
```

The only hit across all ~40 main-source files is `java.util.logging.Logger` in `OpenTelemetryDataSource.java`, and that's solely because `javax.sql.CommonDataSource.getParentLogger()` — a JDBC SPI method the wrapper must implement — is typed to return `java.util.logging.Logger`; it's a pass-through of an inherited interface signature, not an actual logging call. **There is no SLF4J, no JUL usage, and no OTel Logs API usage anywhere in `opentelemetry-jdbc`'s own operational code.** The same emptiness holds for the shared `instrumentation-api` module (the `Instrumenter`/`AttributesExtractor`/`SpanNameExtractor` framework that `opentelemetry-jdbc` and every other library instrumentation in that repo is built on) — no file under `instrumentation-api/src/main` imports `java.util.logging` or `org.slf4j`.

In other words: OpenTelemetry's own reference library instrumentations don't emit *any* diagnostic logs, through *any* channel — not SLF4J, not the OTel Logs API. They emit spans and metrics only. This is the cleanest available signal for "what does the project itself do, not just say": a library instrumentation's job is to produce telemetry (spans/metrics/log-events-as-telemetry), and anything that would traditionally be a `logger.warn(...)` diagnostic line for a human operator is either omitted entirely or (per §2.1–2.2) left to whatever logging framework the *application* has chosen, bridged in by the application, not the library.

### 3.3 The bridge/appender artifacts that do exist, and their stated audience

- **`io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17`** (latest `2.16.0-alpha` per Maven Central search, `https://search.maven.org/solrsearch/select?q=g:%22io.opentelemetry.instrumentation%22...`; confirmed present at `https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-log4j-appender-2.17/`) — its [README](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java-instrumentation/main/instrumentation/log4j/log4j-appender-2.17/library/README.md) states: *"This module provides a Log4j2 appender which forwards Log4j2 log events to the OpenTelemetry Log SDK."* Usage is squarely an **application** concern: the app adds an `<OpenTelemetry name="OpenTelemetryAppender"/>` element to its `log4j2.xml`, then calls `OpenTelemetryAppender.install(openTelemetrySdk)` "during application startup." A library has no place in this flow — it just calls `LoggerFactory.getLogger(...)` as normal and the application decides whether/how those calls end up in OTel.
- **A standalone `opentelemetry-slf4j-bridge` artifact does not currently exist on Maven Central.** This is worth flagging explicitly since the research brief's own primary-source list names it: a full repo-tree search of `open-telemetry/opentelemetry-java-instrumentation` at `main` (`gh api .../git/trees/main?recursive=1`) turns up no module producing that artifact, and `https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/opentelemetry-slf4j-bridge/maven-metadata.xml` 404s; a full directory listing of `io.opentelemetry.instrumentation`'s Maven Central group has zero `*slf4j*`-named artifacts at all (checked against the raw `repo1.maven.org` group index). What *does* exist for SLF4J specifically is automatic, javaagent-only bridging: running an application under the OTel Java agent auto-instruments Logback/Log4j2/JUL/JBoss LogManager appenders directly (controlled per-framework via `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED`, `OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED`, `OTEL_INSTRUMENTATION_JUL_ENABLED`, etc., all enabled by default) — since SLF4J itself is a facade over one of those underlying frameworks, an app using SLF4J-over-Logback/Log4j2 gets its logs bridged for free just by attaching the javaagent, with **no code change and no dependency added to the library**. This is the strongest practical argument for a library to keep using SLF4J as-is: it's already covered by the zero-effort path.
- **`opentelemetry-logback-mdc-1.0`** and **`opentelemetry-log4j-context-data-2.17-autoconfigure`** — narrower, MDC-only artifacts (confirmed present on Maven Central: `repo1.maven.org/maven2/io/opentelemetry/instrumentation/` group listing) that inject `trace_id`/`span_id`/`trace_flags` into the logging framework's MDC/ThreadContext so a *human-readable* console/file log line can display them, without converting the log into an OTel `LogRecord` at all — a lighter-weight, non-exporting form of correlation, per [`docs/logger-mdc-instrumentation.md`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/logger-mdc-instrumentation.md): *"The OTel Java agent injects several pieces of information about the current span into each logging event's MDC copy."* Again, application-level opt-in, not a library concern.

### 3.4 Synthesis

Every primary source converges on the same split: **libraries should keep emitting through whatever logging facade they already use (SLF4J is the de facto standard); applications own the decision of whether/how to route those log statements into OpenTelemetry**, via either the javaagent's automatic appender bridging or an explicitly-installed library appender (`opentelemetry-log4j-appender-2.17`, or Logback's `opentelemetry-logback-appender-1.0` equivalent). The OTel Logs API in `opentelemetry-api` exists for the authors of *those bridges*, not for arbitrary libraries wanting to skip SLF4J. And OTel's own library instrumentations, when given the choice, emit no diagnostic logs of their own at all — reinforcing that "library operational logging" and "OTel telemetry" are treated as separate concerns by the project, not as something to be unified by having libraries call the Logs API directly.

## 4. Correlation with active spans

### 4.1 Direct Logs API (`LogRecordBuilder`)

Correlation is automatic by default, and requires no manual work in the common (same-thread) case. `LogRecordBuilder.setContext(Context)` exists precisely so a caller *can* override which context (and therefore which span) a log record is associated with, but it is optional. The SDK's implementation, [`SdkLogRecordBuilder.emit()`](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java/main/sdk/logs/src/main/java/io/opentelemetry/sdk/logs/SdkLogRecordBuilder.java):

```java
public void emit() {
  ...
  Context context = this.context == null ? Context.current() : this.context;
  ...
  Span.fromContext(context).getSpanContext(), // → trace_id / span_id attached to the LogRecord
  ...
}
```

falls back to `Context.current()` whenever `setContext(...)` was never called — i.e., a log record emitted anywhere inside `try (Scope scope = span.makeCurrent()) { ... }` (or any code path reached synchronously from such a block, including via the OTel-instrumented `Instrumenter` flow used for spans) automatically carries that span's `trace_id`/`span_id`/`trace_flags` with zero extra code. This matches `music-catalogue`'s own existing pattern of starting spans and running statement/transaction code on the same thread (`StatementExecutor.java:128-155`, `MusicCatalogueSession.java:268-276`) — any OTel log record emitted from inside those blocks would auto-correlate for free, the same way a hypothetical SLF4J call in the same block would auto-correlate once bridged (§4.2).

### 4.2 Bridge/appender approach (Log4j2 appender, and by extension the javaagent's SLF4J-facaded auto-bridging)

Same underlying mechanism, same default behavior. [`OpenTelemetryAppender.getContext(...)`](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java-instrumentation/main/instrumentation/log4j/log4j-appender-2.17/library/src/main/java/io/opentelemetry/instrumentation/log4j/appender/v2_17/OpenTelemetryAppender.java) does the equivalent fallback:

```java
Context currentContext = Context.current();
```

when the log event doesn't already carry an explicit OTel `Context` object. So a plain `logger.warn(...)` call made on the thread where a span is current gets bridged into an OTel `LogRecord` carrying that span's trace/span IDs automatically, with **no MDC configuration required** for the OTel-Logs-export path specifically. Where MDC *does* matter is the separate, cosmetic concern of getting `trace_id`/`span_id` to show up as text in a human-readable console/file log line (e.g. `%X{trace_id}` in a `PatternLayout`) — that's a display concern for whichever appender renders text, unrelated to whether the exported OTel `LogRecord` itself carries correlation data.

The one real gap in both paths: **async/cross-thread logging**. If a log call happens on a different thread than the one that had the span active (e.g. a log-processing thread, or Log4j2's `AsyncAppender`), `Context.current()` on that thread won't see the original span, and the README explicitly calls out that this "cannot be configured reliably from `log4j2.xml`" — it requires setting `OpenTelemetryAppenderContextDataInjector` as a JVM system property or in `log4j2.component.properties` before Log4j initializes. `music-catalogue` logs synchronously on the calling thread in all five of its call sites (§5), so this edge case does not currently apply to it.

## 5. Practical implication for `music-catalogue`

### 5.1 Current call sites (grounding, not exhaustive re-derivation — see also `otel-instrumentation-survey.md:24` for the slow-query line, first flagged there as out of scope for that survey)

All against `music-catalogue/src/main/java/io/pgenie/artifacts/myspace/musiccatalogue/` at commit `822f84c`:

| Call site | Level | Message | Source |
|---|---|---|---|
| Slow-query warning | `warn` | `"Slow query detected: {} took {} seconds"` | `StatementExecutor.java:193`, logger injected via constructor `StatementExecutor.java:43,50,56` |
| Session opened | `info` | `"MusicCatalogueSession opened for jdbcUrl={} user={}"`, URL passed through `redactUrl(...)` | `MusicCatalogueSession.java:98`, logger field `:46` |
| Session closing | `info` | `"Closing MusicCatalogueSession"` | `MusicCatalogueSession.java:353` |
| Pool-drain timeout | `warn` | `"{} active connection(s) remained at close deadline"` | `MusicCatalogueSession.java:375` |
| Session closed | `info` | `"MusicCatalogueSession closed"` | `MusicCatalogueSession.java:383` |
| URL redaction helper (feeds the session-opened log line above; not itself a log call) | — | strips the `password=...` query param down to `password=***` | `MusicCatalogueSession.redactUrl`, `MusicCatalogueSession.java:386-399` |

`ObservableTransactionContext.java` and `MusicCatalogueConfig.java` have **no logging calls at all** — confirmed by grep (`grep -n logger *.java` in the artifact's source tree returns hits only in `MusicCatalogueSession.java` and `StatementExecutor.java`). `MusicCatalogueConfig`'s only logging-adjacent code is its redacting `toString()` (`MusicCatalogueConfig.java:204-210`, doc comment: *"Redacts {@link #password()} so it can't leak into logs or exception messages"*) — a defensive measure for if the config object itself ever gets logged or included in an exception message elsewhere, not a log statement of its own.

`music-catalogue/pom.xml:39-43,45-49` already declares `io.opentelemetry:opentelemetry-api:1.49.0` (compile scope, for the existing `Tracer`/`Meter` wiring) alongside `org.slf4j:slf4j-api:2.0.17` (compile scope). This matters for the recommendation below: adopting the OTel Logs API would **not** require adding a new compile dependency (the base `Logger`/`LoggerProvider`/`LogRecordBuilder` types have been in `opentelemetry-api` since 1.27.0, well below the pinned 1.49.0) — so the maturity/audience findings in §2–3, not dependency weight, are what should drive the decision here.

### 5.2 Recommendation: keep SLF4J, let applications bridge it

**Do not drop SLF4J in favor of calling the OTel Logs API directly from `music-catalogue`.** Reasoning, tied directly to §§2–4:

1. **These are exactly the log statements the Logs API's own Javadoc says not to originate this way.** All five call sites are precisely "operational/diagnostic messages a human operator reads" — a slow-query threshold breach, session lifecycle, a graceful-shutdown timeout warning — not structured telemetry data an ingestion pipeline consumes as first-class signal. `Logger.logRecordBuilder()`'s own doc comment (§2.2) says this "should not be used by application developers" and is meant for appender authors bridging *existing* frameworks — `music-catalogue` is not writing an appender, it's the source of the very log statements an appender would bridge.
2. **OTel's own library instrumentations set the precedent, and it's "don't."** `opentelemetry-jdbc` — the closest upstream analog to `music-catalogue`'s own shape (a JDBC-adjacent library wrapping statement execution) — contains zero logging calls through any framework (§3.2). If OpenTelemetry's own JDBC library instrumentation authors, who have the most direct incentive and expertise to use their own Logs API, chose not to emit any operational logs at all rather than reach for it, that's strong evidence against `music-catalogue` doing so either.
3. **The "Events API" piece is unambiguously not something to hard-depend on.** Where these call sites might arguably be reframed as structured "events" (e.g. `"slow query detected"` as an `event.name`-bearing log record) rather than free-text diagnostics, that capability lives in `opentelemetry-api-incubator`, which the project's own README explicitly and currently (as of `1.63.0-alpha`) still labels **alpha across all 31 published releases with no GA ever** (§2.2). A generated, published library artifact should not hard-depend on a surface the producing project itself has never stabilized — this is the same "don't hard-depend on incubator" principle already applied to `opentelemetry-jdbc`/`opentelemetry-hikaricp-3.0` in `otel-instrumentation-survey.md:9`, and it applies with equal force here.
4. **SLF4J bridging is already a solved, zero-effort problem for consumers, whichever way they deploy.** If a `music-catalogue`-consuming application runs under the OTel javaagent, its SLF4J-backed logs (via Logback/Log4j2/JUL, whichever the app chooses) are auto-bridged into OTel with **no code change anywhere**, default-enabled (§3.3). If the application does manual/library-mode SDK wiring instead, it can install `opentelemetry-log4j-appender-2.17` (or the Logback equivalent) once at its own composition root and every SLF4J call site across every dependency — `music-catalogue` included — gets bridged uniformly. Switching `music-catalogue` itself to call the OTel Logs API directly would only special-case *this one library's* five log lines, forcing them onto a different pipeline than every other dependency's SLF4J-backed logging in the same application, for no correlation benefit (§4 shows both paths auto-correlate to the active span identically) and a real stability cost (§2).
5. **The redaction logic (`redactUrl`, `MusicCatalogueConfig.toString()`) is orthogonal to this decision and needs no change either way** — it's about what value gets *passed into* the log call, not which API the log call goes through; the same redaction is equally necessary whether the sink is SLF4J or an OTel `LogRecordBuilder.setBody(...)`.

If this changes later — e.g. if the OTel Java Events API graduates to stable, or if pgenie decides some of these lines (the slow-query warning in particular) really are structured telemetry that should be span-correlated events rather than free-text logs — the pattern to reach for at that point is `LogRecordBuilder.setEventName("pgenie.slow_query")` on the now-stable base interface (available since `opentelemetry-api:1.50.0`, one bump above the current `1.49.0` pin), emitted from inside the existing span scope so it auto-correlates per §4.1, *not* the incubator `ExtendedLogRecordBuilder`. That would still be an explicit design decision to make later, informed by re-checking the Events API's stability status at that time — not a default to adopt now.

## 6. Open questions / caveats

- I have live web/GitHub access (verified via `WebFetch` and `curl`/`gh` against `raw.githubusercontent.com`, `opentelemetry.io`, `repo1.maven.org`, `search.maven.org`, and the GitHub REST/tree API) and used it for every non-obvious factual claim above; nothing here is guessed from training-data memory of these artifacts. Versions/commits referenced are `main`-tip as of 2026-07-10, not pinned SHAs.
- The tension between the Logs API spec doc's "can also be directly called by... instrumented libraries or applications" and the status page's/Java Javadoc's "not meant to be called directly by end users" (§2.1) is real and I did not find a changelog entry reconciling the two — I've treated the more specific, source-backed claim as authoritative for this recommendation, but a future re-read closer to a decision point should re-check whether the spec doc's permissive language has since been tightened (or the status page's loosened) to match.
- I did not verify the Logback appender (`opentelemetry-logback-appender-1.0`) README in the same depth as the Log4j2 one (§3.3) — its existence and general shape were confirmed via the Maven Central group listing only; if a future ticket needs Logback-specific detail (`music-catalogue` doesn't pin a concrete SLF4J binding today, so this wasn't load-bearing here), re-fetch its README directly.
- I did not attempt to enumerate every OTel Java instrumentation module's internal logging usage — only `opentelemetry-jdbc` and the shared `instrumentation-api` framework (§3.2), chosen as the closest structural analog to `music-catalogue` and already the subject of `otel-instrumentation-survey.md`. A broader survey across all ~100+ instrumentation modules in that repo was out of scope and likely unnecessary given how unambiguous the two checked modules were.
- The absence of a standalone `opentelemetry-slf4j-bridge` Maven artifact (§3.3) is worth double-checking again if this doc is revisited much later — it's possible this functionality is mid-refactor upstream (the existence of `Slf4jApplicationLoggerBridge.java` under `instrumentation/internal/internal-application-logger/javaagent/` suggests SLF4J-facade bridging logic does exist in the codebase, just not as a separately-publishable library artifact today) rather than permanently absent.
