package com.jawad.booking;

import java.util.concurrent.CountDownLatch;

/** Runs the service until the process is stopped. BOOKING_PORT selects a fixed port; the default is any free port. */
public final class BookingServer {
    private BookingServer() {
    }

    public static void main(String[] args) throws Exception {
        String configured = System.getenv().getOrDefault("BOOKING_PORT", "0");
        try (var app = new BookingApp(Integer.parseInt(configured))) {
            System.out.println("BOOKING_URL=" + app.url());
            new CountDownLatch(1).await();
        }
    }
}
