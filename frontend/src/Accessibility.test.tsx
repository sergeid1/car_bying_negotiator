import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { NegotiationMandate } from "./NegotiationMandate";
import { DealerRelay } from "./DealerRelay";
import { QuoteRequest } from "./QuoteRequest";
import { AgreedPrice } from "./AgreedPrice";
import { CaseDownloads, RecoveryEmailOptIn, SaveCaseLink } from "./CasePortability";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { OfferVerification } from "./OfferVerification";
import { CoreDealReport, MarketEvidenceSection, ReportSection } from "./CoreDealReport";
import { OutcomeFeedback } from "./PilotFeedback";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
import { DealerReplyAssistant } from "./NegotiationDraft";
import { ReportExplanationLoader } from "./ReportExplanation";
import { ReferralDisclosure } from "./AcquisitionChannel";
import { OfferTerms, PurchasePoint } from "./OneTimeOffer";
import { ExampleReportPage } from "./SampleReport";
import { ExplainerGuide, GuideLinks } from "./ExplainerGuides";
import { ReferralPartnerKit } from "./ReferralPartnerKit";
import { HowItWorks, HowItWorksCard } from "./HowItWorks";
import { HomeHero } from "./HomeHero";

import { AccountHeader, AccountSignedInContext, BuyerAccount } from "./BuyerAccount";
import { BuyingJourneys } from "./BuyingJourneys";
import { NegotiationConversation } from "./NegotiationConversation";
import { JourneyCandidates, ShortlistEntry } from "./JourneyCandidates";
import { QuoteComparison } from "./QuoteComparison";
import { Passkeys } from "./Passkeys";
import { Passwords } from "./Passwords";
import { SignIn } from "./SignIn";
import { DeskTactics } from "./DeskTactics";
import { syntheticTactic, syntheticTradeTactics } from "./DeskTactics.fixture";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test("the home hero has no automated AA violations", async () => {
  const { container } = render(<main><HomeHero /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("worksheet tactics and pending questions have no automated AA violations", async () => {
  const { container } = render(<main><h1>Desk Check</h1><DeskTactics findings={[syntheticTactic]} /></main>);
  await userEvent.click(screen.getByText("Calculation and evidence"));
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test.each([false, true])("password management and sign-in have no automated AA violations (%s)", async configured => {
  const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ configured })));
  const { container } = render(<main><h1>Account</h1><Passwords request={request} onSessionEnded={() => {}} />
    <SignIn request={request} onSignedIn={async () => {}} /></main>);
  await screen.findByRole("button", { name: configured ? "Change password" : "Set password" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test.each([{ keys: [] }, { keys: [{ id: "key", nickname: "My device", createdAt: "2026-10-09T12:00:00Z", lastUsedAt: null }] }])(
  "passkey management has no automated AA violations (%j)", async ({ keys }) => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(keys)));
    const { container } = render(<main><h1>Account</h1><Passkeys request={request} onSessionEnded={() => {}} /></main>);
    await screen.findByText(keys.length ? "My device" : "No passkeys added yet.");
    expect((await axe.run(container, AA)).violations).toEqual([]);
  });

test.each([undefined, "a.long.buyer.address@example.com"])("header account controls for %s have no automated AA violations", async email => {
  const { container } = render(<header><AccountHeader controls={{ email, busy: false, signOut: () => {} }} onOpen={() => {}} /></header>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("the Sign in side panel, with its private case link disclosure open, has no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input) === "/api/v1/account"
    ? new Response(JSON.stringify({ code: "SESSION_ENDED" }), { status: 401 })
    : new Response(JSON.stringify({}), { status: 404 }));
  const { container } = render(<App />);
  await userEvent.click(within(screen.getByRole("banner")).getByRole("button", { name: "Sign in" }));
  const panel = await screen.findByRole("dialog", { name: "Sign in" });
  await within(panel).findByRole("button", { name: "Continue" });
  await userEvent.click(within(panel).getByText("Have a private case link?"));
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("the This case side panel has no automated AA violations", async () => {
  window.history.replaceState(null, "", "/#case=case-1&access=token");
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input) === "/api/v1/account"
    ? new Response(JSON.stringify({ code: "SESSION_ENDED" }), { status: 401 })
    : String(input) === "/api/v1/cases/case-1" ? new Response(JSON.stringify({ id: "case-1" }))
      : new Response(JSON.stringify({}), { status: 404 }));
  try {
    const { container } = render(<App />);
    await userEvent.click(await within(screen.getByRole("main")).findByRole("button", { name: "This case" }));
    await screen.findByRole("dialog", { name: "This case" });
    expect((await axe.run(container, AA)).violations).toEqual([]);
  } finally { window.history.replaceState(null, "", "/"); }
});

// Ticket 63: acceptance, written confirmation and saved agreement states.
test.each(["ACCEPTANCE", "WRITTEN_CONFIRMATION", "AGREED", "BLOCKED", "JOURNEY_OPTIONS"])("Agreed Price %s has no automated AA violations", async state => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    requests: [{ id: "a1", kind: state === "WRITTEN_CONFIRMATION" ? state : "ACCEPTANCE", state: state === "WRITTEN_CONFIRMATION" ? "SENT" : "DRAFT",
      offerVersion: 3, planVersion: 2, gatedDraft: { draft: { text: "I accept the itemized quote, subject to a test drive and matching paperwork." },
        decision: { pass: state !== "BLOCKED", gateVersion: "outbound-gate.v2", reasons: state === "BLOCKED" ? [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] : [] } } }],
    agreedPrice: state === "AGREED" || state === "JOURNEY_OPTIONS" ? { offerVersion: 3, baselineVersion: 2, actor: "BUYER", acceptedAt: "2026-10-04T12:00:00Z",
      journey: { journeyLinked: state === "JOURNEY_OPTIONS", otherOpenNegotiations: state === "JOURNEY_OPTIONS" ? [{ caseId: "other", label: "Another dealer" }] : [] } } : null,
  })));
  const { container } = render(<main><h1>Buying Case</h1><AgreedPrice caseId="case" token="token"
    quotes={[{ id: "q1", state: "SENT", dealerAddress: "sales@dealer.example" }]} sources={[{ offerVersion: 3 }]} onChanged={async () => {}} /></main>);
  await screen.findByText(state === "WRITTEN_CONFIRMATION" ? "Request written confirmation" : "Accept verified offer 3");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

