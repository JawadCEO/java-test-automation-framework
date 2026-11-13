package com.jawad.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Seat booking service: HTML page, JSON API, health, metrics and its own OpenAPI document. */
public final class BookingApp implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(BookingApp.class.getName());
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(16);
    private final BookingDatabase database;
    private final RequestMetrics metrics = new RequestMetrics();

    /** Starts on a free loopback port, which keeps parallel test classes isolated. */
    public BookingApp() throws IOException {
        this(0);
    }

    public BookingApp(int port) throws IOException {
        database = new BookingDatabase();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException failure) {
            database.close();
            throw failure;
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public BookingDatabase database() {
        return database;
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
                LOG.log(System.Logger.Level.ERROR, "Database failure for request " + requestId, failure);
                json(exchange, 500, Map.of("error", "Booking storage unavailable"));
            }
        } finally {
            int status = exchange.getResponseCode();
            if (path.startsWith("/api/") && status >= 200) {
                long duration = System.nanoTime() - started;
                metrics.record(status, duration);
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "request_id=%s method=%s status=%d duration_ns=%d",
                    requestId, exchange.getRequestMethod(), status, duration));
            }
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException, SQLException {
        String method = exchange.getRequestMethod();
        switch (path) {
            case "/" -> allow(exchange, method, "GET", () -> send(exchange, 200, "text/html", PAGE));
            case "/health" -> allow(exchange, method, "GET", () -> {
                boolean ready = database.ready();
                json(exchange, ready ? 200 : 503, Map.of("status", ready ? "UP" : "DOWN"));
            });
            case "/metrics" -> allow(exchange, method, "GET",
                () -> send(exchange, 200, "text/plain; version=0.0.4", metrics.render()));
            case "/openapi.yaml" -> allow(exchange, method, "GET",
                () -> send(exchange, 200, "application/yaml", openApiDocument()));
            case "/api/bookings" -> {
                if (method.equals("POST")) {
                    createBooking(exchange);
                } else if (method.equals("GET")) {
                    listBookings(exchange);
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
            }
            default -> {
                if (path.startsWith("/api/bookings/")) {
                    String id = path.substring("/api/bookings/".length());
                    if (method.equals("GET")) {
                        findBooking(exchange, id);
                    } else if (method.equals("DELETE")) {
                        deleteBooking(exchange, id);
                    } else {
                        methodNotAllowed(exchange, "GET, DELETE");
                    }
                } else {
                    json(exchange, 404, Map.of("error", "Not found"));
                }
            }
        }
    }

    private void createBooking(HttpExchange exchange) throws IOException, SQLException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            json(exchange, 415, Map.of("error", "Content-Type must be application/json"));
            return;
        }
        var body = exchange.getRequestBody().readNBytes(BookingRules.MAX_BODY_BYTES + 1);
        if (body.length > BookingRules.MAX_BODY_BYTES) {
            json(exchange, 413, Map.of("error", "Request too large"));
            return;
        }
        String raw;
        try {
            var document = JSON.readTree(body);
            var field = document == null ? null : document.get("name");
            raw = field != null && field.isTextual() ? field.textValue() : null;
        } catch (JsonProcessingException invalidJson) {
            raw = null;
        }
        var name = BookingRules.normalizeName(raw);
        if (name.isEmpty()) {
            json(exchange, 400, Map.of("error", "Name must contain 1 to 80 characters"));
            return;
        }
        UUID id = UUID.randomUUID();
        database.insert(id, name.get());
        exchange.getResponseHeaders().set("Location", "/api/bookings/" + id);
        json(exchange, 201, Map.of("id", id, "name", name.get()));
    }

    private void listBookings(HttpExchange exchange) throws IOException, SQLException {
        String query = exchange.getRequestURI().getQuery();
        String limitParameter = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("limit=")) {
                    limitParameter = pair.substring("limit=".length());
                }
            }
        }
        int limit = BookingRules.pageSize(limitParameter, 20);
        if (limit < 0) {
            json(exchange, 400, Map.of("error", "limit must be an integer"));
            return;
        }
        json(exchange, 200, Map.of("total", database.count(), "items", database.list(limit)));
    }

    private void findBooking(HttpExchange exchange, String identifier) throws IOException, SQLException {
        var id = BookingRules.parseId(identifier);
        if (id.isEmpty()) {
            json(exchange, 400, Map.of("error", "Invalid booking ID"));
            return;
        }
        String name = database.find(id.get());
        if (name == null) {
            json(exchange, 404, Map.of("error", "Booking not found"));
            return;
        }
        json(exchange, 200, Map.of("id", id.get(), "name", name));
    }

    private void deleteBooking(HttpExchange exchange, String identifier) throws IOException, SQLException {
        var id = BookingRules.parseId(identifier);
        if (id.isEmpty()) {
            json(exchange, 400, Map.of("error", "Invalid booking ID"));
            return;
        }
        if (!database.delete(id.get())) {
            json(exchange, 404, Map.of("error", "Booking not found"));
            return;
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private interface Handler {
        void run() throws IOException, SQLException;
    }

    private void allow(HttpExchange exchange, String method, String allowed, Handler handler)
        throws IOException, SQLException {
        if (method.equals(allowed)) {
            handler.run();
        } else {
            methodNotAllowed(exchange, allowed);
        }
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        json(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private static String openApiDocument() throws IOException {
        try (InputStream spec = BookingApp.class.getResourceAsStream("/openapi.yaml")) {
            if (spec == null) {
                throw new IOException("openapi.yaml is missing from the classpath");
            }
            return new String(spec.readAllBytes(), StandardCharsets.UTF_8);
        }
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
          <h2>Recent bookings</h2>
          <ul aria-label="Recent bookings"></ul>
          <script>
            const status = document.querySelector('[role=status]');
            const list = document.querySelector('ul');
            async function refresh() {
              const response = await fetch('/api/bookings?limit=5');
              const page = await response.json();
              list.replaceChildren(...page.items.map((booking) => {
                const item = document.createElement('li');
                item.textContent = booking.name;
                return item;
              }));
            }
            document.querySelector('form').addEventListener('submit', async (event) => {
              event.preventDefault();
              try {
                const response = await fetch('/api/bookings', {
                  method: 'POST',
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify({name: document.querySelector('#name').value})
                });
                const booking = await response.json();
                status.textContent = response.ok ? 'Booked: ' + booking.name : booking.error;
                if (response.ok) {
                  document.querySelector('#name').value = '';
                  await refresh();
                }
              } catch {
                status.textContent = 'Booking service unavailable';
              }
            });
            refresh().catch(() => { status.textContent = 'Booking service unavailable'; });
          </script>
        </html>
        """;
}
