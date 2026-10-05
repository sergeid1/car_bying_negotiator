package com.dealanalyzer.relay;

/**
 * How an approved dealer message leaves (spec 2.1 section 2.1), recorded on every outbound dealer message.
 * {@code RELAY} is the platform relay (every message sent before ticket 82); {@code OWN_EMAIL_APP} is the
 * Buyer sending from their own mail app; {@code CONNECTED_MAILBOX} is reserved for ticket 83.
 */
public enum SendPath { RELAY, OWN_EMAIL_APP, CONNECTED_MAILBOX }
