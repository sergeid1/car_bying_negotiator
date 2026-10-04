import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { BuyingJourneys, Journey } from "./BuyingJourneys";
import { CandidateResults } from "./CandidateVehicles";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const OPTIONS = {
  reference: { version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "honda", label: "Honda", models: [{ id: "honda-civic", label: "Civic", trims: [{ id: "honda-civic-ex", label: "EX" }] }] }],
    features: [] },
  bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
    minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20, maxReferenceVehicles: 5 },
};
const brief = (version: number) => ({ journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1",
  yearFrom: 2020, yearTo: 2024, make: { id: "honda", label: "Honda" }, model: { id: "honda-civic", label: "Civic" },
  trims: [{ id: "honda-civic-ex", label: "EX" }], maxMileage: 60000, priceCeilingMinor: 3_200_000, currency: "USD",
  postalCode: "27601", jurisdiction: "US-NC", radiusMiles: 50, mustHaveFeatures: [], niceToHaveFeatures: [],
  createdAt: "2026-10-03T12:00:00Z" });
const JOURNEY: Journey = { id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", brief: brief(2),
  versions: [1, 2].map(version => ({ version, createdAt: "2026-10-03T12:00:00Z" })), referenceVehicles: [] };

const snapshot = (staleness: "CURRENT" | "STALE" | "UNKNOWN", observedAt: string | null) => ({
  source: "SYNTHETIC_INVENTORY", retrievedAt: "2026-10-04T12:00:00Z", observedAt, staleness, synthetic: true });
const RESULTS: CandidateResults = {
  matcherVersion: "candidate-matching.v1", briefVersion: 2, referenceListVersion: "vehicle-reference-2026-10-v1",
  availability: "AVAILABLE", notice: "Synthetic inventory for development; these vehicles are not for sale.",
  provider: { name: "SYNTHETIC_INVENTORY", version: "deterministic-inventory.v1", licence: "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED",
    synthetic: true },
  retrievedAt: "2026-10-04T12:00:00Z",
  ordering: "Listings that state a fact missing a must-have are left out. The rest are ordered by your brief.",
  candidates: [
    { listingId: "match", vin: "2HGFE2F55NH000011", title: "2024 Honda Civic EX", year: 2024, make: "Honda", model: "Civic",
      trim: "EX", mileage: 30000, distanceMiles: 10, advertisedPriceMinor: 3_200_000, currency: "USD",
      matched: [{ kind: "MUST_HAVE", text: "Trim EX is one of your trims." },
        { kind: "REFERENCE", text: "Matches your reference trim (EX)." }],
      missed: [{ kind: "NICE_TO_HAVE", text: "Does not list heated seats, a nice-to-have." }],
      unconfirmed: [], snapshot: snapshot("CURRENT", "2026-10-04T11:00:00Z") },
    { listingId: "stale", vin: null, title: "2024 Honda Civic EX", year: 2024, make: "Honda", model: "Civic",
      trim: "EX", mileage: null, distanceMiles: 12, advertisedPriceMinor: null, currency: null,
      matched: [{ kind: "MUST_HAVE", text: "Trim EX is one of your trims." }], missed: [],
      unconfirmed: [{ kind: "MUST_HAVE", text: "The listing does not state its mileage." }],
      snapshot: snapshot("STALE", "2026-09-26T12:00:00Z") },
  ],
  excluded: [{ listingId: "near-miss", vin: "2HGFE2F55NH000012", title: "2024 Honda Civic EX",
    missed: [{ kind: "MUST_HAVE", text: "61,000 miles is over your 60,000-mile maximum." }], snapshot: snapshot("CURRENT", null) }],
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function server(candidates: () => Response) {
  const calls: { path: string; init?: RequestInit }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    calls.push({ path, init });
    if (path.endsWith("/candidates")) return candidates();
    if (path === "/api/v1/journeys/brief-options") return json(OPTIONS);
    if (path === "/api/v1/journeys") return json([{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "",
      currentVersion: 2, yearFrom: 2020, yearTo: 2024, make: "Honda", model: "Civic" }]);
    if (path === "/api/v1/journeys/j1/briefs/1") return json(brief(1));
    if (path === "/api/v1/journeys/j1") return json(JOURNEY);
    return json([]);
  }));
  return calls;
}

