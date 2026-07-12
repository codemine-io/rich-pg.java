package io.codemine.java.reachpg.observability;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.StatementBatch;
import io.codemine.java.postgresql.jdbc.TransactionContext;
import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Owns all transaction-related OpenTelemetry instrumentation for {@code reach-pg}.
 *
 * <p>The class is responsible for:</p>
 *
 * <ul>
 *   <li>Creating the single INTERNAL {@code "transaction"} span and pre-setting attributes from
 *       {@link TransactionSettings}.</li>
 *   <li>Tracking commit and rollback attempts during the vendor retry loop.</li>
 *   <li>Building a {@link StatementObservability} leaf for every statement/batch executed inside
 *       the transaction, parented to the transaction span.</li>
 *   <li>Classifying the final outcome ({@code committed}, {@code retries_exhausted},
 *       {@code non_retryable_failure}) and recording it on the span.</li>
 *   <li>Recording transaction retries on the {@code pgenie.transaction.retries} counter.</li>
 *   <li>Deciding whether a failure is retryable by inspecting PostgreSQL SQLSTATEs.</li>
 * </ul>
 *
 * <p>Callers obtain a {@link TransactionObservation} via {@link #observe}, make its span current,
 * run the transaction body, and then call {@link TransactionObservation#markCommitted()} or
 * {@link TransactionObservation#markFailed(Throwable)} before closing the observation.</p>
 */
public final class TransactionObservability {

    /** Name of the transaction span. */
    static final String SPAN_NAME = "transaction";

    /** Name of the transaction-retries counter. */
    static final String RETRIES_METRIC_NAME = "pgenie.transaction.retries";

    /** Description of the transaction-retries counter. */
    static final String RETRIES_METRIC_DESCRIPTION = "Number of transaction retries";

    /** {@code db.system.name} attribute key. */
    static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");

    /** {@code pgenie.transaction.isolation_level} attribute key. */
    static final AttributeKey<String> ISOLATION_LEVEL =
            AttributeKey.stringKey("pgenie.transaction.isolation_level");

    /** {@code pgenie.transaction.max_attempts} attribute key. */
    static final AttributeKey<Long> MAX_ATTEMPTS =
            AttributeKey.longKey("pgenie.transaction.max_attempts");

    /** {@code pgenie.transaction.read_only} attribute key. */
    static final AttributeKey<Boolean> READ_ONLY =
            AttributeKey.booleanKey("pgenie.transaction.read_only");

    /** {@code pgenie.transaction.attempt_count} attribute key. */
    static final AttributeKey<Long> ATTEMPT_COUNT =
            AttributeKey.longKey("pgenie.transaction.attempt_count");

    /** {@code pgenie.transaction.outcome} attribute key. */
    static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("pgenie.transaction.outcome");

    /** Outcome value for a transaction that committed successfully. */
    static final String OUTCOME_COMMITTED = "committed";

    /** Outcome value for a transaction that exhausted all retry attempts. */
    static final String OUTCOME_RETRIES_EXHAUSTED = "retries_exhausted";

    /** Outcome value for a transaction that failed with a non-retryable error. */
    static final String OUTCOME_NON_RETRYABLE_FAILURE = "non_retryable_failure";

    private static final String DB_SYSTEM = "postgresql";
    private static final String[] RETRYABLE_SQL_STATES = {"40001", "40P01", "23505"};

    private final Tracer tracer;
    private final LongCounter retriesCounter;
    private final DoubleHistogram durationHistogram;
    private final Logger logger;
    private final String dbUser;
    private final Duration slowQueryLogThreshold;
    private final Span boundParentSpan;

    /**
     * Creates a new transaction observability helper.
     *
     * @param tracer               the OpenTelemetry tracer used to create the transaction span and
     *                             the statement spans of statements executed inside it
     * @param meter                the OpenTelemetry meter used to derive the retries counter
     * @param durationHistogram    the shared {@code db.client.operation.duration} histogram, from
     *                             {@link StatementObservability#buildDurationHistogram}
     * @param logger               the SLF4J logger used to warn when retries are exhausted and for
     *                             slow-query warnings on statements executed inside the transaction
     * @param dbUser               the database user to attach as {@code pgenie.db.user} on statement
     *                             spans executed inside the transaction
     * @param slowQueryLogThreshold queries running longer than this threshold are logged as slow;
     *                             zero logs every query; must not be negative
     * @throws NullPointerException if any argument is null
     */
    public TransactionObservability(
            Tracer tracer,
            Meter meter,
            DoubleHistogram durationHistogram,
            Logger logger,
            String dbUser,
            Duration slowQueryLogThreshold) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        Objects.requireNonNull(meter, "meter");
        this.durationHistogram = Objects.requireNonNull(durationHistogram, "durationHistogram");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dbUser = Objects.requireNonNull(dbUser, "dbUser");
        this.slowQueryLogThreshold = Objects.requireNonNull(slowQueryLogThreshold, "slowQueryLogThreshold");
        this.retriesCounter = meter.counterBuilder(RETRIES_METRIC_NAME)
                .setDescription(RETRIES_METRIC_DESCRIPTION)
                .build();
        this.boundParentSpan = null;
    }

    private TransactionObservability(TransactionObservability source, Span boundParentSpan) {
        this.tracer = source.tracer;
        this.retriesCounter = source.retriesCounter;
        this.durationHistogram = source.durationHistogram;
        this.logger = source.logger;
        this.dbUser = source.dbUser;
        this.slowQueryLogThreshold = source.slowQueryLogThreshold;
        this.boundParentSpan = boundParentSpan;
    }

    /**
     * Returns a copy of this wrapper that falls back to {@code parentSpan} as the parent for any
     * {@link #observe} call that itself passes a {@code null} parent span.
     *
     * <p>An explicit, non-null parent span passed to {@link #observe} always takes precedence
     * over the bound span.</p>
     *
     * @param parentSpan the default parent span, or {@code null} to fall through to the current
     *                   OpenTelemetry context as before
     * @return a copy of this wrapper bound to {@code parentSpan}
     */
    TransactionObservability withParentSpan(Span parentSpan) {
        return new TransactionObservability(this, parentSpan);
    }

    private Span effectiveParentSpan(Span parentSpan) {
        return parentSpan != null ? parentSpan : boundParentSpan;
    }

    /**
     * Starts a transaction observation for the given settings and connection.
     *
     * <p>The returned observation implements {@link TransactionContext} and can be passed directly
     * to the vendor transaction body. Its {@link TransactionObservation#span()} is the transaction
     * span; statement execution through the observation builds a {@link StatementObservability}
     * leaf per statement/batch, parented to that span.</p>
     *
     * @param settings   the transaction settings
     * @param connection the JDBC connection to use
     * @param parentSpan the parent span, or {@code null} to use the current span
     * @return a new transaction observation
     * @throws NullPointerException if {@code settings} or {@code connection} is null
     */
    public TransactionObservation observe(
            TransactionSettings settings,
            Connection connection,
            Span parentSpan) {
        return new TransactionObservation(startSpan(settings, effectiveParentSpan(parentSpan)), connection, settings);
    }

    private Span startSpan(TransactionSettings settings, Span parentSpan) {
        var builder = tracer.spanBuilder(SPAN_NAME)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(DB_SYSTEM_NAME, DB_SYSTEM)
                .setAttribute(ISOLATION_LEVEL, settings.isolationLevel().name())
                .setAttribute(MAX_ATTEMPTS, (long) settings.maxAttempts())
                .setAttribute(READ_ONLY, settings.readOnly());

        if (parentSpan != null) {
            builder.setParent(Context.current().with(parentSpan));
        }
        // When parentSpan is null, the span builder defaults to the current context,
        // making the span a child of Span.current().

        return builder.startSpan();
    }

    /**
     * Returns true if {@code failure} is a retryable PostgreSQL failure.
     *
     * <p>Retryable SQLSTATEs are {@code 40001} (serialization failure), {@code 40P01} (deadlock
     * detected), and {@code 23505} (unique violation, which PostgreSQL may raise instead of
     * {@code 40001} under {@code SERIALIZABLE} isolation).</p>
     *
     * @param failure the exception to inspect; may be null
     * @return true if the exception is non-null and has a retryable SQLSTATE
     */
    public static boolean isRetryableFailure(Throwable failure) {
        if (failure == null) {
            return false;
        }
        SQLException sqlException = extractSqlException(failure);
        if (sqlException == null) {
            return false;
        }
        String state = sqlException.getSQLState();
        if (state == null) {
            return false;
        }
        for (String retryable : RETRYABLE_SQL_STATES) {
            if (state.equals(retryable)) {
                return true;
            }
        }
        return false;
    }

    private static SQLException extractSqlException(Throwable t) {
        if (t instanceof SQLException sqlException) {
            return sqlException;
        }
        Throwable cause = t.getCause();
        if (cause instanceof SQLException sqlException) {
            return sqlException;
        }
        return null;
    }

    /**
     * A single in-flight transaction observation.
     *
     * <p>Implements {@link TransactionContext} by delegating to a fresh
     * {@link TransactionContext#of(Connection)} while counting commit/rollback attempts and
     * building a {@link StatementObservability} leaf per statement/batch, parented to the
     * transaction span.</p>
     */
    public final class TransactionObservation implements TransactionContext, AutoCloseable {

        private final Span span;
        private final Connection connection;
        private final TransactionSettings settings;
        private final TransactionContext delegate;

        private boolean commitCalled;
        private int rollbackCount;
        private boolean closed;

        private TransactionObservation(Span span, Connection connection, TransactionSettings settings) {
            this.span = Objects.requireNonNull(span, "span");
            this.connection = Objects.requireNonNull(connection, "connection");
            this.settings = Objects.requireNonNull(settings, "settings");
            this.delegate = TransactionContext.of(connection);
        }

        /**
         * Returns the transaction span.
         *
         * @return the span representing this transaction
         */
        public Span span() {
            return span;
        }

        /**
         * Returns whether {@link #commit()} was attempted, regardless of whether it succeeded.
         *
         * @return true if commit was attempted
         */
        public boolean committed() {
            return commitCalled;
        }

        /**
         * Returns the number of times {@link #rollback()} was attempted, including attempts where
         * the delegate threw.
         *
         * @return the rollback attempt count
         */
        public int rollbackCount() {
            return rollbackCount;
        }

        @Override
        public <R> R execute(Statement<R> statement) throws SQLException {
            StatementObservability leaf = StatementObservability.forStatement(
                    tracer, durationHistogram, logger, dbUser, slowQueryLogThreshold, statement, span);
            return leaf.execute(() -> statement.executeOn(connection));
        }

        @Override
        public <R> List<R> executeBatch(Iterable<? extends Statement<R>> statements) throws SQLException {
            List<Statement<R>> statementList = new ArrayList<>();
            statements.forEach(statementList::add);
            if (statementList.isEmpty()) {
                return List.of();
            }

            StatementBatch<R> batch = new StatementBatch<>(statementList);
            Statement<?> first = statementList.get(0);

            StatementObservability leaf = StatementObservability.forBatch(
                    tracer, durationHistogram, logger, dbUser, slowQueryLogThreshold,
                    batch.sql(), first, batch.size(), span);
            return leaf.execute(() -> batch.execute(connection));
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return delegate.setSavepoint();
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            delegate.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            delegate.releaseSavepoint(savepoint);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return delegate.getAutoCommit();
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            delegate.setAutoCommit(autoCommit);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return delegate.getTransactionIsolation();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            delegate.setTransactionIsolation(level);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return delegate.isReadOnly();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public void commit() throws SQLException {
            commitCalled = true;
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            rollbackCount++;
            delegate.rollback();
        }

        /**
         * Marks the transaction as committed and records the outcome on the span.
         *
         * <p>Sets {@code pgenie.transaction.attempt_count}, {@code pgenie.transaction.outcome},
         * span status to {@code OK}, and adds the retry count to the counter.</p>
         */
        public void markCommitted() {
            int attempts = rollbackCount + 1;
            span.setAttribute(ATTEMPT_COUNT, (long) attempts);
            span.setAttribute(OUTCOME, OUTCOME_COMMITTED);
            retriesCounter.add(Math.max(0, attempts - 1));
            span.setStatus(StatusCode.OK);
        }

        /**
         * Marks the transaction as failed and records the outcome on the span.
         *
         * <p>Sets {@code pgenie.transaction.attempt_count}, {@code pgenie.transaction.outcome},
         * records the exception, sets span status to {@code ERROR}, adds the retry count to the
         * counter, and logs a warning when retries are exhausted.</p>
         *
         * @param failure the failure that caused the transaction to fail; must not be null
         * @throws NullPointerException if {@code failure} is null
         */
        public void markFailed(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            int attempts = Math.max(1, rollbackCount);
            String outcome = isRetryableFailure(failure)
                    ? OUTCOME_RETRIES_EXHAUSTED
                    : OUTCOME_NON_RETRYABLE_FAILURE;
            span.setAttribute(ATTEMPT_COUNT, (long) attempts);
            span.setAttribute(OUTCOME, outcome);
            retriesCounter.add(Math.max(0, attempts - 1));
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage());

            if (OUTCOME_RETRIES_EXHAUSTED.equals(outcome)) {
                logger.warn("Transaction exhausted {} attempts, last failure: {}", settings.maxAttempts(), failure);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            span.end();
        }
    }
}
