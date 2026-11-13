package com.jawad.qa.unit;

import com.jawad.booking.RequestMetrics;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RequestMetricsTest {
    @Test
    void classifiesStatusCodes() {
        var metrics = new RequestMetrics();
        metrics.record(201, 1_000_000);
        metrics.record(404, 2_000_000);
        metrics.record(415, 1_000_000);
        metrics.record(503, 500_000_000);
        String text = metrics.render();
        assertTrue(text.contains("booking_api_requests_total 4\n"), text);
        assertTrue(text.contains("booking_api_client_errors_total 2\n"), text);
        assertTrue(text.contains("booking_api_server_errors_total 1\n"), text);
        assertTrue(text.contains("booking_api_duration_seconds_total 0.504000\n"), text);
    }

    @Test
    void countsEveryRequestUnderConcurrency() {
        var metrics = new RequestMetrics();
        IntStream.range(0, 10_000).parallel().forEach(i -> metrics.record(200, 1));
        assertEquals(10_000, metrics.requests());
    }

    @Test
    void rendersEachMetricWithATypeLine() {
        String[] lines = new RequestMetrics().render().split("\n");
        assertEquals(8, lines.length);
        for (int i = 0; i < lines.length; i += 2) {
            assertTrue(lines[i].startsWith("# TYPE booking_api_"), lines[i]);
            assertTrue(lines[i].endsWith(" counter"), lines[i]);
        }
    }
}
