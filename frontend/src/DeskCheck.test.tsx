import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { DeskCheck, DeskFreshCase, type DeskCheckView } from "./DeskCheck";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import App from "./App";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const agreedPrice = { offerVersion: 2, baselineVersion: 1, vin: "1HGCM82633A004352", sellingPriceMinor: 2500000,
  outTheDoorMinor: 2680000, acceptedAt: "2026-10-04T12:00:00Z" };
const serviceContract = { group: "ADDED", term: "line:service contract#1", label: "Service contract", kind: "ADDED", agreed: null,
  desk: { amountMinor: 149500, currency: "USD", category: "ADD_ON" }, delta: null, unit: "USD_MINOR", severity: "MATERIAL",
  verified: false, evidence: { pageNumber: 2 }, buyerAction: "Ask the dealer to clarify and verify this term." };
const total = { group: "TOTAL", term: "candidate:OUT_THE_DOOR_TOTAL#1", label: "OUT_THE_DOOR_TOTAL", kind: "CHANGED",
  agreed: { value: "2680000", unit: "USD_MINOR" }, desk: { value: "2829500", unit: "USD_MINOR" }, delta: 149500, unit: "USD_MINOR",
  severity: "MATERIAL", verified: false, evidence: null, buyerAction: null };
function deskFixture(state: "AWAITING" | "READ" | "REVIEWED" | "OTHER_CAR" | "NONE"): DeskCheckView {
  const base: DeskCheckView = { status: "AWAITING_PAPERWORK", source: null, agreedPrice, paperwork: null, findings: [], otherDifferences: [],
    unchangedTerms: 0, totalEffect: { basis: "UNAVAILABLE", currency: "USD", outTheDoorDeltaMinor: null, preTaxDeltaMinor: null,
      taxEffectMinor: null, addedChargesMinor: null }, freshCase: null, dealReviewVersion: null };
  const paperwork = { documentId: "d2", uploadedAt: "2026-10-04T13:00:00Z", offerVersion: null, vin: agreedPrice.vin };
  if (state === "NONE") return { ...base, status: "NO_AGREED_PRICE", agreedPrice: null };
  if (state === "AWAITING") return base;
  if (state === "OTHER_CAR") return { ...base, status: "DIFFERENT_VIN", source: "READ_FROM_PAPERWORK", paperwork: { ...paperwork, vin: "1M8GDM9AXKP042788" },
    findings: [{ group: "VEHICLE", term: "candidate:VEHICLE_VIN#1", label: "VEHICLE_VIN", kind: "CHANGED", agreed: { value: agreedPrice.vin, unit: "VIN" },
      desk: { value: "1M8GDM9AXKP042788", unit: "VIN" }, delta: null, unit: "VIN", severity: "BLOCKING", verified: false, evidence: { pageNumber: 1 },
      buyerAction: "Start a separate case for this car." }],
    freshCase: { vin: "1M8GDM9AXKP042788", dealerPostalCode: "27604", purchaseMethod: "CASH" } };
  const reviewed = state === "REVIEWED";
  return { ...base, status: "DIFFERENCES", source: reviewed ? "DEAL_REVIEW" : "READ_FROM_PAPERWORK", paperwork,
    findings: [{ ...serviceContract, verified: reviewed }, { ...total, verified: reviewed }],
    otherDifferences: [{ group: "OTHER", term: "candidate:DOCUMENT_NUMBER#1", label: "DOCUMENT_NUMBER", kind: "CHANGED", agreed: { value: "A-42", unit: "TEXT" },
      desk: { value: "A-57", unit: "TEXT" }, delta: null, unit: "TEXT", severity: "MATERIAL", verified: false, evidence: null, buyerAction: null }],
    unchangedTerms: 14, dealReviewVersion: reviewed ? 1 : null,
    totalEffect: reviewed ? { basis: "CALCULATED", currency: "USD", outTheDoorDeltaMinor: 149500, preTaxDeltaMinor: 149500, taxEffectMinor: 0, addedChargesMinor: 149500 }
      : { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 149500, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: 149500 } };
}

