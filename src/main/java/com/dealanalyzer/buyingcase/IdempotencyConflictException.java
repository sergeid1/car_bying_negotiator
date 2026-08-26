package com.dealanalyzer.buyingcase;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("Idempotency key was already used for another analysis request");
  }
}
