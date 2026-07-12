package io.codemine.java.reachpg.observability;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Observability for one particular statement execution (or one particular batch).
 *
 * <p>Bound at construction to the statement (or batch) whose telemetry it will emit — the CLIENT
 * span is already started by the time a {@code StatementObservability} exists. Callers obtain an
 * instance via the package-private {@link #forStatement} / {@link #forBatch} factories and must
 * call {@link #execute} exactly once to run the operation and end the span.</p>
 */
public final class StatementObservability {

    static final String DB_SYSTEM = "postgresql";

    static final String METRIC_NAME = "db.client.operation.duration";
    static final String METRIC_UNIT = "s";
    static final String METRIC_DESCRIPTION = "Duration of database client operations";

    static final AttributeKey<String> DB_SYSTEM_NAME_KEY = AttributeKey.stringKey("db.system.name");
    static final AttributeKey<String> DB_QUERY_TEXT_KEY = AttributeKey.stringKey("db.query.text");
    static final AttributeKey<String> DB_OPERATION_NAME_KEY = AttributeKey.stringKey("db.operation.name");
    static final AttributeKey<String> DB_COLLECTION_NAME_KEY = AttributeKey.stringKey("db.collection.name");
    static final AttributeKey<String> STATEMENT_NAME_KEY = AttributeKey.stringKey("pgenie.statement.name");
    static final AttributeKey<String> DB_USER_KEY = AttributeKey.stringKey("pgenie.db.user");
    static final AttributeKey<Long> BATCH_SIZE_KEY = AttributeKey.longKey("db.operation.batch.size");

    private final DoubleHistogram durationHistogram;
    private final Logger logger;
    private final Duration slowQueryLogThreshold;
    private final String statementName;
    private final String sql;
    private final Optional<String> operationName;
    private final Optional<String> collectionName;
    private final Span span;

    private StatementObservability(
            DoubleHistogram durationHistogram,
            Logger logger,
            Duration slowQueryLogThreshold,
            String statementName,
            String sql,
            Optional<String> operationName,
            Optional<String> collectionName,
            Span span) {
        this.durationHistogram = durationHistogram;
        this.logger = logger;
        this.slowQueryLogThreshold = slowQueryLogThreshold;
        this.statementName = statementName;
        this.sql = sql;
        this.operationName = operationName;
        this.collectionName = collectionName;
        this.span = span;
    }

    /**
     * Builds the {@code db.client.operation.duration} histogram from a {@link Meter}.
     *
     * <p>Callers build this once per session and pass the same instance into every
     * {@link #forStatement} / {@link #forBatch} call, so that every statement execution in a
     * session records onto one shared instrument.</p>
     *
     * @param meter the OpenTelemetry meter used to derive the histogram
     * @return the duration histogram
     * @throws NullPointerException if {@code meter} is null
     */
    public static DoubleHistogram buildDurationHistogram(Meter meter) {
        Objects.requireNonNull(meter, "meter");
        return meter.histogramBuilder(METRIC_NAME)
                .setUnit(METRIC_UNIT)
                .setDescription(METRIC_DESCRIPTION)
                .build();
    }

    /**
     * Builds a {@code StatementObservability} bound to one statement, starting its CLIENT span.
     *
     * @param tracer               the OpenTelemetry tracer used to create the CLIENT span
     * @param durationHistogram    the shared duration histogram, from {@link #buildDurationHistogram}
     * @param logger               the SLF4J logger used for slow-query warnings
     * @param dbUser               the database user to attach as {@code pgenie.db.user}
     * @param slowQueryLogThreshold queries running longer than this threshold are logged as slow;
     *                             zero logs every query; must not be negative
     * @param statement            the statement whose metadata and SQL define span/metric attributes
     * @param parentSpan           the parent span, or {@code null} to use the current context
     * @return a new statement observability, with its span already started
     * @throws NullPointerException if any argument other than {@code parentSpan} is null
     */
    static StatementObservability forStatement(
            Tracer tracer,
            DoubleHistogram durationHistogram,
            Logger logger,
            String dbUser,
            Duration slowQueryLogThreshold,
            Statement<?> statement,
            Span parentSpan) {
        Objects.requireNonNull(tracer, "tracer");
        Objects.requireNonNull(durationHistogram, "durationHistogram");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dbUser, "dbUser");
        Objects.requireNonNull(slowQueryLogThreshold, "slowQueryLogThreshold");
        Objects.requireNonNull(statement, "statement");

        String statementName = statement.statementName();
        String sql = statement.sql();
        Optional<String> operationName = statement.operationName();
        Optional<String> collectionName = statement.collectionName();

        var builder = tracer.spanBuilder(statementName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(DB_SYSTEM_NAME_KEY, DB_SYSTEM)
                .setAttribute(DB_QUERY_TEXT_KEY, sql)
                .setAttribute(STATEMENT_NAME_KEY, statementName)
                .setAttribute(DB_USER_KEY, dbUser);
        applyMetadataAttributes(builder, operationName, collectionName);
        applyParentSpan(builder, parentSpan);

        return new StatementObservability(
                durationHistogram, logger, slowQueryLogThreshold,
                statementName, sql, operationName, collectionName, builder.startSpan());
    }

    /**
     * Builds a {@code StatementObservability} bound to one batch of statements, starting its
     * CLIENT span.
     *
     * @param tracer                  the OpenTelemetry tracer used to create the CLIENT span
     * @param durationHistogram       the shared duration histogram, from {@link #buildDurationHistogram}
     * @param logger                  the SLF4J logger used for slow-query warnings
     * @param dbUser                  the database user to attach as {@code pgenie.db.user}
     * @param slowQueryLogThreshold    queries running longer than this threshold are logged as slow;
     *                                zero logs every query; must not be negative
     * @param batchSql                the SQL text shared by every statement in the batch
     * @param representativeStatement a representative statement used for optional metadata
     *                                attributes ({@code db.operation.name}, {@code db.collection.name})
     * @param batchSize               the number of statements in the batch
     * @param parentSpan              the parent span, or {@code null} to use the current context
     * @return a new statement observability, with its span already started
     * @throws NullPointerException if any argument other than {@code parentSpan} is null
     */
    static StatementObservability forBatch(
            Tracer tracer,
            DoubleHistogram durationHistogram,
            Logger logger,
            String dbUser,
            Duration slowQueryLogThreshold,
            String batchSql,
            Statement<?> representativeStatement,
            int batchSize,
            Span parentSpan) {
        Objects.requireNonNull(tracer, "tracer");
        Objects.requireNonNull(durationHistogram, "durationHistogram");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dbUser, "dbUser");
        Objects.requireNonNull(slowQueryLogThreshold, "slowQueryLogThreshold");
        Objects.requireNonNull(batchSql, "batchSql");
        Objects.requireNonNull(representativeStatement, "representativeStatement");

        String statementName = "batch";
        Optional<String> operationName = representativeStatement.operationName();
        Optional<String> collectionName = representativeStatement.collectionName();

        var builder = tracer.spanBuilder(statementName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(DB_SYSTEM_NAME_KEY, DB_SYSTEM)
                .setAttribute(DB_QUERY_TEXT_KEY, batchSql)
                .setAttribute(STATEMENT_NAME_KEY, statementName)
                .setAttribute(BATCH_SIZE_KEY, (long) batchSize)
                .setAttribute(DB_USER_KEY, dbUser);
        applyMetadataAttributes(builder, operationName, collectionName);
        applyParentSpan(builder, parentSpan);

        return new StatementObservability(
                durationHistogram, logger, slowQueryLogThreshold,
                statementName, batchSql, operationName, collectionName, builder.startSpan());
    }

    /**
     * Runs {@code operation} under this statement's already-started span, recording the
     * {@code db.client.operation.duration} histogram point and any slow-query warning, then ends
     * the span.
     *
     * <p>Must be called exactly once per {@code StatementObservability}.</p>
     *
     * @param operation the operation that actually executes the statement or batch
     * @return the operation result
     * @throws SQLException if the operation throws
     */
    public <R> R execute(SqlOperation<R> operation) throws SQLException {
        Objects.requireNonNull(operation, "operation");

        long startNanos = System.nanoTime();
        try (var scope = span.makeCurrent()) {
            R result = operation.execute();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Throwable t) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
            throw t;
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            double durationSeconds = durationNanos / 1_000_000_000.0;
            recordDuration(durationSeconds);
            maybeLogSlowQuery(durationNanos, durationSeconds);
            span.end();
        }
    }

    private static void applyMetadataAttributes(
            SpanBuilder builder, Optional<String> operationName, Optional<String> collectionName) {
        operationName.ifPresent(v -> builder.setAttribute(DB_OPERATION_NAME_KEY, v));
        collectionName.ifPresent(v -> builder.setAttribute(DB_COLLECTION_NAME_KEY, v));
    }

    private static void applyParentSpan(SpanBuilder builder, Span parentSpan) {
        if (parentSpan != null) {
            builder.setParent(Context.current().with(parentSpan));
        }
    }

    private void recordDuration(double durationSeconds) {
        var attributesBuilder = Attributes.builder()
                .put(DB_SYSTEM_NAME_KEY, DB_SYSTEM)
                .put(DB_QUERY_TEXT_KEY, sql)
                .put(STATEMENT_NAME_KEY, statementName);
        operationName.ifPresent(v -> attributesBuilder.put(DB_OPERATION_NAME_KEY, v));
        collectionName.ifPresent(v -> attributesBuilder.put(DB_COLLECTION_NAME_KEY, v));
        durationHistogram.record(durationSeconds, attributesBuilder.build());
    }

    private void maybeLogSlowQuery(long durationNanos, double durationSeconds) {
        if (Duration.ofNanos(durationNanos).compareTo(slowQueryLogThreshold) > 0) {
            logger.warn("Slow query detected: {} took {} seconds", statementName, durationSeconds);
        }
    }
}
