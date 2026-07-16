package io.codemine.java.richpg;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TelemetryTest {

  private static OpenTelemetry sdkWith(InMemorySpanExporter exporter) {
    return OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build())
        .build();
  }

  private static RichPgConfig config(OpenTelemetry otel) {
    return RichPgConfig.defaults("jdbc:postgresql://localhost/test", "u", "p")
        .withOpenTelemetry(otel)
        .withArtifactName("music-catalogue");
  }

  @Test
  void redactUrlMasksPassword() {
    assertThat(Telemetry.redactUrl("jdbc:postgresql://h/db?password=secret&x=1"))
        .isEqualTo("jdbc:postgresql://h/db?password=***&x=1");
    assertThat(Telemetry.redactUrl("jdbc:postgresql://h/db?password=secret"))
        .isEqualTo("jdbc:postgresql://h/db?password=***");
    assertThat(Telemetry.redactUrl("jdbc:postgresql://h/db")).isEqualTo("jdbc:postgresql://h/db");
  }

  @Test
  void sessionCloseSpanRecordsRemainingConnections() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    HikariPoolMXBean pool = Mockito.mock(HikariPoolMXBean.class);
    Telemetry telemetry = Telemetry.forSession(config(sdkWith(exporter)), pool);

    telemetry.startClose().finish(2);

    var spans = exporter.getFinishedSpanItems();
    var closeSpan =
        spans.stream().filter(s -> s.getName().equals("session.close")).findFirst().orElseThrow();
    assertThat(
            closeSpan
                .getAttributes()
                .get(
                    io.opentelemetry.api.common.AttributeKey.longKey(
                        "pgenie.session.close.connections_remaining")))
        .isEqualTo(2L);
    assertThat(closeSpan.getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
  }
}
