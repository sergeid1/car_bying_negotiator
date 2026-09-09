package com.dealanalyzer.recovery;
/** External delivery boundary. Implementations must never log the link or recipient. */
public interface RecoveryEmail { void send(String recipient, String link); }
