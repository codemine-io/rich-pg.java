package io.codemine.java.reachpg.observability;

import static io.codemine.java.reachpg.observability.StatementObservability.BATCH_SIZE_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.DB_COLLECTION_NAME_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.DB_OPERATION_NAME_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.DB_QUERY_TEXT_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.DB_SYSTEM_NAME_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.DB_USER_KEY;
import static io.codemine.java.reachpg.observability.StatementObservability.STATEMENT_NAME_KEY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.codemine.java.postgresql.jdbc.Statement;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.codemine.java.reachpg.CollectingLogger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each test constructs a {@code StatementObservability} bound to one particular statement (or
 * batch) via the package-private {@code forStatement}/{@code forBatch} factories, then calls
 * {@link StatementObservability#execute} exactly once, matching how {@code SessionObservability}
 * and {@code TransactionObservability} build and use them in production.
 */
class StatementObservabilityTest {

    private InMemorySpanExporter spanExporter;
    private InMemoryMetricReader metricReader;
    private SdkTracerProvider tracerProvider;
    private SdkMeterProvider meterProvider;
    private OpenTelemetrySdk openTelemetry;
    private CollectingLogger logger;
    private DoubleHistogram durationHistogram;

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
        durationHistogram = StatementObservability.buildDurationHistogram(openTelemetry.getMeter("test"));
    }

    private StatementObservability forStatement(Statement<?> statement, Duration slowQueryThreshold) {
        return StatementObservability.forStatement(
                openTelemetry.getTracer("test"),
                durationHistogram,
                logger,
                "test-user",
                slowQueryThreshold,
                statement,
                null);
    }

    @Test
    void singleStatementSpanAttributesAndMetricPoint() throws SQLException {
        var statement = new MetadataStatement(
                "INSERT INTO albums (title) VALUES (?)", "ok", "INSERT", "albums");

        String result = forStatement(statement, Duration.ofSeconds(1)).execute(() -> "ok");

        assertEquals("ok", result);
        flush();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("MetadataStatement", span.getName());
        assertEquals(SpanKind.CLIENT, span.getKind());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
        assertEquals("INSERT INTO albums (title) VALUES (?)", span.getAttributes().get(DB_QUERY_TEXT_KEY));
        assertEquals("INSERT", span.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertEquals("albums", span.getAttributes().get(DB_COLLECTION_NAME_KEY));
        assertEquals("MetadataStatement", span.getAttributes().get(STATEMENT_NAME_KEY));
        assertEquals("test-user", span.getAttributes().get(DB_USER_KEY));

        MetricData metric = singleDurationMetric();
        assertEquals("db.client.operation.duration", metric.getName());
        assertEquals("s", metric.getUnit());
        HistogramPointData point = singlePoint(metric);
        assertEquals("postgresql", point.getAttributes().get(DB_SYSTEM_NAME_KEY));
        assertEquals("INSERT", point.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertEquals("albums", point.getAttributes().get(DB_COLLECTION_NAME_KEY));
        assertEquals("MetadataStatement", point.getAttributes().get(STATEMENT_NAME_KEY));
        assertEquals("INSERT INTO albums (title) VALUES (?)", point.getAttributes().get(DB_QUERY_TEXT_KEY));
        assertNull(point.getAttributes().get(BATCH_SIZE_KEY));
    }

    @Test
    void batchSpanAttributesAndMetricPoint() throws SQLException {
        var statement = new MetadataStatement(
                "UPDATE albums SET title = ?", "r1", "UPDATE", "albums");

        StatementObservability observability = StatementObservability.forBatch(
                openTelemetry.getTracer("test"),
                durationHistogram,
                logger,
                "test-user",
                Duration.ofSeconds(1),
                statement.sql(),
                statement,
                2,
                null);

        List<String> results = observability.execute(() -> List.of("r1", "r2"));

        assertEquals(List.of("r1", "r2"), results);
        flush();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("batch", span.getName());
        assertEquals(SpanKind.CLIENT, span.getKind());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals("postgresql", span.getAttributes().get(DB_SYSTEM_NAME_KEY));
        assertEquals("UPDATE albums SET title = ?", span.getAttributes().get(DB_QUERY_TEXT_KEY));
        assertEquals("UPDATE", span.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertEquals("albums", span.getAttributes().get(DB_COLLECTION_NAME_KEY));
        assertEquals("batch", span.getAttributes().get(STATEMENT_NAME_KEY));
        assertEquals("test-user", span.getAttributes().get(DB_USER_KEY));
        assertEquals(2L, span.getAttributes().get(BATCH_SIZE_KEY));

        MetricData metric = singleDurationMetric();
        HistogramPointData point = singlePoint(metric);
        assertEquals("postgresql", point.getAttributes().get(DB_SYSTEM_NAME_KEY));
        assertEquals("UPDATE", point.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertEquals("albums", point.getAttributes().get(DB_COLLECTION_NAME_KEY));
        assertEquals("batch", point.getAttributes().get(STATEMENT_NAME_KEY));
        assertEquals("UPDATE albums SET title = ?", point.getAttributes().get(DB_QUERY_TEXT_KEY));
        assertNull(point.getAttributes().get(BATCH_SIZE_KEY));
    }

    @Test
    void statementWithoutMetadataOmitsOperationAndCollectionAttributes() throws SQLException {
        var statement = new SimpleStatement("SELECT 1", "one");

        forStatement(statement, Duration.ofSeconds(1)).execute(() -> "one");
        flush();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals("SimpleStatement", span.getName());
        assertNull(span.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertNull(span.getAttributes().get(DB_COLLECTION_NAME_KEY));

        HistogramPointData point = singlePoint(singleDurationMetric());
        assertEquals("SimpleStatement", point.getAttributes().get(STATEMENT_NAME_KEY));
        assertNull(point.getAttributes().get(DB_OPERATION_NAME_KEY));
        assertNull(point.getAttributes().get(DB_COLLECTION_NAME_KEY));
    }

    @Test
    void exceptionPathSetsErrorStatusAndRecordsException() {
        var statement = new FailingStatement("INSERT INTO albums VALUES (1)");

        SQLException thrown = assertThrows(
                SQLException.class,
                () -> forStatement(statement, Duration.ofSeconds(1)).execute(() -> {
                    throw new SQLException("boom");
                }));

        assertEquals("boom", thrown.getMessage());
        flush();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("boom", span.getStatus().getDescription());
        assertFalse(span.getEvents().isEmpty());
        assertEquals("exception", span.getEvents().get(0).getName());
    }

    @Test
    void slowQueryLogThresholdTriggersWarnLog() throws SQLException {
        var statement = new SlowStatement("SELECT pg_sleep(0)", 50L);

        forStatement(statement, Duration.ofNanos(1)).execute(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(statement.sleepMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        });
        flush();

        assertEquals(1, logger.warnings().size());
        String message = logger.warnings().get(0);
        assertTrue(message.contains("SlowStatement"), message);
        assertTrue(message.contains("seconds"), message);
    }

    @Test
    void parentSpanIsHonored() throws SQLException {
        var parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
        var statement = new SimpleStatement("SELECT 1", "one");

        StatementObservability observability = StatementObservability.forStatement(
                openTelemetry.getTracer("test"), durationHistogram, logger, "test-user",
                Duration.ofSeconds(1), statement, parent);
        observability.execute(() -> "one");
        parent.end();
        flush();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        SpanData statementSpan = spans.stream()
                .filter(s -> s.getName().equals("SimpleStatement"))
                .findFirst()
                .orElseThrow();
        assertEquals(parent.getSpanContext().getSpanId(), statementSpan.getParentSpanId());
    }

    @Test
    void eachStatementProducesItsOwnIndependentSpan() throws SQLException {
        var statementA = new MetadataStatement("SELECT a", "a", "SELECT", "a_table");
        var statementB = new MetadataStatement("SELECT b", "b", "SELECT", "b_table");

        forStatement(statementA, Duration.ofSeconds(1)).execute(() -> "a");
        forStatement(statementB, Duration.ofSeconds(1)).execute(() -> "b");
        flush();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        assertTrue(spans.stream().anyMatch(s -> "a_table".equals(s.getAttributes().get(DB_COLLECTION_NAME_KEY))));
        assertTrue(spans.stream().anyMatch(s -> "b_table".equals(s.getAttributes().get(DB_COLLECTION_NAME_KEY))));
    }

    private void flush() {
        tracerProvider.forceFlush().join(5, SECONDS);
        meterProvider.forceFlush().join(5, SECONDS);
    }

    private MetricData singleDurationMetric() {
        Collection<MetricData> metrics = metricReader.collectAllMetrics();
        assertEquals(1, metrics.size(), metrics::toString);
        return metrics.iterator().next();
    }

    private static HistogramPointData singlePoint(MetricData metric) {
        Collection<HistogramPointData> points = metric.getHistogramData().getPoints();
        assertEquals(1, points.size());
        return points.iterator().next();
    }

    record SimpleStatement(String sql, String result) implements Statement<String> {
        @Override
        public void bindParams(PreparedStatement preparedStatement) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public String decodeResultSet(ResultSet resultSet) {
            return result;
        }

        @Override
        public String decodeAffectedRows(long affectedRows) {
            return result;
        }
    }

    record MetadataStatement(
            String sql,
            String result,
            String opName,
            String colName)
            implements Statement<String> {
        @Override
        public void bindParams(PreparedStatement preparedStatement) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public String decodeResultSet(ResultSet resultSet) {
            return result;
        }

        @Override
        public String decodeAffectedRows(long affectedRows) {
            return result;
        }

        @Override
        public java.util.Optional<String> operationName() {
            return java.util.Optional.ofNullable(opName);
        }

        @Override
        public java.util.Optional<String> collectionName() {
            return java.util.Optional.ofNullable(colName);
        }
    }

    record FailingStatement(String sql) implements Statement<String> {
        @Override
        public void bindParams(PreparedStatement preparedStatement) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public String decodeResultSet(ResultSet resultSet) {
            return null;
        }

        @Override
        public String decodeAffectedRows(long affectedRows) {
            return null;
        }
    }

    record SlowStatement(String sql, long sleepMillis) implements Statement<String> {
        @Override
        public void bindParams(PreparedStatement preparedStatement) {}

        @Override
        public boolean returnsRows() {
            return false;
        }

        @Override
        public String decodeResultSet(ResultSet resultSet) {
            return "ok";
        }

        @Override
        public String decodeAffectedRows(long affectedRows) {
            return "ok";
        }
    }
}