const AA = {
  runOnly: { type: "tag" as const, values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
  // Layout/contrast require the real-browser walkthrough; jsdom has no rendering engine.
  rules: { "color-contrast": { enabled: false } },
};

// Ticket 62: partial quotes and expanded source evidence.
test("quote comparison and its document evidence have no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ otdComparable: false, cars: [{
    caseId: "one", candidateId: "one", title: "2024 Honda Civic", state: "Verified quote", offerVersion: 2,
    missing: ["Taxes"], reportVersion: null, marketPosition: "Market position unavailable.",
    amounts: [{ label: "Selling price", minorUnits: 2000000, currency: "USD", evidence: { sourceNote: null,
      calculation: null, documentSource: { documentId: "doc", extractionId: "ex", offerVersion: 2, pageNumber: 1,
        wording: "Selling price", region: { x: .1, y: .2, width: .5, height: .1 } } } }],
  }] })));
  const { container } = render(<main><h1>Buying Journey</h1><QuoteComparison journeyId="j1" onSessionEnded={() => {}} /></main>);
  await screen.findByText("Selling price evidence");
  expect((await axe.run(container, AA)).violations).toEqual([]);
  await userEvent.setup().click(screen.getByText("Selling price evidence"));
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 62.

// Ticket 56: populated shortlist and candidate case confirmation.
test("shortlist ordering and case confirmation have no automated AA violations", async () => {
  const entry: ShortlistEntry = { candidateId: "c1", caseId: null, marketCheck: { status: "UNAVAILABLE", range: null,
    confidence: "UNAVAILABLE", provider: null, vintage: null, limitation: "No current comparables." },
    candidate: { listingId: "l1", vin: null, title: "2024 Honda Civic EX", year: 2024, make: "Honda", model: "Civic",
      trim: "EX", mileage: 10000, distanceMiles: 10, advertisedPriceMinor: 2000000, currency: "USD", matched: [], missed: [],
      unconfirmed: [], snapshot: { source: "Inventory", retrievedAt: "2026-10-04T12:00:00Z", observedAt: null,
        staleness: "UNKNOWN", synthetic: false } } };
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ supported: ["US-NC"], consentPolicies: {} })));
  const { container } = render(<main><h1>Buying Journey</h1><JourneyCandidates journeyId="j1" briefVersion={1}
    postalCode="27601" request={(async () => [entry]) as never} onSessionEnded={() => {}} /></main>);
  await screen.findByRole("button", { name: "Open a Buying Case" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
  await userEvent.setup().click(screen.getByRole("button", { name: "Open a Buying Case" }));
  await screen.findByRole("region", { name: "Confirm your Buying Case" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 56.

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

test("the signed-in Buyer Account, its case list, its contact form and its deletion receipt have no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => new Response(JSON.stringify(
    String(input).endsWith("/deletion") && init?.method === "POST"
      ? { state: "DELETION_PENDING", failureCode: null, cases: [{ caseId: "case-1", state: "DELETION_PENDING" }] }
      : { contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null }, createdAt: "", sessionExpiresAt: "",
        cases: [{ caseId: "case-1", attachedAt: "", state: "ACTIVE", startedAt: "2026-10-04T15:00:00Z", vehicle: "2021 Honda Accord EX-L",
          dealer: "Triangle Honda" }, { caseId: "case-3", attachedAt: "", state: "EXPIRED", vehicle: null, dealer: "Dealer ZIP 27604" }] }),
    { headers: { "Content-Type": "application/json" } }));
  const { container } = render(<main><h1>Buying Case</h1><BuyerAccount caseCredentials={{ caseId: "case-2", token: "token" }} /></main>);
  await screen.findByRole("form", { name: "Contact details" });
  // Ticket 88: the account's case list, with an openable and an unavailable case.
  await screen.findByRole("list", { name: "Your Buying Cases" });
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
  const { container } = render(<main><h1>Buyer Account</h1><BuyingJourneys onSessionEnded={() => {}} /></main>);
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

test("Reference Vehicles: decoded, undecodable and read-only vehicles and a checksum error have no automated AA violations", async () => {
  const brief = (version: number) => ({ journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2020,
    yearTo: 2024, make: { id: "toyota", label: "Toyota" }, model: { id: "toyota-rav4", label: "RAV4" }, trims: [], maxMileage: 60000,
    priceCeilingMinor: 3_200_000, currency: "USD", postalCode: "27601", jurisdiction: "US-NC", radiusMiles: 50,
    mustHaveFeatures: [], niceToHaveFeatures: [], createdAt: "2026-10-03T12:00:00Z" });
  const source = { source: "NHTSA vPIC", decoderVersion: "nhtsa-vpic-decodevinvalues-adapter-v1" };
  const decoded = { referenceId: "r1", vin: "2T3P1RFV5MW000001", outcome: "DECODED", ...source, decodedAt: "2026-10-04T12:00:00Z",
    attributes: [["MODEL_YEAR", "Model year", "2021"], ["MAKE", "Make", "Toyota"], ["MODEL", "Model", "RAV4"], ["TRIM", "Trim", "XLE"],
      ["DRIVETRAIN", "Drivetrain", "All-wheel drive"]].map(([attribute, label, value]) =>
      ({ attribute, label, value, relevant: attribute === "TRIM", ...source })) };
  const undecodable = { referenceId: "r2", vin: "3VW000003AA000006", outcome: "UNDECODABLE", ...source,
    decodedAt: "2026-10-04T12:00:00Z", attributes: [] };
  const journey = { id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", brief: brief(3),
    versions: [1, 2, 3].map(version => ({ version, createdAt: "2026-10-03T12:00:00Z" })), referenceVehicles: [decoded, undecodable] };
  const options = { reference: { version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "toyota", label: "Toyota", models: [{ id: "toyota-rav4", label: "RAV4", trims: [] }] }], features: [] },
    bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
      minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20, maxReferenceVehicles: 5 } };
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const [status, body] = url.endsWith("/brief-options") ? [200, options]
      : url.endsWith("/reference-vehicles") && init?.method === "POST" ? [400, { code: "VIN_INVALID",
        detail: "That VIN was not decoded because it is not a valid VIN.",
        errors: [{ field: "vin", message: "This VIN fails the VIN checksum. Enter all 17 characters exactly." }] }]
      : url === "/api/v1/journeys" ? [200, [{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "",
        currentVersion: 3, yearFrom: 2020, yearTo: 2024, make: "Toyota", model: "RAV4" }]]
      : url.endsWith("/briefs/2/reference-vehicles") ? [200, [decoded]]
      : url.endsWith("/briefs/2") ? [200, brief(2)] : [200, journey];
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  const { container } = render(<main><h1>Buyer Account</h1><BuyingJourneys onSessionEnded={() => {}} /></main>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Toyota RAV4" }));
  await screen.findByRole("region", { name: "Reference Vehicles" });
  const violations = async () => (await axe.run(container, AA)).violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }));
  expect(await violations()).toEqual([]);

  await user.type(screen.getByLabelText("VIN of a car you like"), "2T3P1RFV0MW000001");
  await user.click(screen.getByRole("button", { name: "Decode and add VIN" }));
  await screen.findByText(/fails the VIN checksum/);
  expect(await violations()).toEqual([]);

  await user.selectOptions(screen.getByLabelText("Brief version"), "2");
  await screen.findByLabelText("Decoded details of Reference Vehicle 1");
  expect(await violations()).toEqual([]);
});

// Ticket 55: Candidate Vehicles results list.
test("Candidate Vehicles: current, stale and unstated listings with left-out listings have no automated AA violations", async () => {
  const brief = { journeyId: "j1", version: 2, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2020, yearTo: 2024,
    make: { id: "honda", label: "Honda" }, model: { id: "honda-civic", label: "Civic" }, trims: [{ id: "honda-civic-ex", label: "EX" }],
    maxMileage: 60000, priceCeilingMinor: 3_200_000, currency: "USD", postalCode: "27601", jurisdiction: "US-NC", radiusMiles: 50,
    mustHaveFeatures: [], niceToHaveFeatures: [], createdAt: "2026-10-03T12:00:00Z" };
  const journey = { id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", brief, referenceVehicles: [],
    versions: [1, 2].map(version => ({ version, createdAt: "2026-10-03T12:00:00Z" })) };
  const options = { reference: { version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "honda", label: "Honda", models: [{ id: "honda-civic", label: "Civic", trims: [] }] }], features: [] },
    bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
      minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20, maxReferenceVehicles: 5 } };
  const snapshot = (staleness: string, observedAt: string | null) => ({ source: "SYNTHETIC_INVENTORY",
    retrievedAt: "2026-10-04T12:00:00Z", observedAt, staleness, synthetic: true });
  const civic = { title: "2024 Honda Civic EX", year: 2024, make: "Honda", model: "Civic", trim: "EX", distanceMiles: 10,
    currency: "USD", matched: [{ kind: "MUST_HAVE", text: "Trim EX is one of your trims." },
      { kind: "REFERENCE", text: "Matches your reference trim (EX)." }],
    missed: [{ kind: "NICE_TO_HAVE", text: "Does not list heated seats, a nice-to-have." }] };
  const results = { matcherVersion: "candidate-matching.v1", briefVersion: 2, referenceListVersion: "vehicle-reference-2026-10-v1",
    availability: "AVAILABLE", notice: "Synthetic inventory for development; these vehicles are not for sale.",
    provider: { name: "SYNTHETIC_INVENTORY", version: "deterministic-inventory.v1", licence: "SYNTHETIC", synthetic: true },
    retrievedAt: "2026-10-04T12:00:00Z", ordering: "The rest are ordered by your brief and what each listing states.",
    candidates: [
      { ...civic, listingId: "match", vin: "2HGFE2F55NH000011", mileage: 30000, advertisedPriceMinor: 3_200_000, unconfirmed: [],
        snapshot: snapshot("CURRENT", "2026-10-04T11:00:00Z") },
      { ...civic, listingId: "stale", vin: null, mileage: null, advertisedPriceMinor: null,
        unconfirmed: [{ kind: "MUST_HAVE", text: "The listing does not state its mileage." }],
        snapshot: snapshot("STALE", "2026-09-26T12:00:00Z") },
      { ...civic, listingId: "unknown", vin: null, mileage: 1000, advertisedPriceMinor: 3_000_050, unconfirmed: [],
        snapshot: snapshot("UNKNOWN", null) }],
    excluded: [{ listingId: "near-miss", vin: null, title: "2024 Honda Civic EX", snapshot: snapshot("CURRENT", null),
      missed: [{ kind: "MUST_HAVE", text: "61,000 miles is over your 60,000-mile maximum." }] }] };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    const body = url.endsWith("/brief-options") ? options : url.endsWith("/candidates") ? results
      : url === "/api/v1/journeys" ? [{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "",
        currentVersion: 2, yearFrom: 2020, yearTo: 2024, make: "Honda", model: "Civic" }] : journey;
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  const { container } = render(<main><h1>Buyer Account</h1><BuyingJourneys onSessionEnded={() => {}} /></main>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Honda Civic" }));
  await screen.findByRole("region", { name: "Candidate Vehicles" });
  const violations = async () => (await axe.run(container, AA)).violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }));
  expect(await violations()).toEqual([]);

  await user.click(screen.getByRole("button", { name: "Search inventory for candidates" }));
  await screen.findByRole("list", { name: "Candidate Vehicles, brief version 2" });
  await user.click(screen.getByText("1 listing was left out for missing a must-have"));
  expect(await violations()).toEqual([]);
});
// End ticket 55.

test("keyboard users can skip to the case workflow", async () => {
  render(<App />);
  const user = userEvent.setup();
  await user.tab();
  expect(screen.getByRole("link", { name: "Skip to case workflow" })).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(document.getElementById("case-workflow")).toHaveFocus();
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

test("the report's collapsible section list has no automated AA violations collapsed or expanded", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ experiment: null })));
  window.history.replaceState(null, "", "/");
  const { container } = render(<main><h1>Buying Case</h1><CoreDealReport report={{ version: 3, asOf: "2026-10-05", status: "COMPLETE", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Selling price comparison", nextAction: "Ask about the fee",
    topReasons: ["Check fees", "Check market", "Check total"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE",
    financingAvailability: "UNAVAILABLE", financing: null, marketEvidence: { status: "AVAILABLE", paragraphs: ["Market data retrieved: October 1, 2026."] },
    materialAmounts: [{ label: "Out-the-door total", minorUnits: 2850000, currency: "USD", precision: "ESTIMATED", source: "cashEconomics", pointer: "/result/outTheDoorTotal" }],
    policyVersions: { review: "core-review.v1" }, limitations: ["Cash purchase only"],
  }, evidence: {}, reproduction: {} }} caseId="case" token="token">
    <ReportSection id="core-feedback" title="Optional outcome feedback"><OutcomeFeedback caseId="case" token="token" headless /></ReportSection>
    <ReportSection id="core-downloads" title="Save a copy"><CaseDownloads caseId="case" token="token" reportVersion={3} headless /></ReportSection>
  </CoreDealReport></main>);
  const list = screen.getByRole("list", { name: "Report details" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
  const sections = Array.from(list.querySelectorAll<HTMLDetailsElement>(":scope > li > details"));
  expect(sections).toHaveLength(7);
  sections.forEach(section => { section.open = true; });
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
  const { container } = render(<main><ExampleReportPage /><OfferTerms />
    <PurchasePoint caseId="case" token="token" onPurchased={() => {}} /></main>);
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

test("the how-it-works walkthrough, its example screens and the start screen's card have no automated AA violations", async () => {
  const sample = { report: { analysis: { nextAction: "Ask the dealer to itemize the fees.", topReasons: ["Dealer fees are above the recorded range."],
    economics: { sellingPrice: { minorUnits: 2500000, currency: "USD" }, dealerFees: { minorUnits: 79900, currency: "USD" },
      addOns: { minorUnits: 50000, currency: "USD" }, outTheDoorTotal: { minorUnits: 2847500, currency: "USD" } } } } };
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(sample)));
  const { container } = render(<main><HowItWorksCard /><HowItWorks /></main>);
  await screen.findByText("Ask the dealer to itemize the fees.", { exact: false });
  const result = await axe.run(container, AA);
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("the Negotiation Mandate with its limits, disclosures, current version and history has no automated AA violations", async () => {
  const mandate = { version: 2, status: "ACTIVE", currency: "USD", targetMinor: 2250000, ceilingMinor: 2350000, planCeilingMinor: 2400000,
    disclosures: ["PAYING_CASH", "FIRST_NAME"], expiresAt: "2026-10-17T12:00:00Z",
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

test("dealer messages with the reply-intake address, a former relay message and attachments have no automated AA violations", async () => {
  const user = userEvent.setup();
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).includes("/messages/")
    ? { id: "m2", direction: "INBOUND", trust: "UNTRUSTED", subject: "Your quote", body: "Quote attached." }
    : { address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: false, messages: [
        { id: "m2", direction: "INBOUND", trust: "UNTRUSTED", dealerAddress: "desk@dealer.example", state: "RECEIVED",
          createdAt: "2026-10-03T12:30:00Z", completedAt: "2026-10-03T12:30:00Z", contentAvailable: true,
          attachments: [{ ordinal: 0, documentId: "d1", outcome: "ACCEPTED" }, { ordinal: 1, documentId: null, outcome: "MALWARE_DETECTED" }] },
        { id: "m1", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: "desk@dealer.example", state: "DELIVERED",
          createdAt: "2026-10-03T12:00:00Z", completedAt: "2026-10-03T12:01:00Z", contentAvailable: false, attachments: [], sendPath: "RELAY" }] })));
  const { container } = render(<main><h1>Buying Case</h1><DealerRelay caseId="case" token="token" /></main>);
  await user.click(await screen.findByRole("button", { name: /Show message from desk@dealer.example/ }));
  await screen.findByText("Quote attached.");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("the quote request with a gated draft, a sent request and dealer quote sources has no automated AA violations", async () => {
  const decision = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
  const draft = (id: string, state: string) => ({ id, dealerAddress: "sales@dealer.example", state, messageId: null, approvedAt: null,
    gatedDraft: { draft: { text: "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352?" }, decision } });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith("/relay")
    ? { address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: false, messages: [] }
    : { negotiationState: "AWAITING_BUYER", dealers: [{ id: "d1", name: "Triangle Motors" }],
      requests: [draft("q1", "SENT"), draft("q2", "DRAFT")],
      documents: [{ documentId: "doc1", outcome: "ACCEPTED", offerVersion: null, sourceKind: "MESSAGE_BODY", messageId: "m2" },
        { documentId: null, outcome: "MALWARE_DETECTED", offerVersion: null, sourceKind: "ATTACHMENT", messageId: "m2" }] }))));
  const { container } = render(<main><h1>Buying Case</h1><QuoteRequest caseId="case" token="token" /></main>);
  await screen.findByRole("button", { name: "Approve this text" });
  await screen.findByText(/cabcdefghijklmnopqrstuvwxyz234567@relay.invalid/);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

// Ticket 82: an approved request handed off to the Buyer's email app, and a sent one taking a pasted reply.
test("the quote request handed off to the Buyer's email app has no automated AA violations", async () => {
  const decision = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
  const text = "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352?";
  const draft = (id: string, state: string, messageId: string | null) => ({ id, dealerAddress: "sales@dealer.example", state, messageId,
    approvedAt: null, gatedDraft: { draft: { text }, decision } });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith("/relay")
    ? { address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: false, messages: [] }
    : url.includes("/handoffs/")
      ? { messageId: "m2", sendPath: "OWN_EMAIL_APP", state: "HANDED_OFF", dealerAddress: "sales@dealer.example",
        subject: "Request for an itemized vehicle quote", body: text, handedOffAt: "2026-10-05T12:00:00Z", sentAt: null }
      : { negotiationState: "AWAITING_DEALER", dealers: [{ id: "d1", name: "Triangle Motors" }],
        requests: [draft("q1", "SENT", "m1"), draft("q2", "HANDED_OFF", "m2")], documents: [] }))));
  const { container } = render(<main><h1>Buying Case</h1><QuoteRequest caseId="case" token="token" /></main>);
  await screen.findByRole("link", { name: "Open in my email app" });
  await screen.findByRole("button", { name: "Add reply" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

// Ticket 83: the connected mailbox on the account page, and Send from my email with an unclear outcome.
test("the Buyer Account's connected mailbox has no automated AA violations", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).endsWith("/mailbox")
    ? { available: true, providers: ["GOOGLE", "MICROSOFT"], connection: { provider: "MICROSOFT", providerLabel: "Outlook",
      address: "a.rather.long.address.for.narrow.screens@outlook.example", connectedAt: "2026-10-06T12:00:00Z" } }
    : String(input).endsWith("/notifications") ? []
    : { contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null }, createdAt: "", sessionExpiresAt: "", cases: [] }),
  { headers: { "Content-Type": "application/json" } }));
  const { container } = render(<main><h1>Buying Case</h1><BuyerAccount /></main>);
  await screen.findByRole("button", { name: "Disconnect Outlook" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

test("Send from my email and an unclear send have no automated AA violations", async () => {
  const decision = { pass: true, gateVersion: "outbound-gate.v3", reasons: [] };
  const text = "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352?";
  const draft = (id: string, messageId: string) => ({ id, dealerAddress: "sales@dealer.example", state: "HANDED_OFF", messageId,
    approvedAt: null, gatedDraft: { draft: { text }, decision } });
  const message = (id: string, state: string, sendPath: string) => ({ messageId: id, sendPath, state, dealerAddress: "sales@dealer.example",
    subject: "Request for an itemized vehicle quote", body: text, handedOffAt: "2026-10-05T12:00:00Z", sentAt: null });
  vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith("/relay")
    ? { address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: false, messages: [] }
    : url.endsWith("/account/mailbox")
      ? { available: true, providers: ["GOOGLE"], connection: { provider: "GOOGLE", providerLabel: "Gmail", address: "buyer@gmail.example",
        connectedAt: "2026-10-06T12:00:00Z" } }
      : url.endsWith("/handoffs/m1") ? message("m1", "HANDED_OFF", "OWN_EMAIL_APP")
      : url.endsWith("/handoffs/m2") ? message("m2", "DELIVERY_UNKNOWN", "CONNECTED_MAILBOX")
      : { negotiationState: "MANDATE_ACTIVE", dealers: [{ id: "d1", name: "Triangle Motors" }],
        requests: [draft("q1", "m1"), draft("q2", "m2")], documents: [] }))));
  const { container } = render(<main><h1>Buying Case</h1><AccountSignedInContext.Provider value={true}>
    <QuoteRequest caseId="case" token="token" /></AccountSignedInContext.Provider></main>);
  await screen.findByRole("button", { name: "Send from my email" });
  await screen.findByRole("button", { name: "Send again" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
  vi.unstubAllGlobals();
});

