package com.dealanalyzer.economics;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class InvalidCashEconomicsException extends RuntimeException {
  public InvalidCashEconomicsException(String message) {
    super(message);
  }
}
