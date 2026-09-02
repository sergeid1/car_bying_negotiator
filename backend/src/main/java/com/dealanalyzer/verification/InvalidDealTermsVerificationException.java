package com.dealanalyzer.verification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class InvalidDealTermsVerificationException extends RuntimeException {
  public InvalidDealTermsVerificationException(String message) {
    super(message);
  }
}
