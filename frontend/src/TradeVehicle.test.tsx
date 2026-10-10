import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { toMinor, TradeVehicle, type Trade, type TradeView } from "./TradeVehicle";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const CONDITIONS: TradeView["conditions"] = [
  { value: "EXCELLENT", label: "Excellent", description: "Looks and drives like new. No dents, warning lights or repairs needed, and a clean title." },
  { value: "GOOD", label: "Good", description: "Light wear such as small scratches or chips. Drives well and needs no repairs right now." },
  { value: "FAIR", label: "Fair", description: "Clear wear or cosmetic damage, or it needs a repair soon, such as tires or brakes. It still drives." },
  { value: "POOR", label: "Poor", description: "Major damage, mechanical problems, a warning light that stays on, or a branded title such as salvage or flood." },
];
function tradeFixture(overrides: Partial<Trade> = {}): Trade {
  return {
    id: "trade-1", revision: 2,
    vehicle: { vin: "5J6RW2H52ML000002", decodeOutcome: "DECODED", source: "Decoder", decoderVersion: "v1", decodedAt: "2026-10-01T12:00:00Z",
      year: "2021", make: "Honda", model: "CR-V", trim: "EX" },
    mileage: 61000, condition: "GOOD", moneyOwed: "YES", lender: "First Coast Credit Union", payoffMinor: 1825000,
    payoffGoodThrough: "2026-10-01", payoffUnknown: false, payoffStale: true, complete: true, changedAt: "2026-10-05T12:00:00Z",
    offers: [
      { id: "offer-old", amountMinor: 1500000, source: "Coastal Auto Buyers", offerDate: "2026-09-10", expiresOn: "2026-09-17",
        expired: true, usedForComparison: false, evidence: { documentId: "d1", state: "READY", pageCount: 1 }, createdAt: "2026-09-10T12:00:00Z" },
      { id: "offer-new", amountMinor: 1400000, source: "Sunshine Car Exchange", offerDate: "2026-10-04", expiresOn: "2026-10-11",
        expired: false, usedForComparison: true, evidence: { documentId: "d2", state: "READY", pageCount: 1 }, createdAt: "2026-10-04T12:00:00Z" },
    ],
    bestOfferId: "offer-new", ...overrides,
  };
}
function tradeView(trade: Trade | null, overrides: Partial<TradeView> = {}): TradeView {
  return { holder: "JOURNEY", trade, analysis: { analyzedAt: "2026-10-03T12:00:00Z", outdated: Boolean(trade) }, conditions: CONDITIONS, ...overrides };
}
function respond(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status }); }

test("dollars convert to whole cents and anything else is refused", () => {
  expect(toMinor("$18,250.5")).toBe(1825050);
  expect(toMinor("14000")).toBe(1400000);
  expect(toMinor("12.345")).toBeNull();
  expect(toMinor("about 14k")).toBeNull();
});

test("a recorded trade shows its payoff as stale, the outdated analysis and the offers, with the expired one not compared", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(tradeView(tradeFixture())));
  render(<TradeVehicle caseId="case" token="token" />);
  expect(await screen.findByText("2021 Honda CR-V EX")).toBeInTheDocument();
  expect(screen.getByText(/does not buy cars/)).toHaveTextContent("does not send your trade-in details to a dealer or to anyone else");
  expect(screen.getByText(/every case in the journey uses the same one/)).toBeInTheDocument();
  expect(screen.getByText("$18,250.00, good through Oct 1, 2026")).toBeInTheDocument();
  const alerts = screen.getAllByRole("alert").map(alert => alert.textContent);
  expect(alerts.some(text => text?.includes("so that analysis is outdated"))).toBe(true);
  expect(alerts.some(text => text?.includes("Stale payoff") && text.includes("Ask First Coast Credit Union for a new payoff quote"))).toBe(true);
  const offers = within(screen.getByRole("list", { name: "Your written trade offers" })).getAllByRole("listitem");
  expect(offers[0]).toHaveTextContent("Expired: not used for comparison");
  expect(offers[1]).toHaveTextContent("Best unexpired offer");
  expect(screen.getByText(/never requests, arranges, sells or refers trade offers/)).toBeInTheDocument();
});

