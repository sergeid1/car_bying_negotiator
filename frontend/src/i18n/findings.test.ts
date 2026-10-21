import { expect, test } from "vitest";
import { dealerReadingCopy, findingCode, findingText, localizeReport } from "./findings";

test("Spanish findings preserve quoted labels and typed amounts", () => {
  expect(findingText("es", { code: "AMOUNT_DOCUMENT", parameters: { label: "DOC FEE" }, text: "DOC FEE" })).toBe("DOC FEE");
  expect(findingText("es", { code: "TACTIC_PAYMENT", parameters: { difference: { minorUnits: 10000, currency: "USD" } }, text: "Stored text" }))
    .toContain("$100.00 mayor por mes");
  expect(findingText("es", { code: "FINANCE_STATUS", parameters: { status: "UNAVAILABLE" }, text: "Stored text" })).toContain("No disponible");
  expect(findingCode("es", "REVIEW_REQUIRED")).toBe("Requiere revisión");
});

test("legacy reports, unknown codes, missing parameters and unknown languages fall back", () => {
  const old = { analysis: { topReasons: ["Stored English finding"] }, textFindings: undefined };
  expect(localizeReport(old, "es")).toEqual(old);
  expect(findingText("es", { code: "FUTURE_FINDING", text: "Stored English finding" })).toBe("Stored English finding");
  expect(findingText("es", { code: "TACTIC_PAYMENT", text: "Stored English finding" })).toBe("Stored English finding");
  expect(findingCode("fr", "REVIEW_REQUIRED")).toBe("Review required");
});

test("the presentation copy follows the viewer without mutating the report", () => {
  const report = { analysis: { topReasons: ["Stored wording"] }, textFindings: {
    "/analysis/topReasons/0": { code: "REVIEW_UNVERIFIABLE", text: "Stored wording" },
  } };
  expect(localizeReport(report, "es").analysis.topReasons[0]).toMatch(/^La información faltante/);
  expect(report.analysis.topReasons).toEqual(["Stored wording"]);
  expect(localizeReport(report, "en").analysis.topReasons).toEqual(["Stored wording"]);
});

test("the Spanish reading copy leaves the approved English counteroffer intact", () => {
  const english = "Would you consider USD 23000.00 as the selling price in a revised written offer?\n\nCould you provide a current itemized written offer?";
  const revision = { text: english };
  const reading = dealerReadingCopy(revision.text);
  expect(reading).toContain("¿Consideraría USD 23000.00");
  expect(reading).toContain("oferta escrita, actual y desglosada");
  const approval = { approved: true, text: revision.text };
  expect(approval.text).toBe(english);
  expect(approval.text).not.toBe(reading);
  expect(dealerReadingCopy("An edited sentence outside the controlled templates.")).toBeUndefined();
});
