import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { BriefOptions, BuyingJourneys, Journey, ShoppingBrief } from "./BuyingJourneys";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const BRIEF_OPTIONS: BriefOptions = {
  reference: {
    version: "vehicle-reference-2026-10-v1",
    makes: [
      { id: "toyota", label: "Toyota", models: [{ id: "toyota-rav4", label: "RAV4",
        trims: [{ id: "toyota-rav4-xle", label: "XLE" }, { id: "toyota-rav4-limited", label: "Limited" }] }] },
      { id: "honda", label: "Honda", models: [{ id: "honda-cr-v", label: "CR-V", trims: [{ id: "honda-cr-v-ex", label: "EX" }] }] },
    ],
    features: [{ id: "all-wheel-drive", label: "All-wheel drive" }, { id: "heated-seats", label: "Heated seats" }],
  },
  bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
    minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20 },
};

const briefVersion = (version: number, priceCeilingMinor: number): ShoppingBrief => ({
  journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2020, yearTo: 2024,
  make: { id: "toyota", label: "Toyota" }, model: { id: "toyota-rav4", label: "RAV4" },
  trims: [{ id: "toyota-rav4-xle", label: "XLE" }], maxMileage: 60000, priceCeilingMinor, currency: "USD",
  postalCode: "27601", jurisdiction: "US-NC", radiusMiles: 50, mustHaveFeatures: [{ id: "all-wheel-drive", label: "All-wheel drive" }],
  niceToHaveFeatures: [], createdAt: "2026-10-03T12:00:00Z",
});

const journeyWith = (...versions: ShoppingBrief[]): Journey => ({
  id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "2026-10-03T12:00:00Z",
  brief: versions[versions.length - 1], versions: versions.map(v => ({ version: v.version, createdAt: v.createdAt })),
});

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
type Call = { path: string; init?: RequestInit };

function server(handler: (path: string, init?: RequestInit) => Response | undefined) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    calls.push({ path, init });
    const handled = handler(path, init);
    if (handled) return handled;
    if (path === "/api/v1/journeys/brief-options") return json(BRIEF_OPTIONS);
    return json([]);
  }));
  return calls;
}

async function fillBrief(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText("Make"), "toyota");
  await user.selectOptions(screen.getByLabelText("Model"), "toyota-rav4");
  await user.click(screen.getByLabelText("Limited"));
  await user.type(screen.getByLabelText("Earliest model year"), "2020");
  await user.type(screen.getByLabelText("Latest model year"), "2024");
  await user.type(screen.getByLabelText("Maximum mileage (miles)"), "60000");
  await user.type(screen.getByLabelText("Price ceiling (whole dollars)"), "32000");
  await user.type(screen.getByLabelText("Your ZIP code"), "27601");
  await user.type(screen.getByLabelText(/Search radius/), "50");
  const must = screen.getByRole("group", { name: "Must-have features" });
  await user.click(within(must).getByLabelText("All-wheel drive"));
}

