package com.jawad.booking;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** Counters exposed at /metrics in the Prometheus text exposition format. */
public final class RequestMetrics {
    private final LongAdder completedRequests = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();

    public void record(int status, long durationNanos) {
        if (status >= 500) {
            serverErrors.increment();
        } else if (status >= 400) {
            clientErrors.increment();
        }
        totalDurationNanos.add(durationNanos);
        completedRequests.increment();
    }

    public long requests() {
        return completedRequests.sum();
    }

    public String render() {
        return String.format(Locale.ROOT, """
            # TYPE booking_api_requests_total counter
            booking_api_requests_total %d
            # TYPE booking_api_client_errors_total counter
            booking_api_client_errors_total %d
            # TYPE booking_api_server_errors_total counter
            booking_api_server_errors_total %d
            # TYPE booking_api_duration_seconds_total counter
            booking_api_duration_seconds_total %.6f
            """, completedRequests.sum(), clientErrors.sum(), serverErrors.sum(),
            totalDurationNanos.sum() / 1_000_000_000.0);
    }
}
