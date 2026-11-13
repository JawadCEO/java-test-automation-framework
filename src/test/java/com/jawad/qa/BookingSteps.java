package com.jawad.qa;

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

    @Given("the booking desk is open")
    public void openDesk() throws IOException {
        app = new BookingApp();
    }

    @When("an attendee submits the name {string}")
    public void submitName(String name) {
        response = given().baseUri(app.url()).contentType("application/json")
            .body(Map.of("name", name)).post("/api/bookings");
    }

    @Then("the booking is confirmed for {string}")
    public void verifyBooking(String name) {
        response.then().statusCode(201).body("name", equalTo(name));
    }

    @After
    public void closeDesk() {
        if (app != null) {
            app.close();
        }
    }
}