test("an unknown payoff is shown as unknown, never as zero", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(tradeView(tradeFixture({ payoffMinor: null, payoffGoodThrough: null,
    payoffUnknown: true, payoffStale: false, offers: [], bestOfferId: null }))));
  render(<TradeVehicle caseId="case" token="token" />);
  expect(await screen.findByText("Unknown")).toBeInTheDocument();
  expect(screen.getByText(/The payoff is unknown, not zero/)).toBeInTheDocument();
  expect(screen.queryByText("$0.00")).not.toBeInTheDocument();
});

test("recording a trade asks whether money is owed and, for yes, the lender, payoff and good-through date", async () => {
  const user = userEvent.setup();
  const saved = tradeFixture({ revision: 1, payoffStale: false, payoffGoodThrough: "2026-10-20", offers: [], bestOfferId: null });
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
    if (init?.method === "PUT") {
      const body = JSON.parse(String(init.body));
      if (body.vin === "5J6RW2H52ML000003") return respond({ detail: "That VIN was not decoded because it is not a valid VIN.", code: "VIN_INVALID",
        errors: [{ field: "vin", message: "This VIN fails the VIN checksum. Enter all 17 characters exactly as shown on the car or its paperwork; VINs never use the letters I, O or Q." }] }, 400);
      return respond(tradeView(saved, { holder: "CASE", analysis: { analyzedAt: null, outdated: false } }));
    }
    return respond(tradeView(null, { holder: "CASE", analysis: { analyzedAt: null, outdated: false } }));
  });
  render(<TradeVehicle caseId="case" token="token" />);
  await user.click(await screen.findByRole("button", { name: "Add the car I'm trading in" }));
  await user.type(screen.getByLabelText("VIN"), "5J6RW2H52ML000003");
  await user.type(screen.getByLabelText("Mileage"), "61,000");
  await user.click(screen.getByRole("radio", { name: /^Good/ }));
  expect(screen.queryByLabelText("Lender")).not.toBeInTheDocument();
  await user.click(screen.getByRole("radio", { name: "Yes" }));
  expect(screen.getByText(/Ask your lender for a payoff quote/)).toBeInTheDocument();
  await user.type(screen.getByLabelText("Lender"), "First Coast Credit Union");
  await user.type(screen.getByLabelText("Payoff amount ($)"), "18,250");
  await user.type(screen.getByLabelText("Good through"), "2026-10-20");
  await user.click(screen.getByRole("button", { name: "Save trade-in" }));
  expect(await screen.findByText(/This VIN fails the VIN checksum/)).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText("That VIN was not decoded because it is not a valid VIN.")).toHaveFocus());
  expect(screen.getByLabelText("VIN")).toHaveAttribute("aria-invalid", "true");
  await user.clear(screen.getByLabelText("VIN"));
  await user.type(screen.getByLabelText("VIN"), "5J6RW2H52ML000002");
  await user.click(screen.getByRole("button", { name: "Save trade-in" }));
  expect(await screen.findByText("2021 Honda CR-V EX")).toBeInTheDocument();
  const put = fetch.mock.calls.filter(([, init]) => init?.method === "PUT").at(-1)!;
  expect(JSON.parse(String(put[1]!.body))).toEqual({ basedOnRevision: null, vin: "5J6RW2H52ML000002", mileage: 61000, condition: "GOOD",
    moneyOwed: "YES", lender: "First Coast Credit Union", payoffMinor: 1825000, payoffGoodThrough: "2026-10-20", payoffUnknown: false });
  expect(screen.getByText("This case holds its own trade-in.")).toBeInTheDocument();
});

test("I don't know the payoff sends no amount, and no is saved without lender or payoff", async () => {
  const user = userEvent.setup();
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) =>
    respond(tradeView(init?.method === "PUT" ? tradeFixture({ offers: [], bestOfferId: null }) : tradeFixture({ offers: [], bestOfferId: null, payoffStale: false }))));
  render(<TradeVehicle caseId="case" token="token" />);
  await user.click(await screen.findByRole("button", { name: "Edit trade-in" }));
  await user.click(screen.getByLabelText("I don't know the payoff yet"));
  expect(screen.queryByLabelText("Payoff amount ($)")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Save trade-in" }));
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true));
  const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === "PUT")![1]!.body));
  expect(body).toMatchObject({ basedOnRevision: 2, moneyOwed: "YES", lender: "First Coast Credit Union", payoffMinor: null,
    payoffGoodThrough: null, payoffUnknown: true });
});

