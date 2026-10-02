package com.dealanalyzer.purchase;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class PurchaseFailureHandler {
  @ExceptionHandler(PurchaseService.PurchaseFailure.class)
  public ResponseEntity<Map<String, String>> handle(PurchaseService.PurchaseFailure failure) {
    var body = new LinkedHashMap<String, String>(); body.put("code", failure.code);
    if (failure.offerId != null) body.put("offerId", failure.offerId);
    return ResponseEntity.status(failure.status).body(body);
  }
}
