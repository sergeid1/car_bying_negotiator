package com.dealanalyzer.api;

import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class VehicleEvidenceExceptionHandler {
  @ExceptionHandler(InvalidVehicleEvidenceException.class)
  ProblemDetail invalidVehicleEvidence(InvalidVehicleEvidenceException exception) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Vehicle evidence is invalid");
    return problem;
  }
}
