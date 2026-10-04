import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { NegotiationMandate } from "./NegotiationMandate";
import { DealerRelay } from "./DealerRelay";
import { CaseDownloads, RecoveryEmailOptIn, SaveCaseLink } from "./CasePortability";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { OfferVerification } from "./OfferVerification";
import { CoreDealReport, MarketEvidenceSection } from "./CoreDealReport";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
import { DealerReplyAssistant } from "./NegotiationDraft";
import { ReportExplanationLoader } from "./ReportExplanation";
import { ReferralDisclosure } from "./AcquisitionChannel";
import { OfferTerms, PurchasePoint } from "./OneTimeOffer";
import { SampleReportEntry } from "./SampleReport";
import { ExplainerGuide, GuideLinks } from "./ExplainerGuides";
import { ReferralPartnerKit } from "./ReferralPartnerKit";
import { ACCOUNT_SESSION_KEY, BuyerAccount } from "./BuyerAccount";
import { BuyingJourneys } from "./BuyingJourneys";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const AA = {
  runOnly: { type: "tag" as const, values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
  // Layout/contrast require the real-browser walkthrough; jsdom has no rendering engine.
  rules: { "color-contrast": { enabled: false } },
};

test("market freshness, uncertainty and unavailable evidence have no automated AA violations", async () => {
  const { container } = render(<main><h1>Saved report</h1><h2>Evidence</h2><MarketEvidenceSection evidence={{ status: "AVAILABLE", paragraphs: [
    "Market data retrieved: October 2, 2026.",
    "Observation window: September 20 to September 30, 2026.",
    "Uncertainty: low confidence from 3 usable comparables. This is an estimated selling-price range.",
    "Too few compatible, current comparables (2) to support a market conclusion. No market rating is drawn.",
  ] }} /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("core entry, document intake, recovery, price approval, downloads and deletion have no automated AA violations", async () => {
  const { container } = render(<><App /><main aria-label="Saved case controls">
    <ReferralDisclosure text="A consumer educator referred you here under a disclosed referral arrangement." />
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

test("the signed-in Buyer Account, its contact form and its deletion receipt have no automated AA violations", async () => {
  const values = new Map([[ACCOUNT_SESSION_KEY, "session"]]);
  Object.defineProperty(window, "localStorage", { configurable: true, value: {
    getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); } } });
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => new Response(JSON.stringify(
    String(input).endsWith("/deletion") && init?.method === "POST"
      ? { state: "DELETION_PENDING", failureCode: null, cases: [{ caseId: "case-1", state: "DELETION_PENDING" }] }
      : { contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null }, createdAt: "", sessionExpiresAt: "",
        cases: [{ caseId: "case-1", attachedAt: "", state: "ACTIVE" }] }), { headers: { "Content-Type": "application/json" } }));
  const { container } = render(<main><h1>Buying Case</h1><BuyerAccount caseCredentials={{ caseId: "case-2", token: "token" }} /></main>);
  await screen.findByRole("form", { name: "Contact details" });
  let result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
  const user = userEvent.setup();
  await user.click(screen.getByLabelText(/permanently delete my Buyer Account/));
  await user.click(screen.getByRole("button", { name: "Delete Buyer Account" }));
  await screen.findByRole("list", { name: "Attached cases being deleted" });
  result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("Buying Journeys: the list, the brief form with field errors, and a journey with versions have no automated AA violations", async () => {
  const brief = (version: number) => ({ journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2020,
    yearTo: 2024, make: { id: "toyota", label: "Toyota" }, model: { id: "toyota-rav4", label: "RAV4" },
    trims: [{ id: "toyota-rav4-xle", label: "XLE" }], maxMileage: 60000, priceCeilingMinor: 3_200_000, currency: "USD",
    postalCode: "27048", jurisdiction: "US-NC", radiusMiles: 50, mustHaveFeatures: [{ id: "all-wheel-drive", label: "All-wheel drive" }],
    niceToHaveFeatures: [], createdAt: "2026-10-03T12:00:00Z" });
  const journey = { id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", brief: brief(2),
    versions: [{ version: 1, createdAt: "2026-10-03T12:00:00Z" }, { version: 2, createdAt: "2026-10-03T13:00:00Z" }] };
  const options = { reference: { version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "toyota", label: "Toyota", models: [{ id: "toyota-rav4", label: "RAV4", trims: [{ id: "toyota-rav4-xle", label: "XLE" }] }] }],
    features: [{ id: "all-wheel-drive", label: "All-wheel drive" }, { id: "heated-seats", label: "Heated seats" }] },
    bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
      minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20 } };
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const [status, body] = url.endsWith("/brief-options") ? [200, options]
      : url === "/api/v1/journeys" && init?.method === "POST" ? [422, { code: "LOCATION_NOT_SERVED", detail: "Choose your state.", errors: [
        { field: "yearFrom", message: "Enter the earliest model year as a year from 1990 to 2027." },
        { field: "mustHaveFeatures", message: "Choose must-have features from the list." },
        { field: "jurisdiction", message: "That ZIP code crosses a state line. Choose your state.", reason: "POSTAL_CODE_CROSSES_STATE_LINE",
          candidateJurisdictions: ["US-NC", "US-VA"] }] }]
      : url === "/api/v1/journeys" ? [200, [{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "",
        currentVersion: 2, yearFrom: 2020, yearTo: 2024, make: "Toyota", model: "RAV4" }]]
      : url.endsWith("/briefs/1") ? [200, brief(1)] : [200, journey];
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  const { container } = render(<main><h1>Buyer Account</h1><BuyingJourneys session="session" onSessionEnded={() => {}} /></main>);
  const user = userEvent.setup();
  await screen.findByRole("list", { name: "Your Buying Journeys" });
  const violations = async () => (await axe.run(container, AA)).violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }));
  expect(await violations()).toEqual([]);

  await user.click(screen.getByRole("button", { name: "Start a Buying Journey" }));
  await user.type(screen.getByLabelText("Find a make or model (optional)"), "toy");
  await user.selectOptions(screen.getByLabelText("Make"), "toyota");
  await user.selectOptions(screen.getByLabelText("Model"), "toyota-rav4");
  await user.click(screen.getByRole("button", { name: "Save brief and start journey" }));
  await screen.findByLabelText("Your state");
  expect(await violations()).toEqual([]);

  await user.click(screen.getByRole("button", { name: "Cancel" }));
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Toyota RAV4" }));
  await user.selectOptions(await screen.findByLabelText("Brief version"), "1");
  await screen.findByText(/reading an earlier version/);
  expect(await violations()).toEqual([]);
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

test("report explanation with generated text, amount links and sources has no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    policyVersion: "report-explanation.v1", path: "MODEL", fallbackReason: null, reportVersion: 1, scope: "Financial consistency only.",
    provenance: { modelId: "claude-opus-5", promptVersion: "report-explanation-prompt.v1", schemaVersion: "report-explanation-schema.v1" },
    statements: [{ text: "The out-the-door total is $28,500.00 (estimated).", segments: [
      { text: "The out-the-door total is ", amountId: null, amountIndex: null, precision: null },
      { text: "$28,500.00 (estimated)", amountId: "amount-0", amountIndex: 0, precision: "ESTIMATED" },
      { text: ".", amountId: null, amountIndex: null, precision: null }],
      sources: [{ id: "cash-availability", sourcePointer: "/analysis/cashAvailability", sourceValue: "AVAILABLE" }] }],
  })));
  const { container } = render(<main><h1>Buying Case</h1><h2>Report</h2><ReportExplanationLoader caseId="case" token="token" reportVersion={1} /></main>);
  await screen.findByText("Generated explanation");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("offer terms, the example report and the payment point have no automated AA violations", async () => {
  const offer = { status: "AVAILABLE", offerId: "offer-1", kind: "ONE_TIME_PER_CASE", price: { amountMinor: 4900, currency: "USD" },
    paymentPoint: "BEFORE_REPORT", free: ["CASE_START", "DOCUMENT_UPLOAD", "CASE_DELETION"], paid: ["DEAL_ANALYSIS_REPORT", "REPORT_EXPORT"],
    refund: { windowDays: 14, terms: "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW" }, jurisdiction: null,
    excludedPurchaseTypes: ["LEASE", "AUCTION"], scopeLimits: ["NO_DEALER_CONTACT"], checkout: "DEVELOPMENT_NO_CHARGE" };
  const sample = { label: "EXAMPLE", source: "SYNTHETIC_CASE", redaction: { method: "FIELD_CATEGORIES", version: "sample-redaction.v2", removed: ["VIN", "DEALER_IDENTITY"] },
    report: { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
      reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask about the fee",
      topReasons: ["Check fees", null], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
      materialAmounts: [{ label: "Out-the-door total", minorUnits: 2850000, currency: "USD", precision: "EXACT", source: "cashEconomics",
        pointer: "/result/outTheDoorTotal", documentSource: null, sourceNote: null }], policyVersions: {}, limitations: [null],
    }, evidence: {}, reproduction: {} } };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    const body = url.endsWith("/api/v1/offer") ? offer : url.endsWith("/sample-report") ? sample : {};
    return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
  });
  const { container } = render(<main><h1>Deal X-Ray</h1><SampleReportEntry /><OfferTerms />
    <PurchasePoint caseId="case" token="token" onPurchased={() => {}} /></main>);
  await userEvent.setup().click(screen.getByRole("button", { name: "Open the example report" }));
  await screen.findByRole("note", { name: "About this example" });
  await screen.findByRole("region", { name: "What is free and what you pay for" });
  await userEvent.setup().click(await screen.findByText("Price terms, refunds, and scope"));
  const result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test.each(["out-the-door-quote", "dealer-fees"] as const)("the %s guide and the start-screen guide links have no automated AA violations", async slug => {
  const { container } = render(<main><GuideLinks /><ExplainerGuide slug={slug} /></main>);
  const result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("the referral partner kit, its example disclosure, and its built links have no automated AA violations", async () => {
  const { container } = render(<main><ReferralPartnerKit /></main>);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Build links" }));
  await screen.findByRole("alert");
  let result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
  await user.type(screen.getByLabelText("Channel code"), "inspector-1");
  await user.click(screen.getByRole("button", { name: "Build links" }));
  await screen.findByRole("list", { name: "Referral links for inspector-1" });
  result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("the Negotiation Mandate with its limits, disclosures, current version and history has no automated AA violations", async () => {
  const mandate = { version: 2, status: "ACTIVE", currency: "USD", targetMinor: 2250000, ceilingMinor: 2350000, planCeilingMinor: 2400000,
    disclosures: ["PAYING_CASH", "FIRST_NAME"], autonomyMode: "SUPERVISED", expiresAt: "2026-10-17T12:00:00Z",
    approvedAt: "2026-10-03T12:00:00Z", revokedAt: null };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).endsWith("/proposal")
    ? { available: true, unavailableReason: null, planVersion: 1, currency: "USD", targetMinor: 2300000, ceilingMinor: 2400000,
      disclosures: ["PAYING_CASH", "HAS_OUTSIDE_FINANCING", "FIRST_NAME", "PURCHASE_TIMELINE"] }
    : [mandate, { ...mandate, version: 1, status: "REVOKED", revokedAt: "2026-10-03T13:00:00Z" }])));
  const { container } = render(<main><h1>Buying Case</h1><NegotiationMandate caseId="case" token="token" /></main>);
  await screen.findByRole("button", { name: "Approve mandate" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("an unavailable Negotiation Mandate explains why without automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).endsWith("/proposal")
    ? { available: false, unavailableReason: "Review price boundaries first.", planVersion: null, currency: null,
      targetMinor: null, ceilingMinor: null, disclosures: [] } : [])));
  const { container } = render(<main><h1>Buying Case</h1><NegotiationMandate caseId="case" token="token" /></main>);
  await screen.findByText("Review price boundaries first.");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("the dealer email relay with its address, a dealer opt-out, messages and attachments has no automated AA violations", async () => {
  const user = userEvent.setup();
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).includes("/messages/")
    ? { id: "m2", direction: "INBOUND", trust: "UNTRUSTED", subject: "Your quote", body: "Quote attached." }
    : { address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", dealerOptedOut: true, dealerOptedOutAt: "2026-10-03T13:00:00Z",
      optOutChannel: "REPLY", perCaseDailyCap: 10, sentLastDay: 1, messages: [
        { id: "m2", direction: "INBOUND", trust: "UNTRUSTED", dealerAddress: "desk@dealer.example", state: "RECEIVED",
          createdAt: "2026-10-03T12:30:00Z", completedAt: "2026-10-03T12:30:00Z", contentAvailable: true,
          attachments: [{ ordinal: 0, documentId: "d1", outcome: "ACCEPTED" }, { ordinal: 1, documentId: null, outcome: "MALWARE_DETECTED" }] },
        { id: "m1", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: "desk@dealer.example", state: "DELIVERED",
          createdAt: "2026-10-03T12:00:00Z", completedAt: "2026-10-03T12:01:00Z", contentAvailable: false, attachments: [] }] })));
  const { container } = render(<main><h1>Buying Case</h1><DealerRelay caseId="case" token="token" /></main>);
  await user.click(await screen.findByRole("button", { name: /Show message from desk@dealer.example/ }));
  await screen.findByText("Quote attached.");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