function stub(views: DeskCheckView[]) {
  const calls: { url: string; method: string }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input), method = init?.method ?? "GET";
    calls.push({ url, method });
    if (url.endsWith("/desk-check")) return new Response(JSON.stringify(views.length > 1 ? views.shift() : views[0]));
    if (url.endsWith("/document-limits")) return new Response(JSON.stringify({ acceptedMediaTypes: ["application/pdf", "image/jpeg"],
      maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 }));
    if (url.endsWith("/documents") && method === "POST") return new Response(JSON.stringify({
      document: { id: "d2", pages: [] }, operation: { id: "op2", stage: "QUEUED" } }), { status: 202 });
    if (url.includes("/operations/")) return new Response(JSON.stringify({ id: "op2", documentId: "d2", stage: "EXTRACTION_COMPLETE",
      retryCount: 0, updatedAt: "2026-10-04T13:00:00Z" }));
    if (url.includes("/documents/")) return new Response(JSON.stringify({ pages: [] }));
    if (url.includes("/intake-options")) return new Response(JSON.stringify({ buyerState: "US-NC", supported: ["US-NC"], consentPolicies: {} }));
    if (url.endsWith("/api/v1/cases") && method === "POST") return new Response(JSON.stringify({ case: { id: "fresh" }, accessToken: "fresh-token" }), { status: 201 });
    if (url.endsWith("/vehicle-evidence")) return new Response(JSON.stringify({ identityConflicts: [], reliableForAnalysis: true }));
    throw new Error(`Unexpected ${method} ${url}`);
  });
  return calls;
}

test("a case without an Agreed Price shows no Desk Check", async () => {
  const calls = stub([deskFixture("NONE")]);
  const { container } = render(<DeskCheck caseId="case" token="token" />);
  await waitFor(() => expect(calls.some(call => call.url.endsWith("/desk-check"))).toBe(true));
  expect(container).toBeEmptyDOMElement();
});

test("the camera control is the next tap, and the check refreshes when the paperwork is read", async () => {
  const calls = stub([deskFixture("AWAITING"), deskFixture("READ")]);
  render(<DeskCheck caseId="case" token="token" />);
  expect(await screen.findByRole("heading", { name: "Desk Check" })).toBeInTheDocument();
  expect(screen.getByText(/Photograph the paperwork the dealer gives you/)).toBeInTheDocument();
  const camera = screen.getByLabelText("Photograph a page with your camera");
  expect(camera).toHaveAttribute("capture", "environment");
  await userEvent.upload(camera, new File(["desk"], "desk.jpg", { type: "image/jpeg" }));
  expect(await screen.findByText(/Added: Service contract/)).toBeInTheDocument();
  expect(calls.some(call => call.method === "POST" && call.url === "/api/v1/cases/case/documents")).toBe(true);
});

