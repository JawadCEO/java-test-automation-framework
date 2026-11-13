package com.jawad.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.sql.SQLException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BookingApp implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final BookingDatabase database;
    private final LongAdder completedRequests = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();

    BookingDatabase database() {
        return database;
    }

    BookingApp() throws IOException {
        database = new BookingDatabase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        String path = exchange.getRequestURI().getPath();
        try (exchange) {
            try {
                route(exchange, path);
            } catch (SQLException failure) {
                System.getLogger(BookingApp.class.getName()).log(
                    System.Logger.Level.ERROR, "Database failure for request " + requestId, failure);
                json(exchange, 500, Map.of("error", "Booking storage unavailable"));
            }
        } finally {
            if (path.startsWith("/api/") && exchange.getResponseCode() >= 200) {
                long duration = System.nanoTime() - started;
                int status = exchange.getResponseCode();
                totalDurationNanos.add(duration);
                if (status >= 500) {
                    serverErrors.increment();
                } else if (status >= 400) {
                    clientErrors.increment();
                }
                completedRequests.increment();
                System.getLogger(BookingApp.class.getName()).log(System.Logger.Level.DEBUG,
                    () -> String.format("request_id=%s method=%s status=%d duration_ns=%d",
                        requestId, exchange.getRequestMethod(), status, duration));
            }
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException, SQLException {
        String method = exchange.getRequestMethod();
        if (path.equals("/") && method.equals("GET")) {
            send(exchange, 200, "text/html", PAGE);
        } else if (path.equals("/health") && method.equals("GET")) {
            boolean ready = database.ready();
            json(exchange, ready ? 200 : 503, Map.of("status", ready ? "UP" : "DOWN"));
        } else if (path.equals("/metrics") && method.equals("GET")) {
            send(exchange, 200, "text/plain; version=0.0.4", """
                # TYPE booking_api_requests_total counter
                booking_api_requests_total %d
                # TYPE booking_api_client_errors_total counter
                booking_api_client_errors_total %d
                # TYPE booking_api_server_errors_total counter
                booking_api_server_errors_total %d
                # TYPE booking_api_duration_seconds_total counter
                booking_api_duration_seconds_total %s
                """.formatted(completedRequests.sum(), clientErrors.sum(), serverErrors.sum(),
                    totalDurationNanos.sum() / 1_000_000_000.0));
        } else if (path.equals("/api/bookings") && method.equals("POST")) {
            createBooking(exchange);
        } else if (path.startsWith("/api/bookings/") && method.equals("GET")) {
            findBooking(exchange, path.substring("/api/bookings/".length()));
        } else {
            json(exchange, 404, Map.of("error", "Not found"));
        }
    }

    private void createBooking(HttpExchange exchange) throws IOException, SQLException {
        var body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            json(exchange, 413, Map.of("error", "Request too large"));
            return;
        }
        String name;
        try {
            var document = JSON.readTree(body);
            var field = document == null ? null : document.get("name");
            name = field != null && field.isTextual() ? field.textValue().strip() : "";
        } catch (JsonProcessingException invalidJson) {
            name = "";
        }
        if (name.isBlank() || name.length() > 80) {
            json(exchange, 400, Map.of("error", "Name must contain 1 to 80 characters"));
            return;
        }
        UUID id = UUID.randomUUID();
        database.insert(id, name);
        exchange.getResponseHeaders().set("Location", "/api/bookings/" + id);
        json(exchange, 201, Map.of("id", id, "name", name));
    }

    private void findBooking(HttpExchange exchange, String identifier) throws IOException, SQLException {
        UUID id;
        try {
            id = UUID.fromString(identifier);
        } catch (IllegalArgumentException invalidId) {
            json(exchange, 400, Map.of("error", "Invalid booking ID"));
            return;
        }
        String name = database.find(id);
        if (name == null) {
            json(exchange, 404, Map.of("error", "Booking not found"));
            return;
        }
        json(exchange, 200, Map.of("id", id, "name", name));
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", JSON.writeValueAsString(body));
    }

    private void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        database.close();
    }

    private static final String PAGE = """
        <!doctype html>
        <html lang="en">
          <meta charset="utf-8">
          <title>Booking desk</title>
          <h1>Booking desk</h1>
          <form>
            <label for="name">Attendee name</label>
            <input id="name" required maxlength="80">
            <button type="submit">Book seat</button>
          </form>
          <p role="status"></p>
          <script>
            document.querySelector('form').addEventListener('submit', async (event) => {
              event.preventDefault();
              const status = document.querySelector('[role=status]');
              try {
                const response = await fetch('/api/bookings', {
                  method: 'POST',
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({name: document.querySelector('#name').value})
                });
                const booking = await response.json();
                status.textContent = response.ok ? 'Booked: ' + booking.name : booking.error;
              } catch {
                status.textContent = 'Booking service unavailable';
              }
            });
          </script>
        </html>
        """;
}
