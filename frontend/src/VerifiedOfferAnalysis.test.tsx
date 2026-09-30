import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
const report = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: [], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [], policyVersions: {}, limitations: [],
}, evidence: {}, reproduction: {} };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
test("frozen offer advances through the endpoints and opens the focused report", async () => {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (path: string) => {
    calls.push(path);
    const payload = path.endsWith("negotiation") ? { language: { draft: "Review the fees", items: [] } } : path.endsWith("dealer-offers/latest") ? { version: 2, status: "VERIFIED" }
      : path.endsWith("analyses") ? { operation: { id: "op", stage: "QUEUED" } }
      : path.includes("operations/") ? { stage: "COMPLETE" }
      : path.endsWith("reports/latest") ? report : {};
    return new Response(JSON.stringify(payload));
  }));
  render(<VerifiedOfferAnalysis caseId="current" token="secret" offerVersion={2} pollDelayMs={0} />);
  expect(await screen.findByRole("heading", { name: "Deal Analysis Report" })).toHaveFocus();
  expect(calls).toEqual(["/api/v1/cases/current/dealer-offers/latest", "/api/v1/cases/current/market-assessments", "/api/v1/cases/current", "/api/v1/cases/current/cash-economics", "/api/v1/cases/current/analyses", "/api/v1/operations/op", "/api/v1/cases/current/reports/latest", "/api/v1/cases/current/reports/1/negotiation"]);
});
test("failure retries the saved offer without uploads and preserves analysis request identity", async () => {
  let fail = true;
  const fetcher = vi.fn(async (path: string, options?: RequestInit) => {
    if (path.endsWith("reports/latest") && fail) { fail = false; throw new Error("offline"); }
    return new Response(JSON.stringify(path.endsWith("negotiation") ? { language: { draft: "Review the fees", items: [] } } : path.endsWith("dealer-offers/latest") ? { version: 1, status: "VERIFIED" }
      : path.endsWith("analyses") ? { operation: { stage: "COMPLETE" } } : path.endsWith("reports/latest") ? report : {}));
  });
  vi.stubGlobal("fetch", fetcher);
  render(<VerifiedOfferAnalysis caseId="case" token="token" pollDelayMs={0} />);
  await screen.findByRole("alert"); fireEvent.click(screen.getByRole("button", { name: "Retry analysis" }));
  await screen.findByRole("heading", { name: "Deal Analysis Report" });
  const requests = fetcher.mock.calls.filter(([path]) => path.endsWith("analyses"));
  expect(requests).toHaveLength(2);
  expect(requests[0][1]?.headers).toEqual(requests[1][1]?.headers);
  expect(fetcher.mock.calls.some(([path]) => path.includes("documents"))).toBe(false);
});
test("does not analyze a different offer version", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ version: 3, status: "VERIFIED" })));
  vi.stubGlobal("fetch", fetcher);
  render(<VerifiedOfferAnalysis caseId="case" token="token" offerVersion={2} />);
  await screen.findByRole("alert"); expect(fetcher).toHaveBeenCalledTimes(1);
});

test("dealer financing continues to a report without calling cash-only economics", async () => {
  const fetcher = vi.fn(async (path: string) => new Response(JSON.stringify(
    path.endsWith("dealer-offers/latest") ? { version: 1, status: "VERIFIED" }
      : path === "/api/v1/cases/finance" ? { purchaseMethod: "FINANCE" }
      : path.endsWith("analyses") ? { operation: { stage: "COMPLETE" } }
      : path.endsWith("reports/latest") ? report
      : path.endsWith("negotiation") ? { language: { draft: "Review fees", items: [] } } : {})));
  vi.stubGlobal("fetch", fetcher);
  render(<VerifiedOfferAnalysis caseId="finance" token="token" />);
  await screen.findByRole("heading", { name: "Deal Analysis Report" });
  expect(fetcher.mock.calls.some(([path]) => path.endsWith("cash-economics"))).toBe(false);
});