test("scenario 23: the added service contract leads, with its total effect, and nothing as read is called verified", async () => {
  stub([deskFixture("READ")]);
  render(<DeskCheck caseId="case" token="token" />);
  const list = await screen.findByRole("list", { name: "Differences from your Agreed Price" });
  const items = within(list).getAllByRole("listitem");
  expect(items[0]).toHaveTextContent("Added: Service contract, $1,495.00. It is not in your Agreed Price.");
  expect(items[1]).toHaveTextContent("Out-the-door total changed from $26,800.00 to $28,295.00 (+$1,495.00).");
  expect(screen.getByText(/2 differences from your Agreed Price\. The out-the-door total is \$1,495\.00 higher than agreed, as printed on the paperwork; added charges come to \$1,495\.00\./))
    .toBeInTheDocument();
  expect(screen.queryByText("Verified")).not.toBeInTheDocument();
  expect(within(list).getAllByText("As read, not verified")).toHaveLength(2);
  expect(screen.getByText(/not yet verified/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Verify these terms" })).toBeInTheDocument();
  expect(screen.getByText("1 other change on the paperwork")).toBeInTheDocument();
});

test("a saved Deal Review reports verified differences with the calculated tax effect", async () => {
  stub([deskFixture("REVIEWED")]);
  render(<DeskCheck caseId="case" token="token" />);
  const list = await screen.findByRole("list", { name: "Differences from your Agreed Price" });
  expect(within(list).getAllByText("Verified")).toHaveLength(2);
  expect(screen.getByText(/calculated from the verified terms; \+\$1,495\.00 before tax and \+\$0\.00 in tax/)).toBeInTheDocument();
  expect(screen.getByText(/From saved Deal Review 1, against the Approved Baseline of your Agreed Price\./)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Verify these terms" })).not.toBeInTheDocument();
});

test("an unchanged documentary fee shows the state-rule question without calling it a new charge", async () => {
  const view = deskFixture("REVIEWED");
  const question = "Texas has a $225 documentary fee safe harbor. Has your dealership filed with the OCCC?";
  view.otherDifferences = [{ group: "OTHER", term: "doc-fee:state-rule", label: "Dealer documentary fee",
    kind: "DOC_FEE_CHECK", agreed: null, desk: { amountMinor: 89900, currency: "USD" }, delta: null,
    unit: "USD_MINOR", severity: "MATERIAL", verified: true, evidence: null, buyerAction: question }];
  stub([view]);
  render(<DeskCheck caseId="case" token="token" />);
  await userEvent.click(await screen.findByText("Other paperwork notes and state fee checks"));
  expect(screen.getByText(question)).toBeVisible();
  expect(screen.getByText(/Dealer documentary fee on the paperwork: \$899.00/)).toBeVisible();
  expect(screen.queryByText(/documentary fee is new/)).not.toBeInTheDocument();
});

test("scenario 24: a different VIN blocks the check and starts a separate case with the same paperwork", async () => {
  const calls = stub([deskFixture("AWAITING"), deskFixture("OTHER_CAR")]);
  const onFreshCase = vi.fn();
  render(<DeskCheck caseId="case" token="token" onFreshCase={onFreshCase} />);
  const paperwork = new File(["four-square"], "four-square.pdf", { type: "application/pdf" });
  await userEvent.upload(await screen.findByLabelText("Dealer paperwork (PDF or photos)"), paperwork);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Different car: this paperwork is for VIN 1M8GDM9AXKP042788, not the agreed car (VIN 1HGCM82633A004352). Nothing on it was compared with or added to your Agreed Price.");
  expect(screen.queryByRole("list", { name: "Differences from your Agreed Price" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Start a fresh case for this car" }));
  expect(await screen.findByRole("heading", { name: "Start a fresh case for VIN 1M8GDM9AXKP042788" })).toBeInTheDocument();
  expect(screen.getByLabelText("VIN")).toHaveValue("1M8GDM9AXKP042788");
  expect(screen.getByLabelText("Dealer city")).toHaveValue("27604");
  await userEvent.type(screen.getByLabelText("Your ZIP code"), "27601");
  await userEvent.click(screen.getByRole("button", { name: /start my case/i }));
  await waitFor(() => expect(onFreshCase).toHaveBeenCalledWith({ caseId: "fresh", token: "fresh-token",
    agreed: { caseId: "case", token: "token" }, agreedVin: "1HGCM82633A004352", vin: "1M8GDM9AXKP042788", files: [paperwork] }));
  // The fresh case starts through the ordinary case-start path, with no journey or account.
  const start = calls.find(call => call.url === "/api/v1/cases" && call.method === "POST");
  expect(start).toBeDefined();
});

test("a fresh desk case is labelled apart from the agreed car and receives the photographed paperwork", async () => {
  const calls = stub([deskFixture("NONE")]);
  const back = vi.fn();
  const paperwork = new File(["four-square"], "four-square.pdf", { type: "application/pdf" });
  render(<main>
    <DeskFreshCase value={{ caseId: "fresh", token: "fresh-token", agreed: { caseId: "case", token: "token" },
      agreedVin: "1HGCM82633A004352", vin: "1M8GDM9AXKP042788", files: [paperwork] }} onBack={back} />
    <DealerDocumentUpload caseId="fresh" token="fresh-token" initialFiles={[paperwork]} />
  </main>);
  expect(screen.getByRole("heading", { name: "Separate case for VIN 1M8GDM9AXKP042788" })).toBeInTheDocument();
  expect(screen.getByText(/separate from your Agreed Price on VIN 1HGCM82633A004352, which is unchanged/)).toBeInTheDocument();
  await waitFor(() => expect(calls.filter(call => call.method === "POST" && call.url === "/api/v1/cases/fresh/documents")).toHaveLength(1));
  await userEvent.click(screen.getByRole("button", { name: "Back to the agreed car" }));
  expect(back).toHaveBeenCalled();
});

test("in the app, a different car opens a separate case with the paperwork and leads back to the agreed car", async () => {
  const posts: string[] = [];
  let cases = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input), method = init?.method ?? "GET";
    if (method === "POST") posts.push(url);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (url.includes("/intake-options")) return json({ buyerState: "US-NC", supported: ["US-NC"], consentPolicies: {} });
    if (url === "/api/v1/cases" && method === "POST") { cases++; return json({ case: { id: cases === 1 ? "agreed" : "fresh" }, accessToken: `token-${cases}` }, 201); }
    if (url.endsWith("/vehicle-evidence")) return json({ identityConflicts: [], reliableForAnalysis: true });
    if (url === "/api/v1/cases/agreed/desk-check") return json(deskFixture("OTHER_CAR"));
    if (url === "/api/v1/cases/fresh/desk-check") return json(deskFixture("NONE"));
    if (url.endsWith("/document-limits")) return json({ acceptedMediaTypes: ["application/pdf"], maxFileBytes: 20971520, maxPages: 25, maxPixels: 40000000, minWidth: 2, minHeight: 2 });
    if (url.endsWith("/documents") && method === "POST") return json({ document: { id: "d", pages: [] }, operation: { id: "op", stage: "QUEUED" } }, 202);
    if (url.includes("/operations/")) return json({ id: "op", documentId: "d", stage: "EXTRACTION_COMPLETE", retryCount: 0, updatedAt: "2026-10-04T13:00:00Z" });
    return json({ detail: "Not found" }, 404);
  });
  render(<App pollDelayMs={0} />);
  await userEvent.type(await screen.findByLabelText("Your ZIP code"), "27601");
  await userEvent.type(screen.getByLabelText("Dealer city"), "27604");
  await userEvent.type(screen.getByLabelText("VIN"), "1HGCM82633A004352");
  await userEvent.click(screen.getByRole("button", { name: /start my case/i }));
  const desk = within(await screen.findByRole("region", { name: "Desk Check" }));
  await userEvent.upload(desk.getByLabelText("Dealer paperwork (PDF or photos)"), new File(["four-square"], "four-square.pdf", { type: "application/pdf" }));
  await userEvent.click(await desk.findByRole("button", { name: "Start a fresh case for this car" }));
  await userEvent.type(desk.getByLabelText("Your ZIP code"), "27601");
  await userEvent.click(desk.getByRole("button", { name: /start my case/i }));
  expect(await screen.findByRole("heading", { name: "Separate case for VIN 1M8GDM9AXKP042788" })).toBeInTheDocument();
  await waitFor(() => expect(posts).toContain("/api/v1/cases/fresh/documents"));
  expect(screen.queryByRole("region", { name: "Desk Check" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Back to the agreed car" }));
  expect(await screen.findByRole("region", { name: "Desk Check" })).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: /Separate case/ })).not.toBeInTheDocument();
});

test("an unpurchased case explains the payment point instead of failing silently", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/desk-check")
    ? new Response(JSON.stringify({ code: "PURCHASE_REQUIRED", offerId: "one-time-case.v1" }), { status: 402 })
    : new Response(null, { status: 404 }));
  render(<DeskCheck caseId="case" token="token" />);
  expect(await screen.findByRole("alert")).toHaveTextContent("needs the one-time purchase first");
});