test("a written offer is sent with its evidence through the trade offer endpoint", async () => {
  const user = userEvent.setup();
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (String(input).endsWith("/trade/offers") && init?.method === "POST") return respond(tradeView(tradeFixture()), 201);
    return respond(tradeView(tradeFixture({ offers: [], bestOfferId: null })));
  });
  render(<TradeVehicle caseId="case" token="token" />);
  await user.click(await screen.findByRole("button", { name: "Add a written offer" }));
  await user.type(screen.getByLabelText("Amount on the offer ($)"), "14,000");
  await user.type(screen.getByLabelText("Who made the offer"), "Sunshine Car Exchange");
  await user.type(screen.getByLabelText("Offer date"), "2026-10-04");
  await user.type(screen.getByLabelText("Expires"), "2026-10-11");
  await user.upload(screen.getByLabelText("Photo or PDF of the offer"), new File(["%PDF-1.4"], "offer.pdf", { type: "application/pdf" }));
  await user.click(screen.getByRole("button", { name: "Add written offer" }));
  expect(await screen.findByRole("list", { name: "Your written trade offers" })).toBeInTheDocument();
  const [url, init] = fetch.mock.calls.find(([, call]) => call?.method === "POST")!;
  expect(String(url)).toBe("/api/v1/cases/case/trade/offers");
  const body = init!.body as FormData;
  expect(body.get("amountMinor")).toBe("1400000");
  expect(body.get("source")).toBe("Sunshine Car Exchange");
  expect((body.get("file") as File).name).toBe("offer.pdf");
  expect((init!.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  expect((init!.headers as Record<string, string>)["Case-Access-Token"]).toBe("token");
});

test("a case started at the Desk Check carries the earlier case's trade with both credentials", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(tradeView(tradeFixture())));
  render(<TradeVehicle caseId="fresh" token="fresh-token" carryFrom={{ caseId: "agreed", token: "agreed-token" }} />);
  expect(await screen.findByText("2021 Honda CR-V EX")).toBeInTheDocument();
  const [url, init] = fetch.mock.calls[0];
  expect(String(url)).toBe("/api/v1/cases/fresh/trade/carry-over");
  expect(init?.headers).toMatchObject({ "Case-Access-Token": "fresh-token", "Source-Case-Access-Token": "agreed-token" });
  expect(JSON.parse(String(init?.body))).toEqual({ sourceCaseId: "agreed" });
});

test("an unreachable trade endpoint shows nothing", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 404 }));
  const { container } = render(<TradeVehicle caseId="case" token="token" />);
  await new Promise(resolve => setTimeout(resolve, 20));
  expect(container).toBeEmptyDOMElement();
});

// Ticket 106: free value checks the Buyer runs themselves, and the estimates they type back.
const ESTIMATES: Trade["estimates"] = [
  { id: "kbb", source: "Kelley Blue Book", lowMinor: 1300000, highMinor: null, checkedOn: "2026-08-01", old: true, createdAt: "2026-08-01T12:00:00Z" },
  { id: "carfax", source: "Carfax", lowMinor: 1400000, highMinor: 1600000, checkedOn: "2026-10-05", old: false, createdAt: "2026-10-05T12:00:00Z" },
];

test("a recorded trade links to the Carfax and CarMax checks with no tracking, and offers the VIN and mileage to copy", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(tradeView(tradeFixture())));
  render(<TradeVehicle caseId="case" token="token" />);
  const section = (await screen.findByRole("heading", { name: "Check what your car is worth" })).closest("section")!;
  const links = within(section).getAllByRole("link");
  expect(links.map(link => link.getAttribute("href"))).toEqual(["https://www.carfax.com/value/", "https://www.carmax.com/sell-my-car"]);
  for (const link of links) {
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(link.getAttribute("href")).not.toMatch(/[?#]/);
    expect(link).toHaveAccessibleName(/opens in a new tab/);
  }
  expect(section).toHaveTextContent("It is not an offer.");
  expect(section).toHaveTextContent("A written offer to buy the car, good for a set number of days");
  expect(section).toHaveTextContent("About 2 minutes.");
  expect(section).toHaveTextContent("does not look them up, check them or vouch for them");
  await user.click(within(section).getByRole("button", { name: "Copy VIN" }));
  expect(writeText).toHaveBeenCalledWith("5J6RW2H52ML000002");
  await user.click(within(section).getByRole("button", { name: "Copy mileage" }));
  expect(writeText).toHaveBeenLastCalledWith("61000");
  expect(within(section).getAllByRole("status").some(status => status.textContent === "Copied.")).toBe(true);
  // A CarMax or Carvana offer is steered to the written offers.
  expect(screen.getByText(/such as a CarMax or Carvana instant offer/)).toBeInTheDocument();
});

