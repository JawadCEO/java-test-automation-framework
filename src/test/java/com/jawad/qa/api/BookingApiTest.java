package com.jawad.qa.api;

import com.jawad.booking.BookingApp;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("api")
class BookingApiTest {
    private static BookingApp app;

    @BeforeAll
    static void start() throws Exception {
        app = new BookingApp();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    private static RequestSpecification api() {
        return given().baseUri(app.url());
    }

    private static String create(String name) {
        return api().contentType("application/json").body(Map.of("name", name))
            .post("/api/bookings").then().statusCode(201).extract().path("id");
    }

    @Test
    void createsAndRetrievesBooking() {
        var created = api().contentType("application/json").body(Map.of("name", "Ada"))
            .post("/api/bookings")
            .then().statusCode(201)
            .contentType(startsWith("application/json"))
            .body("name", equalTo("Ada"))
            .body("id", matchesPattern("[0-9a-f-]{36}"))
            .extract();
        String id = created.path("id");
        assertEquals("/api/bookings/" + id, created.header("Location"));
        api().get(created.header("Location")).then()
            .statusCode(200).body("id", equalTo(id)).body("name", equalTo("Ada"));
    }

    @Test
    void deletesBookingAndThenReportsItMissing() {
        String id = create("Grace");
        api().delete("/api/bookings/" + id).then().statusCode(204).body(emptyString());
        api().get("/api/bookings/" + id).then().statusCode(404).body("error", equalTo("Booking not found"));
        api().delete("/api/bookings/" + id).then().statusCode(404);
    }

    @Test
    void listsNewestBookingsWithinLimit() {
        for (int i = 0; i < 4; i++) {
            create("List attendee " + i);
        }
        api().queryParam("limit", 2).get("/api/bookings").then()
            .statusCode(200)
            .body("items", hasSize(2))
            .body("total", greaterThanOrEqualTo(4))
            .body("items.name", everyItem(startsWith("List attendee")));
    }

    @Test
    void responsesCarryRequestIdHeader() {
        api().get("/api/bookings").then().statusCode(200)
            .header("X-Request-Id", matchesPattern("[0-9a-f-]{36}"));
    }

    static Stream<Arguments> invalidCreatePayloads() {
        return Stream.of(
            Arguments.of("blank name", "{\"name\":\" \"}"),
            Arguments.of("empty object", "{}"),
            Arguments.of("malformed JSON", "{"),
            Arguments.of("name is a number", "{\"name\":42}"),
            Arguments.of("name is null", "{\"name\":null}"),
            Arguments.of("name is an array", "{\"name\":[\"Ada\"]}"),
            Arguments.of("JSON array body", "[]"),
            Arguments.of("81 characters", "{\"name\":\"" + "x".repeat(81) + "\"}"),
            Arguments.of("control character", "{\"name\":\"Ada\\u0007\"}"));
    }

    @ParameterizedTest(name = "[{index}] 400 for {0}")
    @MethodSource("invalidCreatePayloads")
    void rejectsInvalidPayloads(String description, String body) {
        api().contentType("application/json").body(body).post("/api/bookings").then()
            .statusCode(400).body("error", equalTo("Name must contain 1 to 80 characters"));
    }

    @Test
    void rejectsOversizedBody() {
        String body = "{\"name\":\"Ada\",\"padding\":\"" + "x".repeat(5000) + "\"}";
        api().contentType("application/json").body(body).post("/api/bookings").then().statusCode(413);
    }

    @ParameterizedTest(name = "[{index}] 415 for Content-Type {0}")
    @CsvSource({"text/plain", "application/xml", "application/x-www-form-urlencoded"})
    void rejectsNonJsonContentType(String contentType) {
        api().contentType(contentType).body("name=Ada").post("/api/bookings").then().statusCode(415);
    }

    @ParameterizedTest(name = "[{index}] {0} {1} -> {2}")
    @CsvSource({
        "GET, /api/bookings/invalid, 400",
        "GET, /api/bookings/00000000-0000-0000-0000-000000000001, 404",
        "DELETE, /api/bookings/not-a-uuid, 400",
        "PUT, /api/bookings, 405",
        "PATCH, /api/bookings/00000000-0000-0000-0000-000000000001, 405",
        "POST, /health, 405",
        "GET, /api/unknown, 404",
        "GET, /api/bookings?limit=abc, 400",
    })
    void returnsExpectedStatusForBadRequests(String method, String path, int status) {
        api().urlEncodingEnabled(false).request(method, path).then().statusCode(status)
            .body("error", not(emptyOrNullString()));
    }

    @Test
    void methodNotAllowedAdvertisesAllowedMethods() {
        api().put("/api/bookings").then().statusCode(405).header("Allow", "GET, POST");
        api().post("/api/bookings/" + UUID.randomUUID()).then().statusCode(405).header("Allow", "GET, DELETE");
    }
}