// Ticket 61: the negotiation conversation with drafts, gate decisions, messages and a paused state.
test.each(["AWAITING_BUYER", "PAUSED"])("the negotiation conversation (%s) has no automated AA violations", async state => {
  const pass = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
  const counter = "Would you consider USD 18500.00 as the selling price in a revised written offer?";
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
    negotiationState: state, targetReached: false,
    mandate: { version: 2, currency: "USD", targetMinor: 1850000, ceilingMinor: 1950000, expiresAt: "2026-10-18T12:00:00Z" },
    conversations: [{ quoteRequestId: "q1", dealerAddress: "sales@dealer.example" }],
    entries: [
      { kind: "INBOUND_MESSAGE", at: "2026-10-04T13:00:00Z", quoteRequest: null, acceptance: null, counteroffer: null,
        message: { id: "m2", direction: "INBOUND", dealerAddress: "sales@dealer.example", deliveryState: "RECEIVED", createdAt: "2026-10-04T13:00:00Z",
          completedAt: null, contentAvailable: true, subject: "Quote", body: "Selling price $25,000.",
          attachments: [{ ordinal: -1, documentId: "doc1", outcome: "ACCEPTED", offerVersion: 2 }] } },
      { kind: "COUNTEROFFER", at: "2026-10-04T14:00:00Z", quoteRequest: null, acceptance: null, message: null,
        counteroffer: { id: "n1", quoteRequestId: "q1", dealerAddress: "sales@dealer.example", state: state === "PAUSED" ? "CANCELLED" : "PROPOSED",
          cancelReason: state === "PAUSED" ? "PAUSED" : null, proposalMinor: 1850000, reportVersion: 3, offerVersion: 2, grounds: [], messageId: null,
          createdAt: "2026-10-04T14:00:00Z", revisions: [{ revision: 1, editedByBuyer: false, text: counter, decision: pass, createdAt: "2026-10-04T14:00:00Z" }] } },
    ] }))));
  const user = userEvent.setup();
  const { container } = render(<main><h1>Buying Case</h1><NegotiationConversation caseId="case" token="token" /></main>);
  await screen.findByRole("list", { name: "Conversation, oldest first" });
  if (state === "AWAITING_BUYER") await user.click(screen.getByRole("button", { name: "Edit this draft" }));
  expect((await axe.run(container, AA)).violations).toEqual([]);
  vi.unstubAllGlobals();
});

// Ticket 65: test-drive availability, dealer reply confirmation with contact release, and booked states.
import { TestDriveAppointment } from "./TestDriveAppointment";
function testDriveFixture(state: string) {
  const pass = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
  const slot = { option: 1, startsAt: "2026-10-10T15:00:00Z", endsAt: "2026-10-10T16:00:00Z", label: "Saturday, October 10, 2026, 10:00 to 11:00 (America/Chicago)" };
  const proposal = { id: "p1", kind: "PROPOSAL", appointmentId: null, slots: [slot], release: [], state: "SENT", createdAt: "2026-10-04T12:00:00Z",
    sentAt: "2026-10-04T12:01:00Z", gatedDraft: { draft: { text: "I'd like to arrange a test drive of the vehicle in your written quote at one of these times:\n\nOption 1: " + slot.label }, decision: pass } };
  return { agreedPrice: { id: "ap", offerVersion: 3, baselineVersion: 2, outTheDoorMinor: 2680000 }, dealerAddress: "sales@dealer.example",
    contactFields: ["FIRST_NAME", "LAST_NAME", "EMAIL", "PHONE"],
    messages: state === "PROPOSE" ? [] : [proposal, ...(state === "CONFIRM" ? [{ ...proposal, id: "b1", kind: "BOOKING", appointmentId: "a1",
      release: ["EMAIL"], state: "DRAFT", sentAt: null, gatedDraft: { draft: { text: "Thank you for confirming. I'll see you for the test drive on " + slot.label + "." },
        decision: { pass: false, gateVersion: "outbound-gate.v2", reasons: [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] } } }] : [])],
    appointments: state === "BOOKED" ? [{ id: "a1", option: 1, startsAt: slot.startsAt, endsAt: slot.endsAt, label: slot.label, state: "SCHEDULED",
      replacesAppointmentId: null, release: { fields: [], state: "WITHHELD", decidedAt: "2026-10-04T13:00:00Z", releasedAt: null } }] : [],
    confirmations: state === "CONFIRM" ? [{ proposalId: "p1", messageId: "m9", receivedAt: "2026-10-04T13:00:00Z",
      excerpt: "Option 1 works for us. Ask for the sales desk when you arrive.", detectedOption: 1 }] : [] };
}
test.each(["PROPOSE", "CONFIRM", "BOOKED"])("the test-drive panel (%s) has no automated AA violations", async state => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(testDriveFixture(state)))));
  const { container } = render(<main><h1>Buying Case</h1><TestDriveAppointment caseId="case" token="token" /></main>);
  await screen.findByRole("heading", { name: "Schedule a test drive" });
  await screen.findByRole("button", { name: "Refresh test-drive status" });
  if (state === "CONFIRM") await screen.findByRole("checkbox", { name: "Email" });
  if (state === "BOOKED") await screen.findByRole("button", { name: "Move this test drive" });
  if (state === "PROPOSE") await screen.findByRole("button", { name: "Prepare proposed times" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
  vi.unstubAllGlobals();
});
// End ticket 65.