test("starts a journey from reference-list choices; lookup text only helps and is never sent", async () => {
  const created = journeyWith(briefVersion(1, 3_200_000));
  const calls = server((path, init) => init?.method === "POST" && path === "/api/v1/journeys" ? json(created, 201) : undefined);
  render(<BuyingJourneys session="session-1" onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  expect(await screen.findByText("No Buying Journeys yet.")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Start a Buying Journey" }));

  await user.type(screen.getByLabelText("Find a make or model (optional)"), "rav");
  await user.click(screen.getByRole("button", { name: "Choose Toyota RAV4" }));
  expect(screen.getByLabelText("Make")).toHaveValue("toyota");
  expect(screen.getByLabelText("Model")).toHaveValue("toyota-rav4");
  await user.type(screen.getByLabelText("Find a make or model (optional)"), "free text model");
  expect(screen.getByText(/No make or model in the list matches/)).toBeVisible();
  await user.clear(screen.getByLabelText("Find a make or model (optional)"));

  await fillBrief(user);
  // Choosing a feature as nice-to-have moves it out of must-have.
  const nice = screen.getByRole("group", { name: "Nice-to-have features" });
  await user.click(within(nice).getByLabelText("Heated seats"));
  await user.click(screen.getByRole("button", { name: "Save brief and start journey" }));

  expect(await screen.findByRole("status")).toHaveTextContent("brief version 1");
  const post = calls.find(c => c.init?.method === "POST")!;
  expect((post.init?.headers as Record<string, string>)["Account-Session-Token"]).toBe("session-1");
  expect((post.init?.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
  const body = JSON.parse(String(post.init?.body));
  expect(body).toEqual({ yearFrom: 2020, yearTo: 2024, makeId: "toyota", modelId: "toyota-rav4", trimIds: ["toyota-rav4-limited"],
    maxMileage: 60000, priceCeiling: 32000, postalCode: "27601", jurisdiction: null, radiusMiles: 50,
    mustHaveFeatures: ["all-wheel-drive"], niceToHaveFeatures: ["heated-seats"] });
  expect(JSON.stringify(body)).not.toContain("rav\"");
  expect(screen.getByRole("heading", { name: "2020–2024 Toyota RAV4" })).toBeVisible();
});

test("names each field with its correction and asks for the state when a ZIP crosses a state line", async () => {
  let attempt = 0;
  const calls = server((path, init) => {
    if (init?.method !== "POST") return undefined;
    attempt++;
    if (attempt === 1) return json({ status: 400, code: "BRIEF_INVALID", detail: "Some Shopping Brief fields need a correction. This brief was not saved.",
      errors: [{ field: "yearFrom", message: "Enter the earliest model year as a year from 1990 to 2027." },
        { field: "makeId", message: "Choose a make from the list." },
        { field: "mustHaveFeatures", message: "Choose must-have features from the list." }] }, 400);
    return json({ status: 422, code: "LOCATION_NOT_SERVED", detail: "That ZIP code crosses a state line.",
      errors: [{ field: "jurisdiction", message: "That ZIP code crosses a state line, so the state cannot be determined from it. Choose your state.",
        reason: "POSTAL_CODE_CROSSES_STATE_LINE", candidateJurisdictions: ["US-NC", "US-VA"] }] }, 422);
  });
  render(<BuyingJourneys session="session-1" onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Start a Buying Journey" }));
  await user.click(screen.getByRole("button", { name: "Save brief and start journey" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("need a correction");
  expect(screen.getByLabelText("Earliest model year")).toHaveAttribute("aria-invalid", "true");
  expect(screen.getByLabelText("Earliest model year")).toHaveAccessibleDescription("Enter the earliest model year as a year from 1990 to 2027.");
  expect(screen.getByLabelText("Make")).toHaveAccessibleDescription("Choose a make from the list.");
  expect(screen.getByRole("group", { name: "Must-have features" })).toHaveAccessibleDescription("Choose must-have features from the list.");
  expect(screen.getByLabelText("Latest model year")).not.toHaveAttribute("aria-invalid");

  await user.click(screen.getByRole("button", { name: "Save brief and start journey" }));
  const state = await screen.findByLabelText("Your state");
  expect(state).toHaveAccessibleDescription(/crosses a state line/);
  expect(within(state).getAllByRole("option").map(o => o.textContent)).toEqual(["Choose your state", "North Carolina", "Virginia"]);
  await user.selectOptions(state, "US-NC");
  await user.click(screen.getByRole("button", { name: "Save brief and start journey" }));
  await waitFor(() => expect(calls.filter(c => c.init?.method === "POST")).toHaveLength(3));
  expect(JSON.parse(String(calls.filter(c => c.init?.method === "POST")[2].init?.body)).jurisdiction).toBe("US-NC");
});

test("lists, opens, edits into a new version, reads an earlier version, and deletes a journey", async () => {
  const v1 = briefVersion(1, 3_200_000), v2 = briefVersion(2, 3_000_000);
  let journeys = [{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", currentVersion: 1,
    yearFrom: 2020, yearTo: 2024, make: "Toyota", model: "RAV4" }];
  let current = journeyWith(v1);
  const calls = server((path, init) => {
    if (path === "/api/v1/journeys" && !init?.method) return json(journeys);
    if (path === "/api/v1/journeys/j1" && init?.method === "DELETE") { journeys = []; return json({ journeyId: "j1", state: "DELETED" }); }
    if (path === "/api/v1/journeys/j1") return json(current);
    if (path === "/api/v1/journeys/j1/briefs" && init?.method === "POST") {
      current = journeyWith(v1, v2); journeys = [{ ...journeys[0], currentVersion: 2 }]; return json(current);
    }
    if (path === "/api/v1/journeys/j1/briefs/1") return json(v1);
    return undefined;
  });
  render(<BuyingJourneys session="session-1" onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  const list = await screen.findByRole("list", { name: "Your Buying Journeys" });
  expect(list).toHaveTextContent("Shopping · Brief version 1");
  await user.click(within(list).getByRole("button", { name: "Open 2020–2024 Toyota RAV4" }));
  expect(await screen.findByRole("heading", { name: "2020–2024 Toyota RAV4" })).toBeVisible();
  expect(screen.getByText("$32,000")).toBeVisible();

  await user.click(screen.getByRole("button", { name: "Edit brief" }));
  const price = screen.getByLabelText("Price ceiling (whole dollars)");
  expect(price).toHaveValue("32000");
  await user.clear(price); await user.type(price, "30000");
  await user.click(screen.getByRole("button", { name: "Save as a new brief version" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Saved as brief version 2. Earlier versions remain readable.");
  const revision = JSON.parse(String(calls.find(c => c.path.endsWith("/briefs"))!.init?.body));
  expect(revision.basedOnVersion).toBe(1);
  expect(revision.brief.priceCeiling).toBe(30000);
  expect(screen.getByText("$30,000")).toBeVisible();

  await user.selectOptions(screen.getByLabelText("Brief version"), "1");
  expect(await screen.findByText("$32,000")).toBeVisible();
  expect(screen.getByText(/reading an earlier version/)).toBeVisible();

  await user.click(screen.getByLabelText(/permanently delete this Buying Journey/));
  await user.click(screen.getByRole("button", { name: "Delete Buying Journey" }));
  expect(await screen.findByRole("status")).toHaveTextContent("deleted with every version");
  expect(screen.getByText("No Buying Journeys yet.")).toBeVisible();
});

test("a stale edit reloads the latest version instead of replacing it", async () => {
  const v1 = briefVersion(1, 3_200_000), v2 = briefVersion(2, 2_800_000);
  let opened = journeyWith(v1);
  server((path, init) => {
    if (path === "/api/v1/journeys" && !init?.method) return json([{ id: "j1", state: "SHOPPING", createdAt: "", updatedAt: "",
      currentVersion: 1, yearFrom: 2020, yearTo: 2024, make: "Toyota", model: "RAV4" }]);
    if (path === "/api/v1/journeys/j1") { const value = opened; opened = journeyWith(v1, v2); return json(value); }
    if (path.endsWith("/briefs") && init?.method === "POST") return json({ status: 409, code: "BRIEF_CHANGED",
      detail: "This Shopping Brief changed since you opened it. Reload it, review the latest version and save your edit again." }, 409);
    return undefined;
  });
  render(<BuyingJourneys session="session-1" onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Toyota RAV4" }));
  await user.click(await screen.findByRole("button", { name: "Edit brief" }));
  await user.click(screen.getByRole("button", { name: "Save as a new brief version" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("changed since you opened it");
  expect(screen.getByText("$28,000")).toBeVisible();
});

test("an ended session hands control back to the account panel", async () => {
  server(() => json({ status: 401, code: "SESSION_ENDED" }, 401));
  const ended = vi.fn();
  render(<BuyingJourneys session="session-1" onSessionEnded={ended} />);
  await waitFor(() => expect(ended).toHaveBeenCalled());
});
