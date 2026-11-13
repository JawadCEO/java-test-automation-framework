Feature: Seat booking
  Attendees reserve a seat at the booking desk and can cancel it later.

  Background:
    Given the booking desk is open

  Scenario: An attendee receives confirmation
    When an attendee submits the name "Ada"
    Then the booking is confirmed for "Ada"
    And the booking can be looked up by its ID

  Scenario: An attendee cancels a booking
    Given an attendee has booked a seat as "Grace"
    When the attendee cancels the booking
    Then the booking can no longer be found

  Scenario Outline: The desk rejects an unusable name
    When an attendee submits the name "<name>"
    Then the booking is rejected with status 400

    Examples:
      | name |
      |  |
      | xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx |
