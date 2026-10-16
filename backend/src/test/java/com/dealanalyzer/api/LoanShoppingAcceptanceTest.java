package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.Result;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.call;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.put;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.desk.tactic.DeskTacticPolicy;
import com.dealanalyzer.loan.LoanShoppingService;
import com.dealanalyzer.loan.preapproval.CreditAuthorization;
import com.dealanalyzer.loan.preapproval.DeterministicFakePreApprovalAdapter;
import com.dealanalyzer.loan.preapproval.LoanApplication;
import com.dealanalyzer.loan.preapproval.PreApprovalAdapter;
import com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;
import com.dealanalyzer.partner.PartnerOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Ticket 129: one loan application, the written credit authorization, soft-inquiry pre-approvals ordered by total
 * cost, and the chosen one as the Buyer's own financing at the desk (LOAN-01 to LOAN-03).
 */
@ExtendWith(OutputCaptureExtension.class)
class LoanShoppingAcceptanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String VIN = "1HGCM82633A004352";
  static final String CREDIT_UNION = "Space Coast Credit Union";
  static final Map<String, Object> AUTHORIZATION = Map.of("authorizationTextVersion", LoanShoppingService.AUTHORIZATION_VERSION,
      "authorized", true);
  @TempDir Path directory;

  /** MANY (several lenders, listed out of cost order), HARD (a hard inquiry) or FAKE (the ticket 128 fake). */
  static volatile String partner = "MANY";
  /** Every application the partner adapter received, with the authorization it came with. */
  static final CopyOnWriteArrayList<Object[]> sent = new CopyOnWriteArrayList<>();

  @TestConfiguration @Import(ShortlistAcceptanceTest.Configuration.class)
  static class Configuration {
    @Bean @Primary PreApprovalAdapter recordingLendingPartner() {
      var fake = new DeterministicFakePreApprovalAdapter();
      return new PreApprovalAdapter() {
        @Override public Provider provider() { return fake.provider(); }
        @Override public Result request(LoanApplication application, CreditAuthorization authorization) {
          sent.add(new Object[] {application, authorization});
          if (partner.equals("FAKE")) return fake.request(application, authorization);
          Instant now = Instant.now();
          var lenders = partner.equals("HARD")
              ? List.of(offer(application, "Harbor Lending", "5.49", 0, null, InquiryType.HARD, now))
              : List.of(
                  offer(application, "Harbor Lending", "6.99", 0, 99_999L, InquiryType.SOFT, now),
                  new LenderEntry("Keys Bank", PartnerOutcome.DECLINED, null),
                  offer(application, "Bayfront Credit", "5.49", 39_500, null, InquiryType.SOFT, now),
                  new LenderEntry("Pelican Lending", PartnerOutcome.TIMED_OUT, null),
                  offer(application, "Coastal Auto Finance", "5.49", 0, null, InquiryType.SOFT, now));
          return new Result(Availability.AVAILABLE, null, provider(), now, BigDecimal.ZERO, lenders);
        }
      };
    }
  }

  static LenderEntry offer(LoanApplication application, String lender, String apr, long fees, Long payment, InquiryType inquiry,
      Instant now) {
    return new LenderEntry(lender, PartnerOutcome.OFFERED, new PreApproval(application.amountMinor(), "USD", new BigDecimal(apr),
        application.termMonths(), fees, payment, new Conditions(10, 120_000, 0), now.plusSeconds(86_400 * 30), inquiry,
        URI.create("https://lender.invalid/" + lender.toLowerCase().replace(' ', '-')), new Source("loan-" + lender.length(), now)));
  }

  @BeforeEach void clear() { sent.clear(); partner = "MANY"; ShortlistAcceptanceTest.links.clear(); }

  @Test void aCaseApplicationNeedsTheAuthorizationKeepsOnlySoftOffersOrdersThemByTotalCostAndTheChosenOneReachesTheDesk(
      CapturedOutput output) throws Exception {
    try (var app = start("case-loan")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id + "/loan";

      var empty = call(client, "GET", base, null, token, null).body();
      assertThat(empty.path("holder").asText()).isEqualTo("CASE");
      assertThat(empty.path("application").isNull()).isTrue();
      assertThat(empty.path("results").isNull()).isTrue();
      assertThat(empty.at("/authorization/version").asText()).isEqualTo(LoanShoppingService.AUTHORIZATION_VERSION);
      assertThat(empty.at("/authorization/text/0").asText()).contains("Fair Credit Reporting Act", "soft inquiry");
      assertThat(empty.at("/authorization/text/2").asText()).contains("to no one else", "Social Security number", "never stores");

      // Field errors name the correction and never echo the input; nothing is stored.
      var invalid = call(client, "PUT", base, Map.of("amountMinor", -4, "termMonths", 240, "vehicle", Map.of("vin", "1HGCM82633A004353",
          "modelYear", 2023, "make", "Honda", "model", "Accord", "mileage", 12), "annualIncomeMinor", 8_800_000, "housing", "CASTLE",
          "monthlyHousingCostMinor", 185_000), token, null);
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().path("code").asText()).isEqualTo("LOAN_INVALID");
      assertThat(invalid.body().path("errors").findValuesAsText("field"))
          .containsExactly("amountMinor", "termMonths", "vehicle.vin", "housing");
      assertThat(invalid.body().toString()).doesNotContain("1HGCM82633A004353", "CASTLE", "8800000");
      assertThat(jdbc(app).queryForObject("select count(*) from loan_applications", Long.class)).isZero();

      // Identity details are not part of the application: anything sent alongside is never stored.
      var withIdentity = new LinkedHashMap<>(application(null, 3_000_000L, 60));
      withIdentity.put("socialSecurityNumber", "123-45-6789");
      withIdentity.put("dateOfBirth", "1984-03-02");
      var stored = put(client, base, withIdentity, token);
      assertThat(stored.path("revision").asInt()).isEqualTo(1);
      assertThat(stored.at("/application/vehicle/vin").asText()).isEqualTo(VIN);
      assertThat(stored.toString()).doesNotContain("123-45-6789", "1984-03-02");
      assertThat(jdbc(app).queryForObject("select application_json from loan_applications", String.class))
          .doesNotContain("123-45-6789", "1984-03-02", "socialSecurity", "dateOfBirth");
      // Sending the same details again changes nothing; an edit of an older revision is refused.
      assertThat(put(client, base, application(null, 3_000_000L, 60), token).path("revision").asInt()).isEqualTo(1);
      var conflict = call(client, "PUT", base, application(null, 3_100_000L, 60), token, null);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.body().path("code").asText()).isEqualTo("LOAN_APPLICATION_CHANGED");

      // Without the Buyer's written authorization as shown now, nothing is sent and nothing is metered (LOAN-01).
      for (var refused : List.<Map<String, Object>>of(Map.of(), Map.of("authorizationTextVersion", LoanShoppingService.AUTHORIZATION_VERSION),
          Map.of("authorizationTextVersion", "an-older-authorization", "authorized", true))) {
        var result = keyed(client, "POST", base + "/pre-approval-requests", refused, token, "refused-" + refused.size());
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().path("code").asText()).isEqualTo("LOAN_AUTHORIZATION_REQUIRED");
      }
      assertThat(sent).isEmpty();
      assertThat(jdbc(app).queryForObject("select count(*) from provider_usage where operation = 'PRE_APPROVAL'", Long.class)).isZero();

      // A response that is not limited to soft inquiries is refused whole: no offer is kept or shown (LOAN-01).
      partner = "HARD";
      var hard = keyed(client, "POST", base + "/pre-approval-requests", AUTHORIZATION, token, "hard-request");
      assertThat(hard.status()).isEqualTo(201);
      assertThat(hard.body().at("/results/request/availability").asText()).isEqualTo("UNAVAILABLE");
      assertThat(hard.body().at("/results/request/notice").asText()).contains("not limited to soft inquiries", "No offer was substituted");
      assertThat(hard.body().at("/results/sameTerms")).isEmpty();
      assertThat(jdbc(app).queryForObject("select count(*) from loan_offers", Long.class)).isZero();
      assertThat(jdbc(app).queryForObject("select reason from loan_pre_approval_requests", String.class)).isEqualTo("NON_SOFT_INQUIRY");

      partner = "MANY";
      var requested = keyed(client, "POST", base + "/pre-approval-requests", AUTHORIZATION, token, "first-request");
      assertThat(requested.status()).isEqualTo(201);
      var results = requested.body().path("results");
      // The adapter received the application with the authorization version, and nothing that names a person.
      assertThat(sent).hasSize(2);
      var application = (LoanApplication) sent.getLast()[0];
      assertThat(((CreditAuthorization) sent.getLast()[1]).authorizationTextVersion()).isEqualTo(LoanShoppingService.AUTHORIZATION_VERSION);
      assertThat(application.amountMinor()).isEqualTo(3_000_000);
      assertThat(application.termMonths()).isEqualTo(60);
      assertThat(application.housing()).isEqualTo(LoanApplication.Housing.RENT);
      assertThat(application.vehicle().make()).isEqualTo("Honda");
      // Nothing else carried it: no dealer message, no email, and the usage row holds no application detail.
      assertThat(jdbc(app).queryForObject("select count(*) from outbound_drafts", Long.class)).isZero();
      assertThat(ShortlistAcceptanceTest.links).isEmpty();
      var usage = jdbc(app).queryForList("select * from provider_usage where case_id = ? and operation = 'PRE_APPROVAL'", id);
      assertThat(usage).hasSize(2).allSatisfy(row -> assertThat(row.values())
          .noneMatch(value -> value != null && List.of(VIN, "8800000", "185000").stream().anyMatch(value.toString()::contains)));
      var request = jdbc(app).queryForMap("select * from loan_pre_approval_requests where availability = 'AVAILABLE'");
      assertThat(request.get("authorization_text_version")).isEqualTo(LoanShoppingService.AUTHORIZATION_VERSION);
      assertThat(request.get("authorized_at")).isNotNull();

      // Ordered by total cost for the same amount and term, whatever the partner's order; fees count (LOAN-02).
      var same = results.path("sameTerms");
      assertThat(same.findValuesAsText("lender")).containsExactly("Coastal Auto Finance", "Bayfront Credit", "Harbor Lending");
      var totals = new ArrayList<Long>();
      same.forEach(offer -> totals.add(offer.path("totalCostMinor").asLong()));
      assertThat(totals).isSorted();
      var coastal = same.get(0);
      assertThat(coastal.path("aprPercent").decimalValue()).isEqualByComparingTo("5.49");
      assertThat(coastal.path("termMonths").asInt()).isEqualTo(60);
      assertThat(coastal.path("amountMinor").asLong()).isEqualTo(3_000_000);
      assertThat(coastal.path("paymentMinor").asLong()).isBetween(57_000L, 58_000L);
      assertThat(coastal.path("totalCostMinor").asLong()).isEqualTo(3_000_000 + coastal.path("totalInterestMinor").asLong());
      assertThat(same.get(1).path("totalCostMinor").asLong()).isEqualTo(coastal.path("totalCostMinor").asLong() + 39_500);
      assertThat(coastal.at("/conditions/maximumMileage").asLong()).isEqualTo(120_000);
      assertThat(coastal.path("expiresAt").asText()).isNotBlank();
      assertThat(coastal.path("continueUrl").asText()).startsWith("https://");
      // The lender's own payment figure is shown beside FIN-01's as a reconciliation (FIN-02).
      var harbor = same.get(2);
      assertThat(harbor.path("lenderPaymentMinor").asLong()).isEqualTo(99_999);
      assertThat(harbor.path("lenderMinusCalculatedMinor").asLong()).isEqualTo(99_999 - harbor.path("paymentMinor").asLong());
      assertThat(harbor.path("lenderPaymentWithinTolerance").asBoolean()).isFalse();
      assertThat(results.path("withoutOffer").findValuesAsText("lender")).containsExactly("Keys Bank", "Pelican Lending");
      assertThat(results.path("withoutOffer").findValuesAsText("outcome")).containsExactly("DECLINED", "TIMED_OUT");
      assertThat(results.at("/request/notice").asText()).contains("Synthetic");
      assertThat(results.at("/request/outdated").asBoolean()).isFalse();

      // A retried request sends nothing more.
      assertThat(keyed(client, "POST", base + "/pre-approval-requests", AUTHORIZATION, token, "first-request").status()).isEqualTo(201);
      assertThat(sent).hasSize(2);

      // An offer from a non-participating lender is costed and ordered on the same terms (PAY-02).
      var own = keyed(client, "POST", base + "/own-offers", Map.of("lender", CREDIT_UNION, "aprPercent", 4.9), token, "own-same");
      assertThat(own.status()).isEqualTo(201);
      var withOwn = own.body().path("results");
      assertThat(withOwn.path("sameTerms").findValuesAsText("lender"))
          .containsExactly(CREDIT_UNION, "Coastal Auto Finance", "Bayfront Credit", "Harbor Lending");
      assertThat(withOwn.at("/sameTerms/0/source").asText()).isEqualTo("BUYER_ENTERED");
      assertThat(withOwn.at("/sameTerms/0/continueUrl").isNull()).isTrue();
      assertThat(keyed(client, "POST", base + "/own-offers", Map.of("lender", CREDIT_UNION, "aprPercent", 4.9), token, "own-same")
          .body().at("/results/sameTerms")).as("a retry adds nothing").hasSize(4);
      var longer = keyed(client, "POST", base + "/own-offers", Map.of("lender", "Gator Federal", "aprPercent", 3.9, "termMonths", 72),
          token, "own-longer").body().path("results");
      assertThat(longer.at("/differentTerms/0/lender").asText()).isEqualTo("Gator Federal");
      assertThat(longer.at("/differentTerms/0/differences/0").asText()).isEqualTo("TERM");
      assertThat(longer.path("sameTerms").findValuesAsText("lender")).doesNotContain("Gator Federal");
      LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
      var expired = keyed(client, "POST", base + "/own-offers", Map.of("lender", "Old Letter Bank", "aprPercent", 2.9,
          "offeredOn", today.minusDays(40).toString(), "expiresOn", today.minusDays(1).toString()), token, "own-expired").body().path("results");
      assertThat(expired.path("expired").findValuesAsText("lender")).containsExactly("Old Letter Bank");
      var refusedOwn = keyed(client, "POST", base + "/own-offers", Map.of("lender", " ", "aprPercent", 140, "termMonths", 0,
          "offeredOn", today.plusDays(3).toString()), token, "own-bad");
      assertThat(refusedOwn.status()).isEqualTo(400);
      assertThat(refusedOwn.body().path("errors").findValuesAsText("field")).containsExactly("lender", "aprPercent", "termMonths", "offeredOn");

      // "Use this pre-approval": only an unexpired offer can be chosen, and the choice is the Buyer's own financing.
      String expiredId = expired.at("/expired/0/id").asText();
      var notChoosable = call(client, "PUT", base + "/chosen-offer", Map.of("offerId", expiredId), token, null);
      assertThat(notChoosable.status()).isEqualTo(409);
      assertThat(notChoosable.body().path("code").asText()).isEqualTo("LOAN_OFFER_NOT_CHOOSABLE");
      assertThat(call(client, "PUT", base + "/chosen-offer", Map.of("offerId", UUID.randomUUID().toString()), token, null).status())
          .isEqualTo(404);
      String coastalId = coastal.path("id").asText();
      var chosen = put(client, base + "/chosen-offer", Map.of("offerId", coastalId), token);
      assertThat(chosen.at("/chosen/lender").asText()).isEqualTo("Coastal Auto Finance");
      assertThat(chosen.at("/chosen/source").asText()).isEqualTo("PARTNER");
      assertThat(chosen.at("/chosen/expired").asBoolean()).isFalse();
      assertThat(chosen.at("/results/sameTerms/1/chosen").asBoolean()).isTrue();

      // At the desk, the chosen pre-approval is the Buyer's own offer (DESK-04), as a manually entered one is.
      String desk = "/api/v1/cases/" + id + "/desk-start/result";
      var above = call(client, "POST", desk, Map.of("route", "THIS_CASE", "typed", Map.of("aprPercent", 7.5, "termMonths", 60,
          "amountFinancedMinor", 3_000_000)), token, null);
      assertThat(above.status()).as(above.toString()).isEqualTo(200);
      var aprFinding = find(above.body().path("tacticFindings"), "APR_ABOVE_OWN_OFFER");
      assertThat(aprFinding.path("summary").asText()).contains("7.5%", "5.49%");
      assertThat(aprFinding.path("totalEffectMinor").asLong()).isPositive();
      var ownEvidence = aprFinding.path("evidenceRegions").get(aprFinding.path("evidenceRegions").size() - 1).path("region");
      assertThat(ownEvidence.path("source").asText()).isEqualTo("chosen_loan_offer");
      assertThat(ownEvidence.path("lender").asText()).isEqualTo("Coastal Auto Finance");
      // When the dealer's financing costs less than the chosen pre-approval, the Desk Check says so plainly.
      var below = call(client, "POST", desk, Map.of("route", "THIS_CASE", "typed", Map.of("aprPercent", 3.9, "termMonths", 60,
          "amountFinancedMinor", 3_000_000)), token, null).body();
      assertThat(below.path("tacticFindings").findValuesAsText("code")).containsExactly(DeskTacticPolicy.DEALER_FINANCING_COSTS_LESS);
      var cheaper = below.path("tacticFindings").get(0);
      assertThat(cheaper.path("summary").asText()).startsWith("The dealer's financing costs less than your own offer")
          .contains("3.9%", "5.49%", "less in scheduled interest");
      assertThat(cheaper.path("totalEffectMinor").asLong()).isNegative();
      assertThat(below.path("findings").findValuesAsText("code")).contains(DeskTacticPolicy.DEALER_FINANCING_COSTS_LESS)
          .doesNotContain("APR_ABOVE_OWN_OFFER");

      // Choosing none takes it away from the desk; removing a chosen Buyer-entered offer removes the choice.
      assertThat(call(client, "DELETE", base + "/chosen-offer", null, token, null).body().path("chosen").isNull()).isTrue();
      assertThat(call(client, "POST", desk, Map.of("route", "THIS_CASE", "typed", Map.of("aprPercent", 3.9, "termMonths", 60,
          "amountFinancedMinor", 3_000_000)), token, null).body().path("tacticFindings")).isEmpty();
      String ownId = withOwn.at("/sameTerms/0/id").asText();
      put(client, base + "/chosen-offer", Map.of("offerId", ownId), token);
      assertThat(call(client, "DELETE", base + "/own-offers/" + coastalId, null, token, null).status()).as("a partner offer stays").isEqualTo(404);
      var removed = call(client, "DELETE", base + "/own-offers/" + ownId, null, token, null).body();
      assertThat(removed.path("chosen").isNull()).isTrue();
      assertThat(removed.at("/results/sameTerms").findValuesAsText("lender")).doesNotContain(CREDIT_UNION);
      put(client, base + "/chosen-offer", Map.of("offerId", coastalId), token);

      // A changed application marks the pre-approvals as requested for an earlier version.
      var changed = put(client, base, application(1, 3_000_000L, 72), token);
      assertThat(changed.at("/results/request/outdated").asBoolean()).isTrue();
      assertThat(changed.at("/results/differentTerms").findValuesAsText("lender")).contains("Coastal Auto Finance");

      // A malformed request is refused without quoting it.
      var unreadable = client.put().uri(base).header("Case-Access-Token", token).contentType(MediaType.APPLICATION_JSON)
          .body("{\"amountMinor\":\"thirty thousand " + VIN + "\"}")
          .exchange((ignored, response) -> new Result(response.getStatusCode().value(), JSON.readTree(response.getBody())));
      assertThat(unreadable.status()).isEqualTo(400);
      assertThat(unreadable.body().path("code").asText()).isEqualTo("LOAN_UNREADABLE");
      assertThat(unreadable.body().toString()).doesNotContain(VIN);
      // Another case's credential reads nothing.
      var other = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "other");
      assertThat(call(client, "GET", base, null, other.path("accessToken").asText(), null).status()).isEqualTo(404);

      // The case export holds the application, the authorization-backed requests, the offers and the choice;
      // case deletion removes them.
      var job = post(client, "/api/v1/cases/" + id + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] archive = client.get().uri("/api/v1/cases/" + id + "/exports/" + job.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      var data = JSON.readTree(zipEntries(archive).get("case-data.json"));
      assertThat(data.at("/loan_applications/0/application_json/vehicle/vin").asText()).isEqualTo(VIN);
      assertThat(data.at("/loan_applications/0/chosen_offer_id").asText()).isEqualTo(coastalId);
      assertThat(data.path("loan_pre_approval_requests").findValuesAsText("authorization_text_version"))
          .containsOnly(LoanShoppingService.AUTHORIZATION_VERSION).hasSize(2);
      assertThat(data.path("loan_offers").findValuesAsText("lender")).contains("Coastal Auto Finance", "Gator Federal", "Keys Bank");
      assertThat(data.toString()).doesNotContain("idempotency", "123-45-6789");
      post(client, "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      for (String table : List.of("loan_applications", "loan_pre_approval_requests", "loan_offers"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();

      // No application field reached the logs.
      assertThat(output.getAll()).doesNotContain(VIN, CREDIT_UNION, "Gator Federal", "123-45-6789", "thirty thousand");
    }
  }

  @Test void aJourneyHoldsOneApplicationAndItsChosenPreApprovalIsTheOwnFinancingOfItsCases(CapturedOutput output) throws Exception {
    partner = "FAKE";
    try (var app = start("journey-loan")) {
      var client = app.client();
      String session = signIn(client, "borrower@example.com");
      String journeyId = account(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, "journey")
          .body().path("id").asText();
      String base = "/api/v1/journeys/" + journeyId + "/loan";

      // On its own: the journey holds the application without any case.
      assertThat(account(client, "GET", base, null, session, null).body().path("holder").asText()).isEqualTo("JOURNEY");
      var stored = account(client, "PUT", base, application(null, 2_500_000L, 60), session, null);
      assertThat(stored.status()).isEqualTo(200);
      assertThat(stored.body().path("caseVehicle").isNull()).isTrue();
      var requested = account(client, "POST", base + "/pre-approval-requests", AUTHORIZATION, session, "journey-request");
      assertThat(requested.status()).isEqualTo(201);
      var offered = requested.body().at("/results/sameTerms/0");
      assertThat(offered.path("lender").asText()).isEqualTo("Synthetic lender");
      assertThat(jdbc(app).queryForObject("select count(*) from provider_usage where journey_id = ? and operation = 'PRE_APPROVAL'",
          Long.class, journeyId)).isEqualTo(1);
      // Writes need the account request marker.
      int unmarked = client.put().uri(base + "/chosen-offer").header("Cookie", AccountSessionTestSupport.cookie(session))
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("offerId", offered.path("id").asText()))
          .exchange((ignored, response) -> response.getStatusCode().value());
      assertThat(unmarked).isEqualTo(403);
      assertThat(account(client, "PUT", base + "/chosen-offer", Map.of("offerId", offered.path("id").asText()), session, null)
          .body().at("/chosen/lender").asText()).isEqualTo("Synthetic lender");

      // A case opened from the journey uses the journey's application and its chosen pre-approval at the desk.
      var candidates = account(client, "POST", "/api/v1/journeys/" + journeyId + "/candidates", null, session, null).body().path("candidates");
      var opened = account(client, "POST", "/api/v1/journeys/" + journeyId + "/candidates/"
          + candidates.get(0).path("candidateId").asText() + "/case", CasePortabilityAcceptanceTest.buyingCase(), session, "case").body();
      String caseId = opened.at("/case/id").asText(), token = opened.path("accessToken").asText();
      var fromCase = call(client, "GET", "/api/v1/cases/" + caseId + "/loan", null, token, null).body();
      assertThat(fromCase.path("holder").asText()).isEqualTo("JOURNEY");
      assertThat(fromCase.at("/chosen/offerId").asText()).isEqualTo(offered.path("id").asText());
      var deskResult = call(client, "POST", "/api/v1/cases/" + caseId + "/desk-start/result", Map.of("route", "THIS_CASE",
          "typed", Map.of("aprPercent", 8.9, "termMonths", 60, "amountFinancedMinor", 2_500_000)), token, null).body();
      assertThat(find(deskResult.path("tacticFindings"), "APR_ABOVE_OWN_OFFER").path("summary").asText()).contains("6.25%");
      keyed(client, "POST", "/api/v1/cases/" + caseId + "/loan/own-offers", Map.of("lender", CREDIT_UNION, "aprPercent", 5.25), token,
          "journey-own");
      assertThat(account(client, "GET", base, null, session, null).body().at("/results/sameTerms/0/lender").asText()).isEqualTo(CREDIT_UNION);

      // The account export holds the journey's application, request, offers and choice.
      byte[] archive = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session))
          .header("Account-Request", "1").retrieve().body(byte[].class);
      var exported = JSON.readTree(zipEntries(archive).get("account.json"));
      var loan = exported.at("/buyingJourneys/0/loanApplication");
      assertThat(loan.at("/application/amountMinor").asLong()).isEqualTo(2_500_000);
      assertThat(loan.path("chosenOfferId").asText()).isEqualTo(offered.path("id").asText());
      assertThat(loan.path("preApprovalRequests")).hasSize(1);
      assertThat(loan.at("/preApprovalRequests/0/authorizationTextVersion").asText()).isEqualTo(LoanShoppingService.AUTHORIZATION_VERSION);
      // Offers are written at different times here, but find them by lender rather than rely on row order.
      assertThat(loan.path("offers").findValuesAsText("lender")).containsExactlyInAnyOrder("Synthetic lender", CREDIT_UNION);
      var partnerOffers = new ArrayList<JsonNode>();
      loan.path("offers").forEach(offer -> { if ("PARTNER".equals(offer.path("source").asText())) partnerOffers.add(offer); });
      assertThat(partnerOffers).singleElement().satisfies(offer -> assertThat(offer.path("inquiryType").asText()).isEqualTo("SOFT"));
      assertThat(exported.path("journeyPreApprovalRequests")).hasSize(1);

      // Deleting the journey removes the application, its requests and offers.
      assertThat(account(client, "DELETE", "/api/v1/journeys/" + journeyId, null, session, null).status()).isEqualTo(200);
      for (String table : List.of("loan_applications", "loan_pre_approval_requests", "loan_offers"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
      assertThat(output.getAll()).doesNotContain(VIN, CREDIT_UNION);
    }
  }

  @Test void accountDeletionRemovesTheJourneyApplication() throws Exception {
    try (var app = start("account-loan")) {
      var client = app.client();
      String session = signIn(client, "leaving-borrower@example.com");
      String journeyId = account(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, "journey")
          .body().path("id").asText();
      String base = "/api/v1/journeys/" + journeyId + "/loan";
      account(client, "PUT", base, application(null, 3_000_000L, 60), session, null);
      var requested = account(client, "POST", base + "/pre-approval-requests", AUTHORIZATION, session, "request").body();
      account(client, "PUT", base + "/chosen-offer", Map.of("offerId", requested.at("/results/sameTerms/0/id").asText()), session, null);
      account(client, "POST", base + "/own-offers", Map.of("lender", CREDIT_UNION, "aprPercent", 4.9), session, "own");
      assertThat(account(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      for (String table : List.of("loan_applications", "loan_pre_approval_requests", "loan_offers"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
  }

  static Map<String, Object> application(Integer revision, long amountMinor, int termMonths) {
    var input = new LinkedHashMap<String, Object>();
    if (revision != null) input.put("basedOnRevision", revision);
    input.put("amountMinor", amountMinor);
    input.put("termMonths", termMonths);
    input.put("vehicle", Map.of("vin", VIN, "modelYear", 2023, "make", "Honda", "model", "Accord", "mileage", 12000));
    input.put("annualIncomeMinor", 8_800_000);
    input.put("housing", "RENT");
    input.put("monthlyHousingCostMinor", 185_000);
    return input;
  }

  private static JsonNode find(JsonNode findings, String code) {
    for (var finding : findings) if (code.equals(finding.path("code").asText())) return finding;
    throw new AssertionError("No " + code + " in " + findings);
  }

  private RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--case.deletion.delay=PT1H");
  }

  static Result keyed(RestClient client, String method, String path, Object body, String token, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Case-Access-Token", token).header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static JdbcTemplate jdbc(RunningApplication app) {
    return new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    String session = AccountSessionTestSupport.verify(client, ShortlistAcceptanceTest.links.getLast().split("#sign-in=")[1]);
    ShortlistAcceptanceTest.links.clear();
    return session;
  }

  private static Result account(RestClient client, String method, String path, Object body, String session, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Cookie", AccountSessionTestSupport.cookie(session));
    if (!"GET".equals(method)) request.header("Account-Request", "1");
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static Map<String, String> zipEntries(byte[] archive) throws Exception {
    var result = new HashMap<String, String>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
        if (entry.getName().endsWith(".json")) result.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    return result;
  }
}
