import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { CaseDownloads, RecoveryEmailOptIn, SaveCaseLink } from "./CasePortability";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { OfferVerification } from "./OfferVerification";
import { CoreDealReport, MarketEvidenceSection } from "./CoreDealReport";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
import { DealerReplyAssistant } from "./NegotiationDraft";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const AA = {
  runOnly: { type: "tag" as const, values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
  // Layout/contrast require the real-browser walkthrough; jsdom has no rendering engine.
  rules: { "color-contrast": { enabled: false } },
};

test("market freshness, uncertainty and unavailable evidence have no automated AA violations", async () => {
  const { container } = render(<main><h1>Saved report</h1><h2>Evidence</h2><MarketEvidenceSection evidence={{ status: "AVAILABLE", paragraphs: [
    "Market data retrieved: 2026-10-02T12:00:00Z.",
    "Observation window: 2026-09-20T12:00:00Z to 2026-09-30T12:00:00Z.",
    "Uncertainty: low confidence from 3 usable comparables. This is an estimated selling-price range.",
    "Too few compatible, current comparables (2) to support a market conclusion. No market rating is drawn.",
  ] }} /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("core entry, document intake, recovery, price approval, downloads and deletion have no automated AA violations", async () => {
  const { container } = render(<><App /><main aria-label="Saved case controls">
    <DealerDocumentUpload caseId="case" token="token" />
    <OfferVerification caseId="case" token="token" />
    <PricePlan caseId="case" token="token" />
    <VerifiedOfferAnalysis caseId="case" token="token" />
    <SaveCaseLink caseId="case" token="token" />
    <RecoveryEmailOptIn caseId="case" token="token" />
    <CaseDownloads caseId="case" token="token" reportVersion={1} />
    <CaseLifecycle caseId="case" token="token" onRevoked={() => {}} />
  </main></>);
  const result = await axe.run(container, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
    // Layout/contrast require the real-browser walkthrough; jsdom has no rendering engine.
    rules: { "color-contrast": { enabled: false } },
  });
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("keyboard users can skip to the case workflow", async () => {
  render(<App />);
  const user = userEvent.setup();
  await user.tab();
  expect(screen.getByRole("link", { name: "Skip to case workflow" })).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(screen.getByRole("region", { name: "Open an existing Buying Case" })).toHaveFocus();
});

test("term verification with source pages, trade terms, corrections and version changes has no automated AA violations", async () => {
  const evidence = (pageNumber: number) => ({ pageNumber, x: 0.1, y: 0.2, width: 0.5, height: 0.05 });
  const correction = { id: "k1", version: 1, normalizedValue: "2680000", normalizedUnit: "USD_MINOR", category: null, amountMinor: null, createdAt: "" };
  const extraction = {
    id: "e1", documentId: "d1", version: 1, extractorVersion: "x", modelVersion: "m",
    candidates: [
      { id: "c1", field: "SELLING_PRICE", originalWording: "Price $25,000", normalizedValue: "2500000", normalizedUnit: "USD_MINOR",
        evidence: evidence(1), confidence: 0.6, material: true, verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
      { id: "c2", field: "AMOUNT_FINANCED", originalWording: "Financed", normalizedValue: null, normalizedUnit: "USD_MINOR",
        evidence: evidence(2), confidence: 0.4, material: true, verificationState: "CORRECTED", currentCorrection: correction,
        correctionHistory: [{ ...correction, id: "k0", normalizedValue: "2600000" }, correction] },
      { id: "c3", field: "TRADE_ALLOWANCE", originalWording: "Trade $8,000", normalizedValue: "800000", normalizedUnit: "USD_MINOR",
        evidence: evidence(1), confidence: 0.97, material: true, verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] },
    ],
    lineItems: [{ id: "l1", originalLabel: "Mystery", category: "UNKNOWN", amountMinor: 89900, currency: "USD", evidence: evidence(2),
      confidence: 0.9, verificationState: "UNVERIFIED", currentCorrection: null, correctionHistory: [] }],
  };
  const offer = (version: number, price: string) => ({ id: `o${version}`, version, status: "VERIFIED", createdAt: "",
    terms: { candidates: [{ field: "SELLING_PRICE", value: price, unit: "USD_MINOR" }], lineItems: [] } });
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    const body = url.endsWith("/documents") ? [{ id: "d1", originalFilename: "order.pdf" }, { id: "d0", originalFilename: "quote.pdf" }]
      : url.endsWith("/extractions/latest") ? extraction
      : url.endsWith("/dealer-offers/latest") ? offer(2, "2450000") : offer(1, "2500000");
    return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
  });
  const { container } = render(<main aria-label="Verify terms"><OfferVerification caseId="case" token="token" /></main>);
  await screen.findByRole("table", { name: /terms that differ/i });
  await userEvent.setup().click(screen.getByRole("button", { name: "Correct Selling price" }));
  const result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});


test("report leading section, editable questions and evidence links have no automated AA violations", async () => {
  const { container } = render(<main><h1>Buying Case</h1><CoreDealReport report={{ version: 1, asOf: "2026-09-30", status: "PARTIAL", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask for the quote",
    topReasons: ["Check fees", "Check market", "Check total"], marketAvailability: "UNAVAILABLE", cashAvailability: "AVAILABLE",
    materialAmounts: [{ label: "Out-the-door total", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal" }], policyVersions: {}, limitations: [],
  }, evidence: { cashEconomics: { result: { outTheDoorTotal: { minorUnits: 2850000, currency: "USD" } } } }, reproduction: {} }} /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
  const user = userEvent.setup();
  await user.tab(); expect(screen.getByRole("link", { name: "$28,500.00" })).toHaveFocus();
});

test("field-to-document evidence, arithmetic and revised-offer differences have no automated AA violations", async () => {
  const region = { pageNumber: 1, x: 0.1, y: 0.6, width: 0.5, height: 0.04 };
  const { container } = render(<main><h1>Buying Case</h1><CoreDealReport report={{ version: 2, asOf: "2026-09-30", status: "COMPLETE", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask about the fee",
    topReasons: ["Check fees"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    dealReview: { version: 1, baselineVersion: 1, previousOfferVersion: 1, currentOfferVersion: 2, status: "REVIEW_REQUIRED", checkedAt: "2026-09-30",
      tolerancePolicy: "USD 1.00", scope: "Verified document terms", discrepancies: [{ comparison: "PREVIOUS", term: "line:doc fee#1", kind: "CHANGED",
        expected: { amountMinor: 49900, currency: "USD" }, observed: { amountMinor: 89900, currency: "USD" }, delta: 40000, unit: "USD_MINOR",
        severity: "MATERIAL", reason: "Changed", expectedEvidence: { originalLabel: "Doc fee", evidence: region },
        observedEvidence: { originalLabel: "Doc fee", evidence: region }, confidence: 0.9, uncertain: false, buyerAction: "Ask" }],
      cashDeltas: { previous: { availability: "ESTIMATED", currency: "USD", preTaxDeltaMinor: 40000, taxEffectMinor: 2500, outTheDoorDeltaMinor: 42500,
        expectedOutTheDoorMinor: 2600000, observedOutTheDoorMinor: 2642500, expectedTaxesMinor: 150000, observedTaxesMinor: 152500 } } },
    materialAmounts: [
      { label: "Doc fee", minorUnits: 89900, currency: "USD", precision: "EXACT", source: "cashEconomics", pointer: "/result/lineItems/0/amount",
        documentSource: { documentId: "document", extractionId: "extraction", offerVersion: 2, wording: "Doc fee", pageNumber: 1, region: { x: 0.1, y: 0.6, width: 0.5, height: 0.04 } } },
      { label: "Out-the-door total", minorUnits: 2642500, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal",
        sourceNote: "Calculated from the inputs below; no single document line holds this amount.",
        calculation: { operands: [{ operator: null, label: "Vehicle subtotal", minorUnits: 2400000, value: null },
          { operator: "+", label: "Taxes", minorUnits: 242500, value: null }], notes: ["Rounded half up."] } },
    ], policyVersions: {}, limitations: [],
  }, evidence: {}, reproduction: {} }} caseId="case" token="token" /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("optional total-identification form has no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ id: "session" })));
  const { container } = render(<main><h1>Pilot check</h1><ReportTotalMeasure caseId="case" token="token" report={{ version: 1 } as import("./CoreDealReport").CoreReport} /></main>);
  await userEvent.setup().click(screen.getByText("Optional pilot check: find the total"));
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("Dealer reply drafting with a returned reply has no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    policyVersion: "grounded-reply.v1", path: "TEMPLATE", fallbackReason: "MODEL_PATH_OFF", reportVersion: 1, dealerOfferId: "offer",
    dealerOfferVersion: 1, omittedStatements: 1, provenance: null, scope: "Recorded figures only.", draft: "Thank you for your response.",
    statements: [{ kind: "FACT", text: "The current written offer lists a selling price of $32,000.", sourcePointer: "/evidence/x", sourceValue: "3200000" }],
    proposal: { currency: "USD", sellingPriceMinor: 3000000, ceilingMinor: 3000000, sellingWalkAwayMinor: 3050000, outTheDoorWalkAwayMinor: null },
  })));
  const { container } = render(<main><h1>Buying Case</h1><h2>Report</h2><h3>Negotiation</h3><DealerReplyAssistant caseId="case" token="token" reportVersion={1} /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
  const user = userEvent.setup();
  await user.type(screen.getByRole("textbox", { name: "Dealer's response" }), "Best we can do.");
  await user.click(screen.getByRole("button", { name: "Draft a reply" }));
  await screen.findByRole("textbox", { name: "Your reply" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
