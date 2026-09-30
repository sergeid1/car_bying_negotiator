import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { OfferVerification, offerChanges } from "./OfferVerification";

const region = (pageNumber: number, y = 0.1) => ({ pageNumber, x: 0.08, y, width: 0.55, height: 0.04 });

function candidate(id: string, field: string, value: string | null, unit: string, confidence: number, material = true, page = 1) {
  return {
    id, field, originalWording: `${field} as printed`, normalizedValue: value, normalizedUnit: unit,
    evidence: region(page), confidence, material, verificationState: "UNVERIFIED",
    currentCorrection: null as null | Record<string, unknown>, correctionHistory: [] as Record<string, unknown>[],
  };
}

function line(id: string, label: string, category: string, amountMinor: number, page = 2) {
  return {
    id, originalLabel: label, category, amountMinor, currency: "USD", evidence: region(page, 0.5),
    confidence: 0.96, verificationState: "UNVERIFIED",
    currentCorrection: null as null | Record<string, unknown>, correctionHistory: [] as Record<string, unknown>[],
  };
}

type Offer = { id: string; version: number; status: string; createdAt: string; terms: unknown };

let extraction: ReturnType<typeof freshExtraction>;
let offers: Offer[];
let calls: { url: string; method: string; headers: Record<string, string>; body?: unknown }[];

function freshExtraction() {
  return {
    id: "ext-1", documentId: "doc-1", version: 1, extractorVersion: "fixture-extractor.v1", modelVersion: "fixture-model.v1",
    candidates: [
      candidate("c-price", "SELLING_PRICE", "2500000", "USD_MINOR", 0.98),
      candidate("c-apr", "APR", "7.5", "PERCENT", 0.62, true, 2),
      candidate("c-financed", "AMOUNT_FINANCED", null, "USD_MINOR", 0.41, true, 2),
      candidate("c-dealer", "DEALER_NAME", "Triangle Motors", "TEXT", 0.98, false),
      candidate("c-trade", "TRADE_ALLOWANCE", "800000", "USD_MINOR", 0.97),
    ],
    lineItems: [
      line("l-vehicle", "Vehicle", "SELLING_PRICE", 2500000),
      line("l-mystery", "Mystery protection", "UNKNOWN", 89900),
      line("l-trade", "Trade payoff", "TRADE", 450000),
    ],
  };
}

function termsOf(value: ReturnType<typeof freshExtraction>) {
  return {
    candidates: value.candidates.map(item => ({
      field: item.field,
      value: (item.currentCorrection?.normalizedValue as string | undefined) ?? item.normalizedValue,
      unit: item.normalizedUnit,
    })),
    lineItems: value.lineItems.map(item => ({
      originalLabel: item.originalLabel, category: item.category, amountMinor: item.amountMinor, currency: "USD",
    })),
  };
}

