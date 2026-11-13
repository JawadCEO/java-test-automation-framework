Feature: Seat booking
  Scenario: An attendee receives confirmation
    Given the booking desk is open
    When an attendee submits the name "Ada"
    Then the booking is confirmed for "Ada"