test("estimates are listed as estimates, never offers, with the old one called old", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(respond(tradeView(tradeFixture({ estimates: ESTIMATES, estimateInUseId: "carfax" }))));
  render(<TradeVehicle caseId="case" token="token" />);
  const items = within(await screen.findByRole("list", { name: "Your trade-in estimates" })).getAllByRole("listitem");
  expect(items[0]).toHaveTextContent("$13,000.00");
  expect(items[0]).toHaveTextContent("Your Kelley Blue Book estimate from Aug 1, 2026.");
  expect(items[0]).toHaveTextContent("Old: looked up more than 30 days ago");
  expect(items[1]).toHaveTextContent("$14,000.00 to $16,000.00");
  expect(items[1]).toHaveTextContent("Estimate, not an offer");
  expect(items[1]).toHaveTextContent("The most recent one.");
  const offers = within(screen.getByRole("list", { name: "Your written trade offers" })).getAllByRole("listitem");
  expect(offers.map(offer => offer.textContent).join(" ")).not.toContain("Carfax");
});

test("an estimate is recorded with its source, range and date, and a CarMax entry is steered to the written offers", async () => {
  const user = userEvent.setup();
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (String(input).endsWith("/trade/estimates") && init?.method === "POST") {
      const body = JSON.parse(String(init.body));
      if (!body.source) return respond({ detail: "Check the highlighted trade-in details.", errors: [
        { field: "source", message: "Enter where you looked up the value, such as Carfax, up to 120 characters." }] }, 400);
      return respond(tradeView(tradeFixture({ estimates: ESTIMATES.slice(1), estimateInUseId: "carfax" })), 201);
    }
    return respond(tradeView(tradeFixture()));
  });
  render(<TradeVehicle caseId="case" token="token" />);
  await user.click(await screen.findByRole("button", { name: "Add an estimate I looked up" }));
  await user.click(screen.getByRole("radio", { name: "Another site" }));
  await user.type(screen.getByLabelText("Site name"), "CarMax");
  expect(screen.getByText(/is a written offer, not an estimate/)).toHaveTextContent("Add a written offer");
  await user.clear(screen.getByLabelText("Site name"));
  await user.type(screen.getByLabelText("Value, or the low end of its range ($)"), "14,000");
  await user.click(screen.getByRole("button", { name: "Add estimate" }));
  expect(await screen.findByText(/Enter where you looked up the value/)).toBeInTheDocument();
  expect(screen.getByLabelText("Site name")).toHaveAttribute("aria-invalid", "true");
  await user.click(screen.getByRole("radio", { name: "Carfax" }));
  await user.type(screen.getByLabelText("High end of the range, if it showed one ($)"), "16000");
  await user.clear(screen.getByLabelText("Date you looked it up"));
  await user.type(screen.getByLabelText("Date you looked it up"), "2026-10-05");
  await user.click(screen.getByRole("button", { name: "Add estimate" }));
  expect(await screen.findByRole("list", { name: "Your trade-in estimates" })).toHaveTextContent("Your Carfax estimate from Oct 5, 2026.");
  const posts = fetch.mock.calls.filter(([url, init]) => String(url).endsWith("/trade/estimates") && init?.method === "POST");
  expect(JSON.parse(String(posts.at(-1)![1]!.body))).toEqual({ source: "Carfax", lowMinor: 1400000, highMinor: 1600000, checkedOn: "2026-10-05" });
  expect((posts.at(-1)![1]!.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
});
