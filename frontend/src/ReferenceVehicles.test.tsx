import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { BriefOptions, BuyingJourneys, Journey, ReferenceVehicle, ShoppingBrief } from "./BuyingJourneys";

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

const OPTIONS: BriefOptions = {
  reference: { version: "vehicle-reference-2026-10-v1",
    makes: [{ id: "toyota", label: "Toyota", models: [{ id: "toyota-rav4", label: "RAV4", trims: [{ id: "toyota-rav4-xle", label: "XLE" }] }] }],
    features: [{ id: "all-wheel-drive", label: "All-wheel drive" }] },
  bounds: { earliestYear: 1990, latestYear: 2027, maxMileage: 300000, minPriceCeiling: 1000, maxPriceCeiling: 500000,
    minRadiusMiles: 5, maxRadiusMiles: 500, maxChoices: 20, maxReferenceVehicles: 5 },
};

const brief = (version: number): ShoppingBrief => ({
  journeyId: "j1", version, referenceListVersion: "vehicle-reference-2026-10-v1", yearFrom: 2020, yearTo: 2024,
  make: { id: "toyota", label: "Toyota" }, model: { id: "toyota-rav4", label: "RAV4" }, trims: [], maxMileage: 60000,
  priceCeilingMinor: 3_200_000, currency: "USD", postalCode: "27601", jurisdiction: "US-NC", radiusMiles: 50,
  mustHaveFeatures: [], niceToHaveFeatures: [], createdAt: "2026-10-03T12:00:00Z",
});

const SOURCE = { source: "NHTSA vPIC", decoderVersion: "nhtsa-vpic-decodevinvalues-adapter-v1" };
const attribute = (name: string, label: string, value: string, relevant = false) => ({ attribute: name, label, value, relevant, ...SOURCE });
const RAV4: ReferenceVehicle = { referenceId: "r1", vin: "2T3P1RFV5MW000001", outcome: "DECODED", ...SOURCE,
  decodedAt: "2026-10-04T12:00:00Z", attributes: [attribute("MODEL_YEAR", "Model year", "2021"), attribute("MAKE", "Make", "Toyota"),
    attribute("MODEL", "Model", "RAV4"), attribute("TRIM", "Trim", "XLE"), attribute("DRIVETRAIN", "Drivetrain", "All-wheel drive")] };
const UNKNOWN: ReferenceVehicle = { referenceId: "r2", vin: "3VW000003AA000006", outcome: "UNDECODABLE", ...SOURCE,
  decodedAt: "2026-10-04T12:00:00Z", attributes: [] };

const journey = (version: number, referenceVehicles: ReferenceVehicle[]): Journey => ({
  id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "", brief: brief(version),
  versions: Array.from({ length: version }, (_, i) => ({ version: i + 1, createdAt: "2026-10-03T12:00:00Z" })), referenceVehicles,
});

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
type Call = { path: string; init?: RequestInit };

function server(opened: Journey, handler: (path: string, init?: RequestInit) => Response | undefined) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    calls.push({ path, init });
    const handled = handler(path, init);
    if (handled) return handled;
    if (path === "/api/v1/journeys/brief-options") return json(OPTIONS);
    if (path === "/api/v1/journeys") return json([{ id: "j1", state: "SHOPPING", createdAt: "2026-10-03T12:00:00Z", updatedAt: "",
      currentVersion: opened.brief.version, yearFrom: 2020, yearTo: 2024, make: "Toyota", model: "RAV4" }]);
    if (path === "/api/v1/journeys/j1") return json(opened);
    return json([]);
  }));
  return calls;
}

async function open(user: ReturnType<typeof userEvent.setup>) {
  render(<BuyingJourneys onSessionEnded={() => {}} />);
  await user.click(await screen.findByRole("button", { name: "Open 2020–2024 Toyota RAV4" }));
  return screen.findByRole("region", { name: "Reference Vehicles" });
}

test("a VIN failing the checksum shows the server's checksum message on the field and saves nothing", async () => {
  const calls = server(journey(1, []), (path, init) => path.endsWith("/reference-vehicles") && init?.method === "POST"
    ? json({ code: "VIN_INVALID", detail: "That VIN was not decoded because it is not a valid VIN.",
      errors: [{ field: "vin", message: "This VIN fails the VIN checksum. Enter all 17 characters exactly." }] }, 400) : undefined);
  const user = userEvent.setup();
  const section = await open(user);
  expect(within(section).getByText("No Reference Vehicles in this version.")).toBeInTheDocument();
  await user.type(within(section).getByLabelText("VIN of a car you like"), "2T3P1RFV0MW000001");
  await user.click(within(section).getByRole("button", { name: "Decode and add VIN" }));
  const input = within(section).getByLabelText("VIN of a car you like");
  await waitFor(() => expect(input).toHaveAttribute("aria-invalid", "true"));
  expect(within(section).getByText(/fails the VIN checksum/)).toBeInTheDocument();
  expect(input).toHaveAccessibleDescription(/fails the VIN checksum/);
  const posted = calls.find(c => c.init?.method === "POST")!;
  expect(JSON.parse(String(posted.init!.body))).toEqual({ basedOnVersion: 1, vin: "2T3P1RFV0MW000001" });
});

