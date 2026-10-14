import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { ListingCapture, ListingPriceNote } from "./ListingCapture";
import { conflictingListing, higherCheck, readCapture, savedListing, unreadableCapture } from "./ListingCapture.fixture";
import { DeskCheck, type DeskCheckView } from "./DeskCheck";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";
import { AtTheDealer, type FirstResult } from "./AtTheDealer";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const EMPTY = { captures: [], snapshot: null, check: null, identityConflicts: [] };
function respond(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status }); }
type Call = { url: string; method: string; body?: BodyInit | null; headers: Record<string, string> };

/** A fake case API: each listing request answers from the queue, the page image with a PNG. */
function api(answers: unknown[]) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? "GET", body: init?.body, headers: (init?.headers ?? {}) as Record<string, string> });
    if (url.endsWith("/image")) return new Response(new Blob(["png"], { type: "image/png" }));
    const next = answers.length > 1 ? answers.shift() : answers[0];
    return next instanceof Response ? next : respond(next);
  });
  return calls;
}

test("pasted ad text is read, each field is checked against where it was read, and the saved ad shows the price check", async () => {
  vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: () => "blob:page", revokeObjectURL: () => {} }));
  const calls = api([EMPTY, { ...EMPTY, captures: [{ ...readCapture, stage: "QUEUED", reading: null }] },
    { ...EMPTY, captures: [readCapture] }, savedListing]);
  const confirmed = vi.fn();
  const user = userEvent.setup();
  render(<ListingCapture caseId="case-1" token="secret" pollDelayMs={10} onConfirmed={confirmed} />);
  expect(await screen.findByRole("heading", { name: "The dealer's ad for this car" })).toBeInTheDocument();
  expect(screen.getByText(/otdog does not visit the dealer's site/)).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Paste the ad's text" }));
  await user.type(screen.getByLabelText(/The ad's text/), "Our Price: $23,995*");
  await user.click(screen.getByRole("button", { name: "Read this text" }));
  const paste = calls.find(call => call.url.endsWith("/listing-captures/text"))!;
  expect(paste.method).toBe("POST");
  expect(paste.headers["Idempotency-Key"]).toBeTruthy();
  expect(JSON.parse(String(paste.body))).toEqual({ text: "Our Price: $23,995*" });

  expect(await screen.findByRole("heading", { name: "Check each field against the ad" })).toBeInTheDocument();
  expect(screen.getByLabelText("Price the ad shows")).toHaveValue("23,995");
  expect(screen.getByLabelText("VIN (if shown)")).toHaveValue("1HGCV1F34MA000001");
  expect(screen.getByText(/Read from “Our Price: \$23,995\*”/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /Show where the price the ad shows was read/ }));
  expect(await screen.findByRole("img", { name: /where the price the ad shows was read/ })).toBeInTheDocument();
  expect(calls.some(call => call.url.endsWith("/listing-captures/listing-1/pages/1/image"))).toBe(true);
  const fine = within(screen.getByRole("group", { name: "The ad's fine print about the price" }));
  expect(fine.getAllByRole("checkbox")).toHaveLength(2);
  await user.click(fine.getByRole("checkbox", { name: /loyalty rebate/ }));

  // Saving needs the Buyer to say they checked the fields.
  await user.click(screen.getByRole("button", { name: "Save this ad" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Check each field against your copy of the ad");
  await user.clear(screen.getByLabelText("Price the ad shows"));
  await user.type(screen.getByLabelText("Price the ad shows"), "23,895");
  await user.click(screen.getByRole("checkbox", { name: "I checked these against my copy of the ad" }));
  await user.click(screen.getByRole("button", { name: "Save this ad" }));
  const confirmation = calls.find(call => call.url.endsWith("/listing-captures/confirmation"))!;
  expect(JSON.parse(String(confirmation.body))).toMatchObject({ documentId: "listing-1", advertisedPriceMinor: 2389500, confirmed: true,
    vin: "1HGCV1F34MA000001", year: 2021, mileage: 38012, dealerName: "Gulf Coast Honda of Sarasota and Bradenton",
    priceConditions: ["Price requires financing through the dealer with approved credit."] });
  expect(await screen.findByText(/Your saved ad: \$23,995/)).toBeInTheDocument();
  expect(screen.getByText(higherCheck.summary)).toBeInTheDocument();
  expect(screen.getByText(higherCheck.deskQuestion!)).toBeInTheDocument();
  expect(screen.getByText(/loyalty rebate/)).toBeInTheDocument();
  expect(confirmed).toHaveBeenCalled();
  vi.unstubAllGlobals();
});

test("an unreadable copy falls back to typed fields without stopping", async () => {
  const calls = api([EMPTY, { ...EMPTY, captures: [unreadableCapture] }, { ...savedListing, check: null }]);
  const user = userEvent.setup();
  render(<ListingCapture caseId="case-1" token="secret" pollDelayMs={10} />);
  const file = new File(["png"], "IMG_0412.png", { type: "image/png" });
  await user.upload(await screen.findByLabelText("Add a screenshot or PDF of the ad"), file);
  expect(calls.find(call => call.url.endsWith("/listing-captures") && call.method === "POST")!.body).toBeInstanceOf(FormData);
  expect(await screen.findByRole("heading", { name: "This copy could not be read. Type what the ad shows" })).toBeInTheDocument();
  expect(screen.getByLabelText("Price the ad shows")).toHaveValue("");
  await user.type(screen.getByLabelText("Price the ad shows"), "about 24k");
  await user.click(screen.getByRole("checkbox", { name: "I checked these against my copy of the ad" }));
  await user.click(screen.getByRole("button", { name: "Save this ad" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter the price the ad shows, like 23,995.");
  expect(screen.getByLabelText("Price the ad shows")).toHaveAttribute("aria-invalid", "true");
  await user.clear(screen.getByLabelText("Price the ad shows"));
  await user.type(screen.getByLabelText("Price the ad shows"), "23,995");
  await user.type(screen.getByLabelText("Other fine print, one per line"), "Requires dealer financing");
  await user.click(screen.getByRole("button", { name: "Save this ad" }));
  await waitFor(() => expect(calls.some(call => call.url.endsWith("/confirmation"))).toBe(true));
  expect(JSON.parse(String(calls.find(call => call.url.endsWith("/confirmation"))!.body))).toEqual({
    documentId: "listing-2", advertisedPriceMinor: 2399500, confirmed: true, priceConditions: ["Requires dealer financing"] });
  expect(await screen.findByText(/Your saved ad/)).toBeInTheDocument();
});

test("a different VIN in the ad is shown with the choice of which car is right", async () => {
  const calls = api([conflictingListing, { ...conflictingListing, identityConflicts: [] }]);
  const user = userEvent.setup();
  render(<ListingCapture caseId="case-1" token="secret" />);
  expect(await screen.findByRole("alert")).toHaveTextContent("This ad is for VIN 1M8GDM9AXKP042788, but this case is for VIN 1HGCV1F34MA000001");
  expect(screen.getByText(/so their prices are not compared/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Keep this case's car" }));
  const resolution = calls.find(call => call.url.endsWith("/vehicle-evidence/conflicts/conflict-1/resolution"))!;
  expect(JSON.parse(String(resolution.body))).toEqual({ resolution: "KEEP_CONFIRMED_VEHICLE" });
  await waitFor(() => expect(screen.queryByRole("button", { name: "Keep this case's car" })).not.toBeInTheDocument());
});

test("a case API without listing captures still offers every way to add the ad", async () => {
  api([new Response("{}", { status: 404 })]);
  const user = userEvent.setup();
  render(<ListingCapture caseId="case-1" token="secret" compact />);
  expect(await screen.findByRole("heading", { level: 3, name: "Compare with the dealer's ad" })).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Type what the ad shows" }));
  expect(screen.getByRole("heading", { name: "Type what the ad shows" })).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByRole("button", { name: "Paste the ad's text" })).toBeInTheDocument();
});

test("the price note says what the ad and the paperwork show, never that otdog checked the dealer's site", () => {
  render(<ListingPriceNote check={{ ...higherCheck, paperwork: "DESK_PAPERWORK", verified: false }} title="The ad you saved" />);
  expect(screen.getByRole("heading", { name: "The ad you saved" })).toBeInTheDocument();
  expect(screen.getByText(/against paperwork numbers that are not yet verified/)).toBeInTheDocument();
  expect(document.body.textContent).toContain("otdog did not visit the dealer's site");
  expect(document.body.textContent).not.toMatch(/illegal|unlawful|bait|deceptive|we checked/i);
  cleanup();
  render(<ListingPriceNote check={{ ...higherCheck, status: "SAME", differenceMinor: 0, deskQuestion: null, priceConditions: [],
    summary: "The vehicle price on this paperwork, $23,995, is the same as the listing you saved on October 1, 2026." }} />);
  expect(screen.getByText(/is the same as the listing you saved/)).toBeInTheDocument();
  expect(screen.queryByText("Ask at the desk")).not.toBeInTheDocument();
});

test("the Desk Check shows the ad's price and fine print beside its differences; older checks render without it", async () => {
  const view: DeskCheckView = {
    status: "NO_DIFFERENCES", source: "VERIFIED_OFFER",
    agreedPrice: { offerVersion: 2, baselineVersion: 1, vin: "1HGCV1F34MA000001", sellingPriceMinor: 2549000, outTheDoorMinor: 2700000,
      acceptedAt: "2026-10-03T12:00:00Z" },
    paperwork: { documentId: "desk-1", uploadedAt: "2026-10-04T12:00:00Z", offerVersion: 3, vin: "1HGCV1F34MA000001" },
    findings: [], otherDifferences: [], unchangedTerms: 12,
    totalEffect: { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 0, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: null },
    freshCase: null, dealReviewVersion: null, listingPrice: { ...higherCheck, paperwork: "DESK_PAPERWORK" },
  };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/desk-check") ? respond(view) : respond({}, 404));
  render(<DeskCheck caseId="case-1" token="secret" />);
  const note = (await screen.findByRole("heading", { name: "The ad you saved" })).parentElement!;
  expect(within(note).getByText(higherCheck.deskQuestion!)).toBeInTheDocument();
  expect(within(note).getAllByRole("listitem")).toHaveLength(2);
  cleanup();
  const { listingPrice: _omitted, ...older } = view;
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/desk-check") ? respond(older) : respond({}, 404));
  render(<DeskCheck caseId="case-1" token="secret" />);
  expect(await screen.findByText(/No differences from your Agreed Price/)).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "The ad you saved" })).not.toBeInTheDocument();
});

test("a fresh report shows the ad's price check; a report saved before it renders without one", () => {
  const saved: CoreReport = { version: 1, asOf: "2026-10-02T12:00:00Z", status: "COMPLETE", analysis: {
    reviewLabel: "Review required", dealRating: "FAIR_NEGOTIATE", ratingBasis: "Selling price only", nextAction: "Ask the dealer",
    topReasons: ["One", "Two", "Three"], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [],
    policyVersions: {}, limitations: [] }, evidence: {}, reproduction: {} };
  render(<CoreDealReport report={saved} />);
  expect(screen.queryByRole("heading", { name: "The ad you saved" })).not.toBeInTheDocument();
  cleanup();
  render(<CoreDealReport report={{ ...saved, version: 2, analysis: { ...saved.analysis, listingPriceCheck: higherCheck } }} />);
  const note = screen.getByRole("heading", { name: "The ad you saved" }).parentElement!;
  expect(within(note).getByText(higherCheck.summary)).toBeInTheDocument();
  expect(within(note).getByText(higherCheck.deskQuestion!)).toBeInTheDocument();
  expect(within(note).getAllByRole("listitem")).toHaveLength(2);
});

test("I'm at the dealer shows a higher paperwork price against the saved ad, and adding the ad there refreshes the result", async () => {
  const finding = { code: "LISTING_PRICE_HIGHER", summary: higherCheck.summary, amountMinor: 149500, deskQuestion: higherCheck.deskQuestion! };
  const result: FirstResult = { route: "THIS_CASE", source: "TYPED_BY_BUYER", outTheDoor: { amountMinor: null, agreedMinor: null, differenceMinor: null },
    lead: finding, findings: [finding], listingPrice: { ...higherCheck, paperwork: "DESK_PAPERWORK", verified: false } };
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input), method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body, headers: {} });
    if (url.includes("/desk-start/result")) return respond(result);
    if (url.includes("/desk-start")) return respond({ status: "AWAITING_PAPERWORK", documentId: null, documentType: null, vin: null,
      route: null, agreedVin: null, caseVin: null, freshCase: null, questions: { trade: { ask: "NONE", paperworkShowsTrade: false, recorded: null },
        financing: { ask: false, paperworkShowsLoan: false, ownAprPercent: null, ownTermMonths: null } } });
    if (url.endsWith("/listing-captures/confirmation")) return respond(savedListing);
    if (url.endsWith("/listing-captures")) return respond(EMPTY);
    return respond({}, 404);
  });
  const user = userEvent.setup();
  render(<AtTheDealer files={[]} open={{ caseId: "case-1", token: "secret" }} pollDelayMs={1} onCaseOpened={vi.fn()} onFreshCase={vi.fn()}
    onDeskCheck={vi.fn()} onClose={vi.fn()} />);
  await user.type(await screen.findByLabelText("Selling price"), "25,490");
  await user.click(screen.getByRole("button", { name: "Check these numbers" }));
  expect(await screen.findByText(higherCheck.deskQuestion!)).toBeInTheDocument();
  const note = screen.getByRole("heading", { name: "The ad you saved" }).parentElement!;
  expect(within(note).queryByText("Ask at the desk")).not.toBeInTheDocument();
  expect(within(note).getAllByRole("listitem")).toHaveLength(2);
  expect(await screen.findByRole("heading", { level: 3, name: "Compare with the dealer's ad" })).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Type what the ad shows" }));
  await user.type(screen.getByLabelText("Price the ad shows"), "23,995");
  await user.click(screen.getByRole("checkbox", { name: "I checked these against my copy of the ad" }));
  await user.click(screen.getByRole("button", { name: "Save this ad" }));
  await waitFor(() => expect(calls.filter(call => call.url.includes("/desk-start/result"))).toHaveLength(2));
});
