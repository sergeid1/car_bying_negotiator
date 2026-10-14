import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { VehicleSafety, VehicleSafetyFacts } from "./VehicleSafety";
import { vehicleSafetyFixture as fixture } from "./VehicleSafety.fixture";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test("shows each recall, the desk question, every variant, and the VIN ready to copy", async () => {
  const user = userEvent.setup();
  render(<VehicleSafetyFacts view={fixture} />);
  expect(screen.getByText(/Campaign 21V001000: AIR BAGS/)).toBeVisible();
  expect(screen.getByText(/Has campaign 21V001000 been repaired on this car/)).toBeVisible();
  expect(screen.getByText(/Remedy: Replace the part/)).toBeVisible();
  expect(screen.getByText("Accord FWD")).toBeVisible(); expect(screen.getByText("Accord AWD")).toBeVisible();
  expect(screen.getAllByText("Not rated").length).toBeGreaterThan(0);
  expect(screen.getByText(/15.0%/)).toBeVisible();
  expect(screen.getByRole("link", { name: /Check this car’s VIN/ })).toHaveAttribute("href", "https://www.nhtsa.gov/recalls");
  await user.click(screen.getByRole("button", { name: "Copy VIN" }));
  expect(await navigator.clipboard.readText()).toBe(fixture.vin);
});

test.each(["AVAILABLE", "UNAVAILABLE", "MISSING_VEHICLE"] as const)("%s has distinct wording", status => {
  render(<VehicleSafetyFacts view={{ ...fixture, facts: { ...fixture.facts, status, recalls: [], ratings: [] } }} />);
  const expected = status === "AVAILABLE" ? /returned zero model recall records/ : status === "UNAVAILABLE" ? /NHTSA did not answer/ : /Confirm the car’s year/;
  expect(screen.getByText(expected)).toBeVisible();
  if (status !== "AVAILABLE") expect(screen.queryByText(/returned zero/)).not.toBeInTheDocument();
});

test("loads only the case endpoint with credentials and expands to show the answer", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(fixture)));
  const user = userEvent.setup(); render(<VehicleSafety caseId="case-1" token="secret" />);
  await user.click(screen.getByText("Recalls and safety ratings"));
  expect(await screen.findByText("Accord FWD")).toBeVisible();
  expect(fetch).toHaveBeenCalledWith("/api/v1/cases/case-1/vehicle-safety", { headers: { "Case-Access-Token": "secret" } });
});

test("an HTTP failure is unavailable", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 503 }));
  const user = userEvent.setup(); render(<VehicleSafety caseId="case-1" token="secret" />);
  await user.click(screen.getByText("Recalls and safety ratings"));
  expect(await screen.findByText(/NHTSA did not answer/)).toBeVisible();
});

test("older reports have no new section; a new report shows only its saved facts", async () => {
  const report: CoreReport = { version: 1, asOf: "2026-10-14", status: "PARTIAL", analysis: {
    reviewLabel: "Review required", dealRating: "INSUFFICIENT_DATA", ratingBasis: "Unknown", nextAction: "Check the quote",
    topReasons: [], marketAvailability: "UNAVAILABLE", cashAvailability: "UNAVAILABLE", materialAmounts: [], policyVersions: {}, limitations: [],
  }, evidence: {}, reproduction: {} };
  const fetch = vi.spyOn(globalThis, "fetch");
  const { rerender } = render(<CoreDealReport report={report} />);
  expect(screen.queryByText("Recalls and safety ratings")).not.toBeInTheDocument();
  rerender(<CoreDealReport report={{ ...report, vehicleSafety: fixture }} />);
  await userEvent.click(screen.getByText("Recalls and safety ratings"));
  expect(screen.getByText("2026-10-14T12:00:00Z")).toBeVisible();
  expect(screen.getByText("Accord AWD")).toBeVisible();
  expect(fetch).not.toHaveBeenCalled();
});
