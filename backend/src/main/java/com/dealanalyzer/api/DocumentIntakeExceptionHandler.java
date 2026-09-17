package com.dealanalyzer.api;

import com.dealanalyzer.document.InvalidDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@org.springframework.core.annotation.Order(0)
@RestControllerAdvice
class DocumentIntakeExceptionHandler {
  @ExceptionHandler(InvalidDocumentException.class)
  ProblemDetail invalidDocument(InvalidDocumentException exception) {
    boolean unavailable = exception.code().equals("PARSER_BUSY") || exception.code().equals("PARSER_UNAVAILABLE");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle(unavailable ? "Document processing is temporarily unavailable" : "Dealer document was rejected");
    problem.setProperty("code", exception.code());
    problem.setProperty("retryable", true);
    return problem;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ProblemDetail requestTooLarge() {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "The document exceeds the configured upload limit. Upload a smaller PDF or image.");
    problem.setTitle("Dealer document was rejected");
    problem.setProperty("code", "FILE_TOO_LARGE");
    problem.setProperty("retryable", true);
    return problem;
  }
}
