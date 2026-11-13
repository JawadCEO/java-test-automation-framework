package com.jawad.qa.bdd;

import com.jawad.booking.BookingApp;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.io.IOException;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

public class BookingSteps {
    private BookingApp app;
    private Response response;
    private String bookingId;

    @Given("the booking desk is open")
    public void openDesk() throws IOException {
        app = new BookingApp();
    }

    @When("an attendee submits the name {string}")
    public void submitName(String name) {
        response = given().baseUri(app.url()).contentType("application/json")
            .body(Map.of("name", name)).post("/api/bookings");
    }

    @Given("an attendee has booked a seat as {string}")
    public void existingBooking(String name) {
        submitName(name);
        bookingId = response.then().statusCode(201).extract().path("id");
    }

    @When("the attendee cancels the booking")
    public void cancel() {
        given().baseUri(app.url()).delete("/api/bookings/" + bookingId).then().statusCode(204);
    }

    @Then("the booking is confirmed for {string}")
    public void verifyBooking(String name) {
        bookingId = response.then().statusCode(201).body("name", equalTo(name)).extract().path("id");
    }

    @Then("the booking can be looked up by its ID")
    public void lookUp() {
        given().baseUri(app.url()).get("/api/bookings/" + bookingId).then().statusCode(200)
            .body("id", equalTo(bookingId));
    }

    @Then("the booking can no longer be found")
    public void gone() {
        given().baseUri(app.url()).get("/api/bookings/" + bookingId).then().statusCode(404);
    }

    @Then("the booking is rejected with status {int}")
    public void rejected(int status) {
        response.then().statusCode(status);
    }

    @After
    public void closeDesk() {
        if (app != null) {
            app.close();
        }
    }
}