beforeEach(() => {
  extraction = freshExtraction();
  offers = [];
  calls = [];
  // jsdom has no object URLs; the component shows the page once the image has been read.
  URL.createObjectURL = vi.fn(() => "blob:page");
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    calls.push({ url, method, headers: (init?.headers ?? {}) as Record<string, string>, body });
    const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
      status, headers: { "Content-Type": "application/json" },
    });
    if (url.endsWith("/cases/case-1/documents")) {
      return json([{ id: "doc-1", originalFilename: "buyers-order.pdf", createdAt: "2026-09-30T10:00:00Z" }]);
    }
    if (url.endsWith("/extractions/latest")) return json(structuredClone(extraction));
    if (url.includes("/pages/")) return new Response(new Blob(["png"], { type: "image/png" }));
    if (url.endsWith("/corrections")) {
      const target = [...extraction.candidates, ...extraction.lineItems].find(item => item.id === body.targetId)!;
      const correction = {
        id: `corr-${calls.length}`, version: target.correctionHistory.length + 1,
        normalizedValue: body.normalizedValue ?? null, normalizedUnit: body.normalizedUnit ?? null,
        category: body.category ?? null, amountMinor: body.amountMinor ?? null, createdAt: "2026-09-30T10:05:00Z",
      };
      target.currentCorrection = correction;
      target.correctionHistory = [...target.correctionHistory, correction];
      target.verificationState = "CORRECTED";
      return json(correction);
    }
    if (url.endsWith("/dealer-offers") && method === "POST") {
      const offer = {
        id: `offer-${offers.length + 1}`, version: offers.length + 1, status: "VERIFIED",
        createdAt: "2026-09-30T10:10:00Z", terms: termsOf(extraction),
      };
      offers.push(offer);
      return json(offer, 201);
    }
    if (url.endsWith("/dealer-offers/latest")) {
      return offers.length ? json(offers[offers.length - 1]) : json({ detail: "Dealer Offer not found" }, 404);
    }
    const version = url.match(/\/dealer-offers\/(\d+)$/);
    if (version) return json(offers[Number(version[1]) - 1]);
    throw new Error(`Unexpected ${method} to ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function renderPanel(onOfferFrozen = vi.fn()) {
  render(<OfferVerification caseId="case-1" token="case-secret" onOfferFrozen={onOfferFrozen} />);
  await screen.findByRole("list", { name: /deal terms read from the paperwork/i });
  return onOfferFrozen;
}

const term = (name: RegExp) => screen.getByRole("listitem", { name });

test("every read term shows its value, confidence, provenance and outlined source page", async () => {
  await renderPanel();

  const price = term(/^selling price$/i);
  expect(within(price).getByText("$25,000.00")).toBeInTheDocument();
  expect(within(price).getByText("98%")).toBeInTheDocument();
  expect(within(price).getByText("“SELLING_PRICE as printed”")).toBeInTheDocument();
  expect(within(price).getByText("Page 1 of “buyers-order.pdf”")).toBeInTheDocument();
  expect(screen.getByText(/extraction version 1 · fixture-extractor.v1/i)).toBeInTheDocument();

  const page = await within(price).findByRole("img", { name: /page 1 of “buyers-order.pdf”, where selling price was read/i });
  expect(page).toHaveAttribute("src", "blob:page");
  const outline = price.querySelector<HTMLElement>(".source-region")!;
  expect(outline.style.left).toBe("8%");
  expect(outline.style.top).toBe("10%");
  expect(outline.style.width).toBe("55%");

  expect(within(term(/^apr$/i)).getByText("Low confidence")).toBeInTheDocument();
  expect(within(term(/^amount financed$/i)).getByText("Missing")).toBeInTheDocument();
  expect(within(term(/mystery protection/i)).getByText(/ask the dealer to explain it/i)).toBeInTheDocument();

  const pageRequests = calls.filter(call => call.url.includes("/pages/"));
  expect(pageRequests.map(call => call.url)).toEqual([
    "/api/v1/cases/case-1/documents/doc-1/pages/1/image",
    "/api/v1/cases/case-1/documents/doc-1/pages/2/image",
  ]);
  expect(pageRequests.every(call => call.headers["Case-Access-Token"] === "case-secret")).toBe(true);
});

test("freezing stays blocked until every term, including missing and low-confidence ones, has a decision", async () => {
  const user = userEvent.setup();
  const onOfferFrozen = await renderPanel();
  const freeze = screen.getByRole("button", { name: /freeze this dealer offer/i });
  expect(freeze).toBeDisabled();
  expect(screen.getByText("8 terms still need your decision before the Dealer Offer can be frozen.")).toBeInTheDocument();

  // A missing material value cannot be confirmed as blank.
  expect(within(term(/^amount financed$/i)).queryByRole("button", { name: /^confirm/i })).toBeNull();

  for (const name of [/^selling price$/i, /^apr$/i, /^dealer name$/i, /^trade-in allowance$/i,
    /“vehicle”/i, /mystery protection/i, /trade payoff/i]) {
    await user.click(within(term(name)).getByRole("button", { name: /^confirm/i }));
  }
  expect(screen.getByText(/1 term still needs your decision/i)).toBeInTheDocument();
  expect(freeze).toBeDisabled();
  expect(calls.some(call => call.url.endsWith("/dealer-offers") && call.method === "POST")).toBe(false);

  await user.click(within(term(/^amount financed$/i)).getByRole("button", { name: /correct amount financed/i }));
  await user.type(screen.getByLabelText(/amount financed as shown on page 2/i), "26,800.00");
  await user.click(screen.getByRole("button", { name: /save correction/i }));
  await waitFor(() => expect(freeze).toBeEnabled());

  await user.click(freeze);
  await screen.findByText(/dealer offer version 1 is frozen/i);
  const posted = calls.find(call => call.url.endsWith("/dealer-offers") && call.method === "POST")!;
  expect(posted.body).toEqual({
    extractionId: "ext-1",
    confirmedCandidateIds: ["c-price", "c-apr", "c-financed", "c-dealer", "c-trade"],
    confirmedLineItemIds: ["l-vehicle", "l-mystery", "l-trade"],
  });
  expect(posted.headers["Idempotency-Key"]).toMatch(/^offer-ext-1-/);
  expect(onOfferFrozen).toHaveBeenCalledWith({ version: 1 });
});

test("a correction posts the Buyer's value and keeps the read value and its provenance beside it", async () => {
  const user = userEvent.setup();
  await renderPanel();
  const financed = term(/^amount financed$/i);
  await user.click(within(financed).getByRole("button", { name: /correct amount financed/i }));
  await user.type(within(financed).getByLabelText(/amount financed as shown on page 2/i), "$26,800");
  await user.click(within(financed).getByRole("button", { name: /save correction/i }));

  await within(financed).findByText("Your value");
  const posted = calls.find(call => call.url.endsWith("/corrections"))!;
  expect(posted.url).toBe("/api/v1/cases/case-1/documents/doc-1/extractions/ext-1/corrections");
  expect(posted.body).toEqual({
    targetType: "CANDIDATE", targetId: "c-financed", normalizedValue: "2680000", normalizedUnit: "USD_MINOR",
  });
  expect(posted.headers["Idempotency-Key"]).toBeTruthy();
  expect(within(financed).getByText("$26,800.00")).toBeInTheDocument();
  expect(within(financed).getByText("Read from paperwork (kept)")).toBeInTheDocument();
  expect(within(financed).getByText("Not found on the paperwork")).toBeInTheDocument();
  expect(within(financed).getByText("“AMOUNT_FINANCED as printed”")).toBeInTheDocument();
  expect(within(financed).getByText("Corrected by you")).toBeInTheDocument();

  const mystery = term(/mystery protection/i);
  await user.click(within(mystery).getByRole("button", { name: /^correct/i }));
  const amount = within(mystery).getByLabelText(/amount on page 2/i);
  await user.clear(amount);
  await user.type(amount, "899");
  await user.selectOptions(within(mystery).getByLabelText(/kind of charge/i), "ADD_ON");
  await user.click(within(mystery).getByRole("button", { name: /save correction/i }));
  await within(mystery).findByText("Your value");
  expect(calls.filter(call => call.url.endsWith("/corrections")).at(-1)!.body).toEqual({
    targetType: "LINE_ITEM", targetId: "l-mystery", category: "ADD_ON", amountMinor: 89900,
  });
});

test("trade-in terms are shown apart from the purchase terms, as written, with no assessment", async () => {
  await renderPanel();
  const trade = screen.getByRole("region", { name: /trade-in terms on the paperwork/i });
  expect(within(trade).getByText("$8,000.00")).toBeInTheDocument();
  expect(within(trade).getByText("$4,500.00 · Trade")).toBeInTheDocument();
  expect(within(trade).getByText(/does not rate or value the trade-in/i)).toBeInTheDocument();
  expect(trade.textContent).not.toMatch(/\b(fair|unfair|good|bad|low|high|below|above|market|rating|overpa|underpa)\w*/i);

  const purchase = screen.getByRole("list", { name: /deal terms read from the paperwork/i });
  expect(within(purchase).queryByText(/trade/i)).toBeNull();
});

test("a later change freezes a new version and the Buyer sees which terms differ from the one before", async () => {
  const user = userEvent.setup();
  await renderPanel();
  for (const item of screen.getAllByRole("button", { name: /^confirm/i })) await user.click(item);
  const financed = term(/^amount financed$/i);
  await user.click(within(financed).getByRole("button", { name: /correct amount financed/i }));
  await user.type(within(financed).getByLabelText(/as shown on page 2/i), "26800");
  await user.click(within(financed).getByRole("button", { name: /save correction/i }));
  const freeze = screen.getByRole("button", { name: /freeze this dealer offer/i });
  await waitFor(() => expect(freeze).toBeEnabled());
  await user.click(freeze);
  await screen.findByText(/version 1 is the first frozen dealer offer/i);
  const firstVersion = structuredClone(offers[0]);
  // An unchanged set of decisions cannot be frozen twice; only a change opens a new version.
  expect(freeze).toBeDisabled();

  const price = term(/^selling price$/i);
  await user.click(within(price).getByRole("button", { name: /correct selling price/i }));
  await user.type(within(price).getByLabelText(/as shown on page 1/i), "24,500");
  await user.click(within(price).getByRole("button", { name: /save correction/i }));
  await waitFor(() => expect(freeze).toBeEnabled());
  await user.click(freeze);

  const changes = await screen.findByRole("table", { name: /terms that differ between version 1 and version 2/i });
  const rows = within(changes).getAllByRole("row").slice(1);
  expect(rows).toHaveLength(1);
  expect(within(rows[0]).getByRole("rowheader")).toHaveTextContent("Selling price");
  expect(within(rows[0]).getAllByRole("cell").map(cell => cell.textContent)).toEqual(["$25,000.00", "$24,500.00"]);

  const offerPosts = calls.filter(call => call.url.includes("/dealer-offers") && call.method !== "GET");
  expect(offerPosts.map(call => call.method)).toEqual(["POST", "POST"]);
  expect(offerPosts[0].headers["Idempotency-Key"]).not.toBe(offerPosts[1].headers["Idempotency-Key"]);
  expect(offers[0]).toEqual(firstVersion);
});

test("differences include added and removed terms, not only changed values", () => {
  const offer = (version: number, candidates: unknown[], lineItems: unknown[]) =>
    ({ id: `o${version}`, version, status: "VERIFIED", createdAt: "", terms: { candidates, lineItems } }) as never;
  const changes = offerChanges(
    offer(1, [{ field: "APR", value: "7.5", unit: "PERCENT" }],
      [{ originalLabel: "Doc fee", category: "DEALER_FEE", amountMinor: 69900, currency: "USD" }]),
    offer(2, [{ field: "APR", value: "7.5", unit: "PERCENT" }, { field: "CASH_DOWN", value: "100000", unit: "USD_MINOR" }], []));
  expect(changes).toEqual([
    { term: "Cash down", before: "Not in this version", after: "$1,000.00" },
    { term: "Line item “Doc fee”", before: "$699.00 · Dealer fee", after: "Not in this version" },
  ]);
});
