import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { JourneyCandidates, ShortlistEntry } from "./JourneyCandidates";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const entry = (id: string): ShortlistEntry => ({ candidateId: id, caseId: null,
  marketCheck: { status: "AVAILABLE", range: { lowMinor: 2000000, highMinor: 2400000, currency: "USD" }, confidence: "MEDIUM",
    limitation: "Small sample.", provider: { retrievedAt: "2026-10-04T12:00:00Z" },
    vintage: { oldestObservationAt: "2026-10-01T12:00:00Z", newestObservationAt: "2026-10-03T12:00:00Z" } },
  candidate: { candidateId: id, listingId: "same-listing-id", vin: null, title: `2024 Honda ${id}`, year: 2024,
    make: "Honda", model: "Civic", trim: "EX", mileage: 20000, distanceMiles: 10, advertisedPriceMinor: 2200000,
    currency: "USD", matched: [], missed: [], unconfirmed: [], snapshot: { source: "Inventory", synthetic: false,
      retrievedAt: "2026-10-04T12:00:00Z", observedAt: null, staleness: "UNKNOWN" } } });

test("stored candidate identifiers preserve Buyer order through changes and remount; case creation confirms terms", async () => {
  let rows = [entry("first"), entry("second")];
  const opened = vi.fn();
  const request = vi.fn(async (path: string, init?: RequestInit & { key?: string }): Promise<unknown> => {
    if (path.endsWith("/case")) return { case: { id: "case-1" }, accessToken: "case-token" };
    if (init?.method === "PUT") {
      const ids = JSON.parse(String(init.body)).candidateIds as string[];
      rows = ids.map(id => rows.find(row => row.candidateId === id)!);
    }
    return rows;
  });
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ supported: ["US-NC"], buyerState: "US-NC",
    consentPolicies: { serviceProcessing: "p1", humanReview: "p2", anonymizedResearch: "p3" } })));
  const mount = () => render(<JourneyCandidates journeyId="j1" briefVersion={1} postalCode="27601"
    request={request as never} onSessionEnded={() => {}} onCaseOpened={opened} />);
  const user = userEvent.setup();
  const first = mount();
  await user.click(await screen.findByRole("button", { name: "Move 2024 Honda second up" }));
  expect(JSON.parse(String(request.mock.calls.at(-1)?.[1]?.body))).toEqual({ candidateIds: ["second", "first"] });
  first.unmount();
  mount();
  const shortlist = await screen.findByRole("list", { name: "Your shortlist order" });
  await screen.findByRole("article", { name: "Candidate 1: 2024 Honda second" });
  expect(within(shortlist).getAllByRole("article")[0]).toHaveAccessibleName("Candidate 1: 2024 Honda second");
  expect(within(shortlist).getAllByText(/Confidence: medium/)).toHaveLength(2);
  await user.click(within(shortlist).getAllByRole("button", { name: "Open a Buying Case" })[0]);
  const form = screen.getByRole("region", { name: "Confirm your Buying Case" });
  expect(within(form).getByLabelText("Your ZIP code")).toHaveValue("27601");
  await user.type(within(form).getByLabelText("Dealer city"), "Raleigh");
  await user.selectOptions(within(form).getByLabelText("Dealer state"), "NC");
  await user.selectOptions(within(form).getByLabelText("How you are paying"), "FINANCE");
  await user.click(within(form).getByRole("button", { name: "Confirm and open case" }));
  await vi.waitFor(() => expect(opened).toHaveBeenCalledWith({ caseId: "case-1", token: "case-token" }, undefined));
  const creation = request.mock.calls.find(([path]) => path.endsWith("/case"));
  expect(creation?.[0]).toBe("/api/v1/journeys/j1/candidates/second/case");
  expect(JSON.parse(String(creation?.[1]?.body))).toMatchObject({ buyerPostalCode: "27601", dealerCity: "Raleigh", dealerState: "NC",
    purchaseMethod: "FINANCE", consents: { serviceProcessing: { granted: true, policyVersion: "p1" }, humanReview: { granted: false } } });
  opened.mockClear();
  await user.click(screen.getByRole("button", { name: "Open linked Buying Case" }));
  await vi.waitFor(() => expect(opened).toHaveBeenCalledWith({ caseId: "case-1", token: "case-token" }, undefined));
  expect(request.mock.calls.at(-1)?.[1]).toEqual({ method: "GET" });
  expect(screen.queryByLabelText("Dealer city")).toBeNull();
  await user.click(screen.getByRole("button", { name: "Remove 2024 Honda first from shortlist" }));
  expect(rows.map(row => row.candidateId)).toEqual(["second"]);
});

test("failed saving retains the stored shortlist and session expiration is reported", async () => {
  const ended = vi.fn();
  const request = vi.fn(async (_path: string, init?: RequestInit) => {
    if (init) throw { status: 401 };
    return [entry("first")];
  });
  render(<JourneyCandidates journeyId="j1" briefVersion={1} postalCode="27601" request={request as never} onSessionEnded={ended} />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Remove 2024 Honda first from shortlist" }));
  await vi.waitFor(() => expect(ended).toHaveBeenCalledOnce());
  expect(screen.getByRole("article", { name: "Candidate 1: 2024 Honda first" })).toBeInTheDocument();
});

test("search results can be added and removed by their stored candidate identifiers", async () => {
  const stored = [entry("first"), entry("second")];
  let rows: ShortlistEntry[] = [];
  const request = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith("/candidates")) return { matcherVersion: "v1", briefVersion: 1, referenceListVersion: "v1",
      availability: "AVAILABLE", notice: null, provider: { name: "Inventory", version: "v1", licence: "licensed", synthetic: false },
      retrievedAt: "2026-10-04T12:00:00Z", ordering: "Your brief determines the order.", excluded: [],
      candidates: stored.map(row => ({ ...row.candidate, marketCheck: row.marketCheck })) };
    if (init?.method === "PUT") rows = JSON.parse(String(init.body)).candidateIds.map((id: string) => stored.find(row => row.candidateId === id)!);
    return rows;
  });
  render(<JourneyCandidates journeyId="j1" briefVersion={1} postalCode="27601" request={request as never} onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  await screen.findByText("No shortlisted candidates yet. Search inventory and add a candidate.");
  await user.click(screen.getByRole("button", { name: "Search inventory for candidates" }));
  const results = screen.getByRole("region", { name: "Candidate Vehicles" });
  await user.click((await within(results).findAllByRole("button", { name: "Add to shortlist" }))[1]);
  expect(rows.map(row => row.candidateId)).toEqual(["second"]);
  expect(within(results).getAllByRole("button", { name: "Add to shortlist" })).toHaveLength(1);
  await user.click(within(results).getByRole("button", { name: "Remove from shortlist" }));
  expect(rows).toEqual([]);
});
