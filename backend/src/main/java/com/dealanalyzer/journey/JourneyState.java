package com.dealanalyzer.journey;

/** Buying Journey states (spec section 6). Ticket 51 only creates journeys in SHOPPING. */
public enum JourneyState { SHOPPING, NEGOTIATING, AGREED, TEST_DRIVE_SCHEDULED, CLOSED, ABANDONED }
