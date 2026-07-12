package io.codemine.java.reachpg;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import io.codemine.java.postgresql.jdbc.Transaction;
import io.codemine.java.postgresql.jdbc.TransactionSettings;
import io.codemine.java.reachpg.observability.StatementObservability;
import io.codemine.java.reachpg.observability.TransactionObservability;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionExecutorIT extends AbstractDatabaseIT {

    private static final AttributeKey<String> DB_SYSTEM_NAME_KEY = AttributeKey.stringKey("db.system.name");
    private static final AttributeKey<String> ISOLATION_LEVEL_KEY =
            AttributeKey.stringKey("pgenie.transaction.isolation_level");
    private static final AttributeKey<Long> MAX_ATTEMPTS_KEY =
            AttributeKey.longKey("pgenie.transaction.max_attempts");
    private static final AttributeKey<Boolean> READ_ONLY_KEY =
            AttributeKey.booleanKey("pgenie.transaction.read_only");
    private static final AttributeKey<Long> ATTEMPT_COUNT_KEY =
            AttributeKey.longKey("pgenie.transaction.attempt_count");
    private static final AttributeKey<String> OUTCOME_KEY =
            AttributeKey.stringKey("pgenie.transaction.outcome");

    private InMemorySpanExporter spanExporter;
    private InMemoryMetricReader metricReader;
    private SdkTracerProvider tracerProvider;
    private SdkMeterProvider meterProvider;
    private OpenTelemetrySdk openTelemetry;
    private CollectingLogger logger;
    private TransactionExecutor transactionExecutor;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        metricReader = InMemoryMetricReader.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();
        logger = new CollectingLogger();

        transactionExecutor = new TransactionExecutor(
                new TransactionObservability(
                        openTelemetry.getTracer("test"),
                        openTelemetry.getMeter("test"),
                        StatementObservability.buildDurationHistogram(openTelemetry.getMeter("test")),
                        logger,
                        "test-user",
                        Duration.ofSeconds(1)));
    }

    @Test
    void singleAttemptCommittedTransactionEmitsExpectedSpanAndMetrics() throws SQLException {
        try (Connection conn = openConnection()) {
            Transaction<Integer> select = ctx -> ctx.execute(new SelectCounterStatement());

            int value = transactionExecutor.execute(
                    select,
                    TransactionSettings.SERIALIZABLE_READ.withMaxAttempts(1),
                    conn,
                    null);

            assertEquals(0, value);
        }

        flush();

        SpanData transactionSpan = singleTransactionSpan();
        assertEquals("transaction", transactionSpan.getName());
        assertEquals(SpanKind.INTERNAL, transactionSpan.getKind());
        assertEquals(StatusCode.OK, transactionSpan.getStatus().getStatusCode());
        assertEquals("postgresql", transactionSpan.getAttributes().get(DB_SYSTEM_NAME_KEY));
        assertEquals("SERIALIZABLE", transactionSpan.getAttributes().get(ISOLATION_LEVEL_KEY));
        assertEquals(1L, transactionSpan.getAttributes().get(MAX_ATTEMPTS_KEY));
        assertEquals(Boolean.TRUE, transactionSpan.getAttributes().get(READ_ONLY_KEY));
        assertEquals(1L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
        assertEquals("committed", transactionSpan.getAttributes().get(OUTCOME_KEY));

        List<SpanData> statementSpans = childStatementSpans(transactionSpan);
        assertEquals(1, statementSpans.size(), "expected one nested statement span");
        assertEquals("SelectCounterStatement", statementSpans.get(0).getName());

        assertEquals(0, retriesCounterValue(), "retries counter should be zero for a single-attempt transaction");
    }

    @Test
    void retryThenCommitReportsRetriesAndMultipleAttempts() throws Exception {
        List<Integer> attempts = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> increment = () -> {
            try (Connection conn = openConnection()) {
                Transaction<Void> tx = ctx -> {
                    int current = ctx.execute(new SelectCounterStatement());
                    ctx.execute(new UpdateCounterStatement(current + 1));
                    return null;
                };
                transactionExecutor.execute(
                        tx,
                        TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(10),
                        conn,
                        null);
            }
            return null;
        };

        Future<Void> first = executor.submit(increment);
        Future<Void> second = executor.submit(increment);

        assertDoesNotThrow(() -> first.get(30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> second.get(30, TimeUnit.SECONDS));
        executor.shutdown();

        flush();

        List<SpanData> transactionSpans = transactionSpans();
        assertEquals(2, transactionSpans.size());
        for (SpanData span : transactionSpans) {
            assertEquals("committed", span.getAttributes().get(OUTCOME_KEY));
            assertTrue(
                    span.getAttributes().get(ATTEMPT_COUNT_KEY) >= 1,
                    "attempt_count should be at least 1");
            assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        }

        long totalRetries = retriesCounterValue();
        assertTrue(totalRetries > 0, "retries counter should be positive when conflicts were retried");
    }

    @Test
    void retriesExhaustedSetsErrorStatusAndLogsWarning() throws SQLException {
        try (Connection conn = openConnection()) {
            Transaction<Void> conflicting = ctx -> {
                ctx.execute(new InsertDuplicateCounterStatement());
                return null;
            };

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> transactionExecutor.execute(
                            conflicting,
                            TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(2),
                            conn,
                            null));

            assertTrue(
                    TransactionObservability.isRetryableFailure(failure),
                    "exhausted failure should be retryable: " + failure.getSQLState());
        }

        flush();

        SpanData transactionSpan = singleTransactionSpan();
        assertEquals(StatusCode.ERROR, transactionSpan.getStatus().getStatusCode());
        assertEquals(2L, transactionSpan.getAttributes().get(ATTEMPT_COUNT_KEY));
        assertEquals("retries_exhausted", transactionSpan.getAttributes().get(OUTCOME_KEY));
        assertFalse(transactionSpan.getEvents().isEmpty(), "exception should be recorded on the span");

        assertEquals(1, logger.warnings().size(), "expected one retries-exhausted warning");
        String warning = logger.warnings().get(0);
        assertTrue(warning.contains("exhausted"), warning);
        assertTrue(warning.contains("attempts"), warning);

        long retries = retriesCounterValue();
        assertEquals(1L, retries, "retries counter should equal attempts minus one");
    }

    @Test
    void nonRetryableFailureSetsErrorStatus() throws SQLException {
        try (Connection conn = openConnection()) {
            Transaction<Void> invalid = ctx -> {
                ctx.execute(new InvalidSqlStatement());
                return null;
            };

            SQLException failure = assertThrows(
                    SQLException.class,
                    () -> transactionExecutor.execute(
                            invalid,
                            TransactionSettings.SERIALIZABLE_WRITE.withMaxAttempts(3),
                            conn,
                            null));

            assertNotNull(failure);
            assertFalse(
                    TransactionObservability.isRetryableFailure(failure),
                    "syntax error should not be retryable: " + failure.getSQLState());
        }

        flush();

        SpanData transactionSpan = singleTransactionSpan();
        assertEquals(StatusCode.ERROR, transactionSpan.getStatus().getStatusCode());
        assertEquals("non_retryable_failure", transactionSpan.getAttributes().get(OUTCOME_KEY));
        assertFalse(transactionSpan.getEvents().isEmpty(), "exception should be recorded on the span");

        assertEquals(0, logger.warnings().size(), "non-retryable failures should not be logged as warnings");

        long retries = retriesCounterValue();
        assertEquals(0, retries, "retries counter should be zero for non-retryable failures");
    }

    private SpanData singleTransactionSpan() {
        List<SpanData> spans = transactionSpans();
        assertEquals(1, spans.size(), spans::toString);
        return spans.get(0);
    }

    private List<SpanData> transactionSpans() {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(span -> "transaction".equals(span.getName()))
                .toList();
    }

    private List<SpanData> childStatementSpans(SpanData parent) {
        String parentSpanId = parent.getSpanId();
        return spanExporter.getFinishedSpanItems().stream()
                .filter(span -> parentSpanId.equals(span.getParentSpanId()))
                .filter(span -> !"transaction".equals(span.getName()))
                .toList();
    }

    private long retriesCounterValue() {
        for (MetricData metric : metricReader.collectAllMetrics()) {
            if ("pgenie.transaction.retries".equals(metric.getName())) {
                Collection<LongPointData> points = metric.getLongSumData().getPoints();
                long total = 0;
                for (LongPointData point : points) {
                    total += point.getValue();
                }
                return total;
            }
        }
        return 0;
    }

    private void flush() {
        tracerProvider.forceFlush().join(5, SECONDS);
        meterProvider.forceFlush().join(5, SECONDS);
    }

    private record SelectCounterStatement() implements Statement<Integer> {
        @Override
        public String sql() {
            return "select value from retry_counter where id = 1";
        }

        @Override
        public void bindParams(PreparedStatement ps) {}

        @Override
        public boolean returnsRows() {
            return true;
        }

        @Override
        public Integer decodeResultSet(ResultSet rs) throws SQLException {
            rs.next();
            return rs.getInt(1);
        }

        @Override
        public Integer decodeAffectedRows(long affectedRows) {
            throw new UnsupportedOperationException();
        }
    }

    private record UpdateCounterStatement(int value) implements Statement<Long> {
        @Override
        public String sql() {
            return "update retry_counter set value = ? where id = 1";
        }

        @Override
        public void bindParams(PreparedStatement ps) throws SQLException {
            ps.setInt(1, value);
        }

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public Long decodeResultSet(ResultSet rs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long decodeAffectedRows(long affectedRows) {
            return affectedRows;
        }
    }

    private record InsertDuplicateCounterStatement() implements Statement<Long> {
        @Override
        public String sql() {
            return "insert into retry_counter (id, value) values (1, 0)";
        }

        @Override
        public void bindParams(PreparedStatement ps) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public Long decodeResultSet(ResultSet rs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long decodeAffectedRows(long affectedRows) {
            return affectedRows;
        }
    }

    private record InvalidSqlStatement() implements Statement<Long> {
        @Override
        public String sql() {
            return "this is not valid sql";
        }

        @Override
        public void bindParams(PreparedStatement ps) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public Long decodeResultSet(ResultSet rs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long decodeAffectedRows(long affectedRows) {
            return affectedRows;
        }
    }
}
