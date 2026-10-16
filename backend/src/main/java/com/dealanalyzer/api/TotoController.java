package com.dealanalyzer.api;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.account.AccountSessionCookie;
import com.dealanalyzer.ai.TotoAnswerTask;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * Toto, the process guide (ticket 120). otdog keeps no conversation: the client sends the question
 * and its recent turns each time, and the server keeps only usage counts. No case or account token is
 * required; a signed-in account's session cookie only selects the account's daily cap.
 */
@RestController
public class TotoController {
  private final TotoService toto;
  private final AccountJourneyAccess accounts;

  TotoController(TotoService toto, AccountJourneyAccess accounts) {
    this.toto = toto; this.accounts = accounts;
  }

  public record Availability(boolean available) {}
  public record TurnInput(String role, String text) {}
  public record QuestionInput(String question, List<TurnInput> turns) {}
  public record Response(TotoAnswerTask.Kind kind, List<TotoAnswerTask.AnswerParagraph> paragraphs, String message,
      List<TotoAnswerTask.Citation> suggestions) {}

  @GetMapping("/api/v1/toto")
  public Availability availability() {
    return new Availability(toto.available());
  }

  @PostMapping(value = "/api/v1/toto/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Response answer(@RequestBody(required = false) QuestionInput input,
      @CookieValue(value = AccountSessionCookie.NAME, required = false) String session, HttpServletRequest request) {
    String question = input == null ? null : input.question();
    if (question == null || question.isBlank() || question.length() > TotoService.QUESTION_LIMIT)
      throw new Problem(HttpStatus.BAD_REQUEST, "TOTO_QUESTION_INVALID",
          "Ask Toto a question of up to 1,000 characters.");
    var turns = turns(input.turns());
    // Never trust client-supplied forwarding headers, as the API peer limit does not.
    var answer = toto.answer(account(session), request.getRemoteAddr(), question, turns);
    // Provenance stays server-side; the client gets the agreed shape only.
    return new Response(answer.kind(), answer.paragraphs(), answer.message(), answer.suggestions());
  }

  private static List<TotoAnswerTask.Turn> turns(List<TurnInput> input) {
    if (input == null) return List.of();
    var turns = new ArrayList<TotoAnswerTask.Turn>();
    boolean valid = input.size() <= TotoService.TURNS;
    for (var turn : valid ? input : List.<TurnInput>of()) {
      TotoAnswerTask.Role role = turn == null ? null : switch (String.valueOf(turn.role())) {
        case "BUYER" -> TotoAnswerTask.Role.BUYER;
        case "TOTO" -> TotoAnswerTask.Role.TOTO;
        default -> null;
      };
      if (role == null || turn.text() == null || turn.text().isBlank() || turn.text().length() > TotoService.TURN_LIMIT) {
        valid = false;
        break;
      }
      turns.add(new TotoAnswerTask.Turn(role, turn.text()));
    }
    if (!valid) throw new Problem(HttpStatus.BAD_REQUEST, "TOTO_TURNS_INVALID",
        "Toto can read up to six earlier messages of up to 1,500 characters each. Start a new question.");
    return turns;
  }

  /** A signed-in Buyer's account; an absent, ended or unknown session asks as a client without one. */
  private String account(String session) {
    if (session == null) return null;
    try {
      return accounts.accountId(session);
    } catch (RuntimeException ended) {
      return null;
    }
  }

  static final class Problem extends RuntimeException {
    final HttpStatus status;
    final String code;
    Problem(HttpStatus status, String code, String detail) {
      super(detail, null, false, false);
      this.status = status; this.code = code;
    }
  }

  /** Every refusal has a code and fixed wording; nothing echoes the question or turns. */
  @RestControllerAdvice(assignableTypes = TotoController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(Problem.class)
    ResponseEntity<ProblemDetail> problem(Problem problem) {
      return body(problem.status, problem.code, problem.getMessage());
    }

    @ExceptionHandler(TotoService.Refused.class)
    ResponseEntity<ProblemDetail> refused(TotoService.Refused refused) {
      return refused.refusal == TotoService.Refusal.RESTING
          ? body(HttpStatus.TOO_MANY_REQUESTS, "TOTO_RESTING", TotoService.RESTING)
          : body(HttpStatus.SERVICE_UNAVAILABLE, "TOTO_UNAVAILABLE", "Toto isn't available right now.");
    }

    /** Handled here so the parser's message, which can quote the input, is neither returned nor logged. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      return body(HttpStatus.BAD_REQUEST, "TOTO_QUESTION_INVALID", "Toto couldn't read that question. Try asking again.");
    }

    private static ResponseEntity<ProblemDetail> body(HttpStatus status, String code, String detail) {
      var body = ProblemDetail.forStatusAndDetail(status, detail);
      body.setProperty("code", code);
      return ResponseEntity.status(status).body(body);
    }
  }
}
