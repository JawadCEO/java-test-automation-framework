package com.jawad.qa;

import java.util.concurrent.CountDownLatch;

public final class FixtureMain {
    public static void main(String[] args) throws Exception {
        try (var app = new BookingApp()) {
            System.out.println("BOOKING_URL=" + app.url());
            new CountDownLatch(1).await();
        }
    }
}