async function open(user: ReturnType<typeof userEvent.setup>, onSessionEnded = () => {}) {
  render(<BuyingJourneys session="s1" onSessionEnded={onSessionEnded} />);
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Honda Civic" }));
  return screen.findByRole("region", { name: "Candidate Vehicles" });
}

test("candidates are searched only on request and show reasons, snapshot source, retrieval time and staleness", async () => {
  const calls = server(() => json(RESULTS));
  const user = userEvent.setup();
  const region = await open(user);
  expect(calls.some(call => call.path.endsWith("/candidates"))).toBe(false);
  expect(within(region).getByText(/There is no hidden score/)).toBeInTheDocument();

  await user.click(within(region).getByRole("button", { name: "Search inventory for candidates" }));
  const list = await within(region).findByRole("list", { name: "Candidate Vehicles, brief version 2" });
  const search = calls.find(call => call.path === "/api/v1/journeys/j1/candidates");
  expect(search?.init?.method).toBe("POST");
  expect((search?.init?.headers as Record<string, string>)["Account-Session-Token"]).toBe("s1");
  expect(within(region).getByRole("heading", { name: "2 candidates for brief version 2" })).toHaveFocus();
  expect(within(region).getByText(/not for sale/, { selector: ".notice" })).toBeInTheDocument();

  const first = within(list).getByRole("article", { name: "Candidate 1: 2024 Honda Civic EX" });
  expect(within(first).getByText("Matches your reference trim (EX).")).toBeInTheDocument();
  expect(within(first).getByRole("heading", { name: "Misses" })).toBeInTheDocument();
  expect(within(first).getByText("$32,000 (advertised, not a quote)")).toBeInTheDocument();
  expect(within(first).getByText(/Listing Snapshot from SYNTHETIC_INVENTORY, retrieved Oct 4, 2026/)).toBeInTheDocument();
  expect(within(first).queryByText(/Stale listing/)).toBeNull();
  expect(within(first).queryByText(/score/i)).toBeNull();

  const second = within(list).getByRole("article", { name: "Candidate 2: 2024 Honda Civic EX" });
  expect(within(second).getByText(/Stale listing: last seen over a week before this search/)).toBeInTheDocument();
  expect(within(second).getByRole("heading", { name: "Not stated in the listing" })).toBeInTheDocument();
  expect(within(second).getAllByText("Not stated")).toHaveLength(3);

  expect(within(region).getByText("1 listing was left out for missing a must-have")).toBeInTheDocument();
  expect(within(region).getByText("61,000 miles is over your 60,000-mile maximum.")).toBeInTheDocument();
  expect(within(region).getByRole("button", { name: "Search inventory again" })).toBeEnabled();
});

test("an unavailable provider, a failure and an ended session are each stated", async () => {
  let reply = () => json({ ...RESULTS, availability: "UNAVAILABLE", notice: "Inventory provider unavailable. No listings were substituted.",
    candidates: [], excluded: [] });
  server(() => reply());
  const user = userEvent.setup();
  const ended = vi.fn();
  const region = await open(user, ended);
  await user.click(within(region).getByRole("button", { name: "Search inventory for candidates" }));
  expect(await within(region).findByRole("heading", { name: "No search results" })).toBeInTheDocument();
  expect(within(region).getByText("Inventory provider unavailable. No listings were substituted.")).toBeInTheDocument();
  expect(within(region).queryByRole("list")).toBeNull();

  reply = () => json({ code: "BRIEF_CHANGED", detail: "Your Shopping Brief changed while inventory was searched. Search again for the latest version." }, 409);
  await user.click(within(region).getByRole("button", { name: "Search inventory again" }));
  expect(await within(region).findByRole("alert")).toHaveTextContent("Search again for the latest version");

  reply = () => json({}, 401);
  await user.click(within(region).getByRole("button", { name: "Search inventory again" }));
  await vi.waitFor(() => expect(ended).toHaveBeenCalled());
});

test("an earlier brief version does not offer a search", async () => {
  server(() => json(RESULTS));
  const user = userEvent.setup();
  await open(user);
  await user.selectOptions(screen.getByLabelText("Brief version"), "1");
  expect(await screen.findByText("Candidate Vehicles are searched for the current brief version only.")).toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Candidate Vehicles" })).toBeNull();
});
