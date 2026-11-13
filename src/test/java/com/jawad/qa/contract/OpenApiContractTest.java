package com.jawad.qa.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import com.jawad.booking.BookingApp;
import io.restassured.specification.RequestSpecification;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-side contract tests: every request and response below is checked against openapi.yaml.
 * A field rename, a new undocumented status code or a schema change fails the build.
 */
@Tag("contract")
class OpenApiContractTest {
    private static final String SPEC = "openapi.yaml";
    private static BookingApp app;
    private static OpenApiValidationFilter strict;
    private static OpenApiValidationFilter responsesOnly;
    private static OpenApiInteractionValidator validator;

    @BeforeAll
    static void start() throws Exception {
        app = new BookingApp();
        validator = OpenApiInteractionValidator.createForSpecificationUrl(SPEC).build();
        strict = new OpenApiValidationFilter(validator);
        // Negative tests send requests the spec forbids on purpose; only the response must conform.
        responsesOnly = new OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl(SPEC)
            .withLevelResolver(LevelResolver.create()
                .withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build());
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    private static RequestSpecification strictApi() {
        return given().baseUri(app.url()).filter(strict);
    }

    private static RequestSpecification responseApi() {
        return given().baseUri(app.url()).filter(responsesOnly);
    }

    private static String createBooking(String name) {
        return strictApi().contentType("application/json").body(Map.of("name", name))
            .post("/api/bookings").then().statusCode(201).extract().path("id");
    }

    @Test
    void createBookingMatchesContract() {
        createBooking("Contract Ada");
    }

    @Test
    void getBookingMatchesContract() {
        String id = createBooking("Contract Grace");
        strictApi().get("/api/bookings/" + id).then().statusCode(200);
    }

    @Test
    void listBookingsMatchesContract() {
        createBooking("Contract Linus");
        strictApi().queryParam("limit", 10).get("/api/bookings").then().statusCode(200);
    }

    @Test
    void deleteBookingMatchesContract() {
        String id = createBooking("Contract Barbara");
        strictApi().delete("/api/bookings/" + id).then().statusCode(204);
    }

    @Test
    void healthMatchesContract() {
        strictApi().get("/health").then().statusCode(200);
    }

    @Test
    void validationErrorMatchesErrorSchema() {
        responseApi().contentType("application/json").body("{\"name\":\"\"}")
            .post("/api/bookings").then().statusCode(400);
    }

    @Test
    void unsupportedMediaTypeMatchesErrorSchema() {
        responseApi().contentType("text/plain").body("Ada").post("/api/bookings").then().statusCode(415);
    }

    @Test
    void notFoundMatchesErrorSchema() {
        strictApi().get("/api/bookings/00000000-0000-0000-0000-000000000001").then().statusCode(404);
    }

    @Test
    void invalidIdMatchesErrorSchema() {
        strictApi().delete("/api/bookings/not-a-uuid").then().statusCode(400);
    }

    @Test
    void unavailableDatabaseMatchesHealthContract() throws SQLException {
        try (var isolated = new BookingApp(); var connection = isolated.database().connect();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE booking");
            given().baseUri(isolated.url()).filter(strict).get("/health").then().statusCode(503);
        } catch (java.io.IOException failure) {
            fail(failure);
        }
    }

    @Test
    void servedDocumentIsTheContractUnderTest() throws Exception {
        String served = given().baseUri(app.url()).get("/openapi.yaml").then().statusCode(200)
            .extract().asString();
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(SPEC)) {
            assertNotNull(source);
            assertEquals(new String(source.readAllBytes(), StandardCharsets.UTF_8), served);
        }
    }

    /** Proves the validator would catch drift: these responses break the contract and must be reported. */
    @Test
    void validatorDetectsUndocumentedFieldAndStatus() {
        var extraField = SimpleResponse.Builder.status(200).withContentType("application/json")
            .withBody("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Ada\",\"seat\":\"A1\"}")
            .build();
        assertTrue(validator.validateResponse("/api/bookings/00000000-0000-0000-0000-000000000001",
            Request.Method.GET, extraField).hasErrors(), "Undocumented field must be reported");

        var wrongType = SimpleResponse.Builder.status(201).withContentType("application/json")
            .withHeader("Location", "/api/bookings/x").withBody("{\"id\":42,\"name\":\"Ada\"}").build();
        assertTrue(validator.validateResponse("/api/bookings", Request.Method.POST, wrongType).hasErrors(),
            "Numeric id must be reported");

        var undocumentedStatus = SimpleResponse.Builder.status(418).build();
        assertTrue(validator.validateResponse("/health", Request.Method.GET, undocumentedStatus).hasErrors(),
            "Undocumented status must be reported");
    }
}