// Ticket 66: Desk Check against the Agreed Price, the different-VIN block, the fresh-case start and its label.
import { DeskCheck, DeskFreshCase } from "./DeskCheck";
function deskCheckFixture(state: string) {
  const agreedPrice = { offerVersion: 2, baselineVersion: 1, vin: "1HGCM82633A004352", sellingPriceMinor: 2500000, outTheDoorMinor: 2680000, acceptedAt: "2026-10-04T12:00:00Z" };
  const none = { basis: "UNAVAILABLE", currency: "USD", outTheDoorDeltaMinor: null, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: null };
  const base = { status: "AWAITING_PAPERWORK", source: null, agreedPrice, paperwork: null, findings: [], otherDifferences: [], unchangedTerms: 0,
    totalEffect: none, freshCase: null, dealReviewVersion: null };
  if (state === "AWAITING") return base;
  if (state === "OTHER_CAR") return { ...base, status: "DIFFERENT_VIN", source: "READ_FROM_PAPERWORK",
    findings: [{ group: "VEHICLE", term: "candidate:VEHICLE_VIN#1", label: "VEHICLE_VIN", kind: "CHANGED", agreed: { value: agreedPrice.vin, unit: "VIN" },
      desk: { value: "1M8GDM9AXKP042788", unit: "VIN" }, delta: null, unit: "VIN", severity: "BLOCKING", verified: false, evidence: null, buyerAction: null }],
    freshCase: { vin: "1M8GDM9AXKP042788", dealerPostalCode: "27604", purchaseMethod: "CASH" } };
  const verified = state === "REVIEWED";
  return { ...base, status: "DIFFERENCES", source: verified ? "DEAL_REVIEW" : "READ_FROM_PAPERWORK", dealReviewVersion: verified ? 1 : null,
    findings: [{ group: "ADDED", term: "line:service contract#1", label: "Service contract", kind: "ADDED", agreed: null,
      desk: { amountMinor: 149500, currency: "USD" }, delta: null, unit: "USD_MINOR", severity: "MATERIAL", verified, evidence: { pageNumber: 2 },
      buyerAction: "Review this difference with the dealer before accepting." }],
    otherDifferences: [{ group: "OTHER", term: "candidate:OFFER_DATE#1", label: "OFFER_DATE", kind: "CHANGED", agreed: { value: "2026-08-30", unit: "DATE" },
      desk: { value: "2026-10-04", unit: "DATE" }, delta: null, unit: "DATE", severity: "MATERIAL", verified, evidence: null, buyerAction: null }],
    totalEffect: { basis: verified ? "CALCULATED" : "QUOTED", currency: "USD", outTheDoorDeltaMinor: 149500, preTaxDeltaMinor: verified ? 149500 : null,
      taxEffectMinor: verified ? 0 : null, addedChargesMinor: 149500 } };
}
test.each(["AWAITING", "READ", "REVIEWED", "OTHER_CAR"])("the Desk Check (%s) has no automated AA violations", async state => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    if (url.endsWith("/desk-check")) return new Response(JSON.stringify(deskCheckFixture(state)));
    if (url.endsWith("/intake-options")) return new Response(JSON.stringify({ supported: ["US-NC"], consentPolicies: {} }));
    return new Response(null, { status: 404 });
  });
  const { container } = render(<main><h1>Buying Case</h1><DeskCheck caseId="case" token="token" /></main>);
  await screen.findByRole("heading", { name: "Desk Check" });
  await screen.findByRole("heading", { name: "Photograph the desk paperwork" });
  if (state === "OTHER_CAR") {
    await userEvent.click(await screen.findByRole("button", { name: "Start a fresh case for this car" }));
    await screen.findByRole("heading", { name: "Start a fresh case for VIN 1M8GDM9AXKP042788" });
  } else if (state !== "AWAITING") await screen.findByRole("list", { name: "Differences from your Agreed Price" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
test("the fresh desk case label has no automated AA violations", async () => {
  const { container } = render(<main><h1>Buying Case</h1><DeskFreshCase value={{ caseId: "fresh", token: "t", agreed: { caseId: "case", token: "token" },
    agreedVin: "1HGCM82633A004352", vin: "1M8GDM9AXKP042788", files: [] }} onBack={() => undefined} /></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 66.

// Ticket 103: the trade-in check in the Desk Check and the report: checked, a different trade VIN, unrecorded.
import { TradeCheckPanel, type TradeCheckResult } from "./TradeCheck";
function tradeCheckA11y(state: string): TradeCheckResult {
  const finding = (code: string, severity: "BLOCKING" | "WARNING" | "INFORMATIONAL", summary: string, estimate = false) =>
    ({ code, severity, summary, amountMinor: null, deskQuestion: severity === "INFORMATIONAL" ? null : "Will you match it?", estimate });
  return { status: state === "UNRECORDED" ? "TRADE_NOT_RECORDED" : "CHECKED", allowanceMinor: 1150000, paperworkPayoffMinor: 1920000,
    buyerPayoff: state === "UNRECORDED" ? null : { state: state === "STALE" ? "STALE" : "CURRENT", amountMinor: 1800000, goodThrough: "2026-10-10" },
    payoffDifferenceMinor: 120000, equityMinor: state === "OTHER_TRADE" ? null : -770000, equityBasis: "PAPERWORK_PAYOFF",
    negativeEquityFinancedMinor: state === "CHECKED" ? 770000 : null,
    negativeEquityCost: { status: "AVAILABLE", reason: null, aprPercent: 6.9, termMonths: 72, paymentIncreaseMinor: 13093,
      scheduledPaymentsIncreaseMinor: 942696, interestMinor: 172696 },
    bestOffer: { id: "o1", amountMinor: 1400000, source: "Synthetic Car Exchange", offerDate: "2026-10-09", expiresOn: "2026-10-17", label: "the written offer" },
    shortfallToOfferMinor: 250000,
    taxCredit: { status: "UNAVAILABLE", reason: "No tax rules are configured for US-FL yet.", jurisdiction: "US-FL", policyVersion: null, creditMinor: null },
    offerGapAfterTaxMinor: null,
    valuation: state === "BUYER_ESTIMATE" ? { availability: "AVAILABLE", notice: null, source: "Carfax", synthetic: false, retrievedAt: null,
      conditionAssumption: null, lowMinor: 1400000, typicalMinor: null, highMinor: 1600000, position: "BELOW", basis: "BUYER_ESTIMATE",
      checkedOn: "2026-09-01", old: true, label: "your Carfax estimate from 2026-09-01" }
      : { availability: "AVAILABLE", notice: "Synthetic trade-in range for development only.", source: "SYNTHETIC_TRADE_VALUATION", synthetic: true,
      retrievedAt: "2026-10-10T15:00:00Z", conditionAssumption: "GOOD", lowMinor: 1400000, typicalMinor: 1600000, highMinor: 1800000, position: "BELOW" },
    vinMatch: { status: state === "OTHER_TRADE" ? "DIFFERENT" : "SAME", paperworkVin: state === "OTHER_TRADE" ? "2T3P1RFV5MW000001" : "5J6RW2H52ML000002",
      recordedVin: "5J6RW2H52ML000002" },
    findings: state === "OTHER_TRADE" ? [finding("TRADE_VIN_DIFFERENT", "BLOCKING", "The trade-in VIN on this paperwork is not your recorded trade-in.")]
      : state === "UNRECORDED" ? [finding("TRADE_NOT_RECORDED", "WARNING", "No trade-in is recorded on this case.")]
      : [finding("BELOW_WRITTEN_OFFER", "WARNING", "The trade-in allowance is $2,500 below the written offer."),
        finding("BELOW_VALUATION_RANGE", "WARNING", "The allowance is below the trade-in range (an estimate).", true),
        finding("TRADE_VIN_SAME", "INFORMATIONAL", "The trade-in VIN is the same as your recorded trade-in.")] };
}
test.each(["CHECKED", "STALE", "OTHER_TRADE", "UNRECORDED", "BUYER_ESTIMATE"])("the Desk Check with a trade check (%s) has no automated AA violations", async state => {
  const view = { ...deskCheckFixture("READ"), trade: tradeCheckA11y(state), findings: [{ group: "TRADE", term: `trade:${tradeCheckA11y(state).findings[0].code}`,
    label: tradeCheckA11y(state).findings[0].code, kind: "TRADE_CHECK", agreed: null, desk: null, delta: null, unit: "USD_MINOR",
    severity: tradeCheckA11y(state).findings[0].severity, verified: false, evidence: null, buyerAction: "Will you match it?" }] };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input =>
    String(input).endsWith("/desk-check") ? new Response(JSON.stringify(view)) : new Response(null, { status: 404 }));
  const { container } = render(<main><h1>Buying Case</h1><DeskCheck caseId="case" token="token" /></main>);
  await screen.findByRole("list", { name: "Trade-in findings" });
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
test("the report's trade check has no automated AA violations", async () => {
  const { container } = render(<main><h1>Buying Case</h1><section className="panel"><h2>Deal Analysis Report</h2>
    <TradeCheckPanel result={tradeCheckA11y("CHECKED")} /></section></main>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 103.

test("trade tactic labels with expanded evidence have no automated AA violations", async () => {
  const result: TradeCheckResult = { ...tradeCheckA11y("CHECKED"), findings: syntheticTradeTactics.map(tactic => ({
    code: tactic.tradeFindingCode!, summary: tactic.summary, amountMinor: tactic.totalEffectMinor,
    deskQuestion: "Original trade question", severity: "WARNING", estimate: false,
  })) };
  const { container } = render(<main><h1>Buying Case</h1><h2>Desk Check</h2><TradeCheckPanel result={result} tactics={syntheticTradeTactics} /></main>);
  for (const summary of screen.getAllByText("Tactic evidence and policy")) await userEvent.click(summary);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

// Ticket 100: the trade-in panel recorded, being recorded with field errors, and with written offers.
import { TradeVehicle } from "./TradeVehicle";
function tradeA11yView(state: string) {
  const conditions = [{ value: "GOOD", label: "Good", description: "Light wear such as small scratches or chips." },
    { value: "FAIR", label: "Fair", description: "Clear wear or cosmetic damage, or it needs a repair soon." }];
  const trade = { id: "trade-1", revision: 2, vehicle: { vin: "5J6RW2H52ML000002", decodeOutcome: "DECODED", source: "Decoder",
    decoderVersion: "v1", decodedAt: "2026-10-01T12:00:00Z", year: "2021", make: "Honda", model: "CR-V", trim: "EX" },
    mileage: 61000, condition: "GOOD", moneyOwed: "YES", lender: "First Coast Credit Union", payoffMinor: 1825000,
    payoffGoodThrough: "2026-10-01", payoffUnknown: false, payoffStale: true, complete: true, changedAt: "2026-10-05T12:00:00Z",
    offers: [{ id: "old", amountMinor: 1500000, source: "Coastal Auto Buyers", offerDate: "2026-09-10", expiresOn: "2026-09-17", expired: true,
      usedForComparison: false, evidence: { documentId: "d1", state: "READY", pageCount: 1 }, createdAt: "2026-09-10T12:00:00Z" },
    { id: "new", amountMinor: 1400000, source: "Sunshine Car Exchange", offerDate: "2026-10-04", expiresOn: "2026-10-11", expired: false,
      usedForComparison: true, evidence: { documentId: "d2", state: "PROCESSING", pageCount: 1 }, createdAt: "2026-10-04T12:00:00Z" }],
    bestOfferId: "new",
    estimates: [{ id: "kbb", source: "Kelley Blue Book", lowMinor: 1300000, highMinor: null, checkedOn: "2026-08-01", old: true, createdAt: "2026-08-01T12:00:00Z" },
      { id: "carfax", source: "Carfax", lowMinor: 1400000, highMinor: 1600000, checkedOn: "2026-10-05", old: false, createdAt: "2026-10-05T12:00:00Z" }],
    estimateInUseId: "carfax" };
  return { holder: "JOURNEY", trade: state === "EMPTY" ? null : trade, conditions,
    analysis: { analyzedAt: "2026-10-03T12:00:00Z", outdated: state !== "EMPTY" } };
}
test.each(["RECORDED", "EDITING", "EMPTY", "ESTIMATING"])("the trade-in panel (%s) has no automated AA violations", async state => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => String(input).endsWith("/estimates")
    ? new Response(JSON.stringify({ detail: "Check the highlighted trade-in details.", errors: [
      { field: "source", message: "Enter where you looked up the value, such as Carfax, up to 120 characters." }] }), { status: 400 })
    : init?.method === "PUT"
    ? new Response(JSON.stringify({ detail: "Check the highlighted trade-in details.", errors: [
      { field: "mileage", message: "Enter the mileage on the odometer, from 0 to 999,999 miles." },
      { field: "lender", message: "Enter the lender you make payments to, up to 120 characters." }] }), { status: 400 })
    : new Response(JSON.stringify(tradeA11yView(state))));
  const { container } = render(<main><h1>Buying Case</h1><TradeVehicle caseId="case" token="token" /></main>);
  await screen.findByRole("heading", { name: "Your trade-in" });
  if (state === "EDITING") {
    await userEvent.click(await screen.findByRole("button", { name: "Edit trade-in" }));
    await userEvent.clear(screen.getByLabelText("Lender"));
    await userEvent.click(screen.getByRole("button", { name: "Save trade-in" }));
    await screen.findByText("Enter the lender you make payments to, up to 120 characters.");
  }
  if (state === "RECORDED") {
    await userEvent.click(screen.getByRole("button", { name: "Add a written offer" }));
    await screen.findByLabelText("Photo or PDF of the offer");
    await screen.findByRole("list", { name: "Your trade-in estimates" });
  }
  if (state === "ESTIMATING") {
    await userEvent.click(await screen.findByRole("button", { name: "Add an estimate I looked up" }));
    await userEvent.click(screen.getByRole("radio", { name: "Another site" }));
    await userEvent.type(screen.getByLabelText("Site name"), "Carvana");
    await screen.findByText(/is a written offer, not an estimate/);
    await userEvent.type(screen.getByLabelText("Value, or the low end of its range ($)"), "14000");
    await userEvent.click(screen.getByRole("button", { name: "Add estimate" }));
    await screen.findByText(/Enter where you looked up the value/);
  }
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 100.

// Ticket 105: "I'm at the dealer" from the header — starting a case, routing, typing the numbers, questions and the first result.
import { AtTheDealer, DealerCameraEntry } from "./AtTheDealer";
function dealerFetch(route: string, status = "READ") {
  const vin = route === "FRESH_CASE" ? "1M8GDM9AXKP042788" : "1HGCM82633A004352";
  const routing = { status, documentId: "d1", documentType: null, vin: status === "UNREADABLE" ? null : vin, route,
    agreedVin: route === "THIS_CASE" ? null : "1HGCM82633A004352", caseVin: null,
    freshCase: route === "FRESH_CASE" ? { vin, dealerPostalCode: "27604", purchaseMethod: "CASH" } : null,
    questions: { trade: { ask: "DETAILS", paperworkShowsTrade: true, recorded: null },
      financing: { ask: true, paperworkShowsLoan: true, ownAprPercent: null, ownTermMonths: null } } };
  const lead = { code: "ADDED_CHARGE", summary: "Added: Service contract, $1,495. It is not in your Agreed Price.", amountMinor: 149500,
    deskQuestion: "The Service contract for $1,495 was not in the price we agreed. Will you take it off?" };
  const result = { route: "AGREED_CAR", source: "READ_FROM_PAPERWORK", outTheDoor: { amountMinor: 2829500, agreedMinor: 2680000, differenceMinor: 149500 },
    lead, findings: [lead, { code: "TOTAL_NOT_SHOWN", summary: "Second finding.", amountMinor: null, deskQuestion: "Second question?" }] };
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input), method = init?.method ?? "GET";
    if (url.endsWith("/document-limits")) return new Response(JSON.stringify({ acceptedMediaTypes: ["image/jpeg"], maxFileBytes: 20971520,
      maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 }));
    if (url.endsWith("/documents") && method === "POST") return new Response(JSON.stringify({ document: { id: "d1", pages: [] },
      operation: { id: "op1", stage: "QUEUED" } }), { status: 202 });
    if (url.includes("/operations/")) return new Response(JSON.stringify({ id: "op1", documentId: "d1", stage: "EXTRACTION_COMPLETE", retryCount: 0, updatedAt: "now" }));
    if (url.includes("/desk-start/result")) return new Response(JSON.stringify(result));
    if (url.includes("/desk-start")) return new Response(JSON.stringify(routing));
    return new Response(null, { status: 404 });
  });
}
const dealerPhoto = () => new File(["desk"], "desk.jpg", { type: "image/jpeg" });
test("the header's I'm at the dealer control has no automated AA violations", async () => {
  const { container } = render(<header><DealerCameraEntry onPhotos={() => undefined} /></header>);
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
test.each(["START", "AGREED_CAR", "FRESH_CASE", "TYPED", "QUESTIONS", "RESULT"])("I'm at the dealer (%s) has no automated AA violations", async state => {
  dealerFetch(state === "FRESH_CASE" ? "FRESH_CASE" : "AGREED_CAR", state === "TYPED" ? "UNREADABLE" : "READ");
  const { container } = render(<main><h1>Buying Case</h1><AtTheDealer files={[dealerPhoto()]} pollDelayMs={1}
    open={state === "START" ? undefined : { caseId: "case", token: "token" }}
    onCaseOpened={() => undefined} onFreshCase={() => undefined} onDeskCheck={() => undefined} onClose={() => undefined} /></main>);
  if (state === "START") await screen.findByRole("button", { name: "Read my paperwork" });
  if (state === "AGREED_CAR" || state === "QUESTIONS" || state === "RESULT")
    await userEvent.click(await screen.findByRole("button", { name: "Check it against my Agreed Price" }));
  if (state === "QUESTIONS" || state === "RESULT") {
    await userEvent.click(within(await screen.findByRole("group", { name: "Do you still owe money on it?" })).getByLabelText("Yes"));
    await userEvent.click(within(screen.getByRole("group", { name: /own loan offer/ })).getByLabelText("Yes"));
  }
  if (state === "QUESTIONS") {
    // Ticket 106: the free value checks with the typed VIN and mileage to copy.
    await userEvent.type(screen.getByLabelText("Trade-in VIN"), "5J6RW2H52ML000002");
    await userEvent.type(screen.getByLabelText("Mileage"), "61000");
    await screen.findByRole("button", { name: "Copy VIN" });
  }
  if (state === "FRESH_CASE") await userEvent.click(await screen.findByRole("button", { name: "Start a separate case for this car" }));
  if (state === "TYPED") {
    await userEvent.type(await screen.findByLabelText("Selling price"), "lots");
    await userEvent.click(screen.getByRole("button", { name: "Check these numbers" }));
    await screen.findByText(/Enter a dollar amount/);
  }
  if (state === "RESULT") {
    await userEvent.click(within(screen.getByRole("group", { name: "Do you still owe money on it?" })).getByLabelText("No"));
    await userEvent.click(within(screen.getByRole("group", { name: /own loan offer/ })).getByLabelText("No"));
    await userEvent.type(screen.getByLabelText("Trade-in VIN"), "5J6RW2H52ML000002");
    await userEvent.type(screen.getByLabelText("Mileage"), "61000");
    await userEvent.selectOptions(screen.getByLabelText("Condition"), "GOOD");
    vi.mocked(fetch).mockImplementationOnce(async () => new Response(JSON.stringify({ holder: "CASE", trade: null, analysis: {}, conditions: [] })));
    await userEvent.click(screen.getByRole("button", { name: "Show my result" }));
    await screen.findByRole("heading", { name: "Out the door: $28,295" });
    await userEvent.click(screen.getByText("1 more to ask about"));
  }
  expect((await axe.run(container, AA)).violations).toEqual([]);
});
// End ticket 105.

// Ticket 108: the registration-county chooser, including its tax explanation.
test("the Florida county chooser has no automated AA violations", async () => {
  const { BuyingCaseIntake } = await import("./BuyingCaseIntake");
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => new Response(JSON.stringify(
    String(input).endsWith("intake-options") ? { supported: ["US-FL"], consentPolicies: {} }
      : { detail: "Choose the registration county.", field: "buyerCounty",
        reason: "POSTAL_CODE_CROSSES_COUNTY_LINE", candidateJurisdictions: ["Columbia", "Suwannee"] }),
    { status: String(input).endsWith("intake-options") ? 200 : 422 }));
  const { container } = render(<main><h1>Synthetic Florida intake</h1><BuyingCaseIntake onCaseOpened={() => {}} /></main>);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/your ZIP code/i), "32024");
  await user.type(screen.getByLabelText(/dealer city/i), "32003");
  await user.selectOptions(screen.getByLabelText(/dealer state/i), "FL");
  await user.type(screen.getByLabelText(/^VIN$/), "1HGCM82633A004352");
  await user.click(screen.getByRole("button", { name: "Start my case" }));
  await user.selectOptions(await screen.findByLabelText(/Florida county where/), "Suwannee");
  expect((await axe.run(container, AA)).violations).toEqual([]);
});

// Ticket 112: the guided case page, its progress bar and every step panel, at a new case and after the desk check.
test.each(["NEW", "DESK_COMPARED"] as const)("every step of the guided case page at stage %s has no automated AA violations", async stage => {
  const { CASE_STEPS } = await import("./CaseFlow");
  const { caseFlowRecord } = await import("./CaseFlow.fixture");
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const body = caseFlowRecord(stage, String(input));
    return body === undefined ? new Response("{}", { status: 404 }) : new Response(JSON.stringify(body));
  });
  window.history.replaceState(null, "", "/#case=case-1&access=secret");
  const { container } = render(<App pollDelayMs={0} />);
  const progress = await screen.findByRole("navigation", { name: "Case progress" });
  await within(progress).findByRole("button", { current: "step" });
  for (const step of CASE_STEPS) {
    await userEvent.click(within(progress).getByRole("button", { name: new RegExp(step.name) }));
    expect(document.getElementById(`case-step-${step.id}-body`)).toBeVisible();
    expect((await axe.run(container, AA)).violations).toEqual([]);
  }
  window.history.replaceState(null, "", "/");
});
// End ticket 112.