test("an added VIN shows decoded details with source and decoder version, and relevance saves a new version", async () => {
  let current = journey(1, []);
  const calls = server(current, (path, init) => {
    if (path === "/api/v1/journeys/j1/reference-vehicles" && init?.method === "POST") return json(current = journey(2, [RAV4]));
    if (path.endsWith("/reference-vehicles/r1/relevance")) return json(current = journey(3, [{ ...RAV4,
      attributes: RAV4.attributes.map(a => ({ ...a, relevant: a.attribute === "TRIM" })) }]));
    return undefined;
  });
  const user = userEvent.setup();
  const section = await open(user);
  await user.type(within(section).getByLabelText("VIN of a car you like"), "2T3P1RFV5MW000001");
  await user.click(within(section).getByRole("button", { name: "Decode and add VIN" }));
  expect(await within(section).findByText("Added as brief version 2. Mark which decoded details matter to you.")).toBeInTheDocument();
  const card = within(section).getByRole("article", { name: "Reference Vehicle 1: 2021 Toyota RAV4 XLE" });
  expect(within(card).getByText("2T3P1RFV5MW000001")).toBeInTheDocument();
  expect(within(card).getAllByText("(decoded by NHTSA vPIC, decoder version nhtsa-vpic-decodevinvalues-adapter-v1)")).toHaveLength(5);
  const save = within(card).getByRole("button", { name: "Save details that matter for Reference Vehicle 1" });
  expect(save).toBeDisabled();
  await user.click(within(card).getByRole("checkbox", { name: /Trim: XLE/ }));
  await user.click(save);
  expect(await within(section).findByText("Saved which details matter as brief version 3.")).toBeInTheDocument();
  const put = calls.find(c => c.init?.method === "PUT")!;
  expect(JSON.parse(String(put.init!.body))).toEqual({ basedOnVersion: 2, relevantAttributes: ["TRIM"] });
  expect(within(section).getByRole("checkbox", { name: /Trim: XLE/ })).toBeChecked();
  expect(screen.getByLabelText("Brief version")).toHaveValue("3");
});

test("an undecodable VIN is shown as unavailable, an unavailable decoder is stated, and removal saves a new version", async () => {
  let current = journey(2, [RAV4, UNKNOWN]);
  const calls = server(current, (path, init) => {
    if (path === "/api/v1/journeys/j1/reference-vehicles" && init?.method === "POST")
      return json({ code: "VIN_DECODER_UNAVAILABLE", detail: "The VIN decoder is not answering right now. Nothing was saved and nothing was guessed.",
        errors: [] }, 503);
    if (path.startsWith("/api/v1/journeys/j1/reference-vehicles/r2") && init?.method === "DELETE") return json(current = journey(3, [RAV4]));
    return undefined;
  });
  const user = userEvent.setup();
  const section = await open(user);
  const unknown = within(section).getByRole("article", { name: "Reference Vehicle 2" });
  expect(within(unknown).getByText(/Decoded details unavailable/)).toBeInTheDocument();
  expect(within(unknown).queryByRole("checkbox")).not.toBeInTheDocument();

  await user.type(within(section).getByLabelText("VIN of a car you like"), "1N4BL4BV0LC000007");
  await user.click(within(section).getByRole("button", { name: "Decode and add VIN" }));
  expect(await within(section).findByRole("alert")).toHaveTextContent("Nothing was saved and nothing was guessed.");

  await user.click(within(unknown).getByRole("button", { name: "Remove Reference Vehicle 2" }));
  expect(await within(section).findByText("Removed as brief version 3. Earlier versions still list it.")).toBeInTheDocument();
  expect(calls.some(c => c.path === "/api/v1/journeys/j1/reference-vehicles/r2?basedOnVersion=2" && c.init?.method === "DELETE")).toBe(true);
  expect(within(section).queryByText("3VW000003AA000006")).not.toBeInTheDocument();
});

test("a full brief offers no add form, and an earlier version lists its vehicles read only", async () => {
  const five = ["r1", "r2", "r3", "r4", "r5"].map(id => ({ ...RAV4, referenceId: id, vin: `${id}VIN` }));
  server(journey(6, five), path => path === "/api/v1/journeys/j1/briefs/2" ? json(brief(2))
    : path === "/api/v1/journeys/j1/briefs/2/reference-vehicles" ? json([{ ...RAV4,
      attributes: RAV4.attributes.map(a => ({ ...a, relevant: a.attribute === "DRIVETRAIN" })) }]) : undefined);
  const user = userEvent.setup();
  const section = await open(user);
  expect(within(section).queryByLabelText("VIN of a car you like")).not.toBeInTheDocument();
  expect(within(section).getByText(/holds the most Reference Vehicles it can \(5\)/)).toBeInTheDocument();

  await user.selectOptions(screen.getByLabelText("Brief version"), "2");
  const details = await within(section).findByLabelText("Decoded details of Reference Vehicle 1");
  expect(details).toHaveTextContent("All-wheel drive · Matters");
  expect(details).toHaveTextContent("XLE · Does not matter");
  expect(within(section).queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
  expect(within(section).queryByRole("checkbox")).not.toBeInTheDocument();
});
