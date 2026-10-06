import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { Mandate, MandateProposal, NegotiationMandate } from "./NegotiationMandate";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const PROPOSAL: MandateProposal = {
  available: true, unavailableReason: null, planVersion: 2, currency: "USD", targetMinor: 2_300_000, ceilingMinor: 2_400_000,
  disclosures: ["PAYING_CASH", "HAS_OUTSIDE_FINANCING", "FIRST_NAME", "PURCHASE_TIMELINE"],
};
const ACTIVE: Mandate = {
  version: 1, status: "ACTIVE", currency: "USD", targetMinor: 2_250_000, ceilingMinor: 2_350_000, planCeilingMinor: 2_400_000,
  disclosures: ["PAYING_CASH"], expiresAt: "2026-10-17T12:00:00Z",
  approvedAt: "2026-10-03T12:00:00Z", revokedAt: null,
};

function stub(routes: Record<string, unknown>) {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    calls.push({ url, init });
    const key = `${init?.method ?? "GET"} ${url}`;
    const body = routes[key];
    if (body instanceof Response) return body;
    return new Response(JSON.stringify(body ?? []), { status: init?.method === "POST" ? 201 : 200 });
  });
  return calls;
}

test("without a market assessment the Buyer is told why and no approval form is offered", async () => {
  stub({ "GET /api/v1/cases/case/mandates/proposal": { ...PROPOSAL, available: false, planVersion: null, targetMinor: null, ceilingMinor: null,
    unavailableReason: "A Negotiation Mandate needs a market check for this car first." } });
  render(<NegotiationMandate caseId="case" token="token" />);
  expect(await screen.findByText(/needs a market check for this car first/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve mandate" })).not.toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "What the platform may not do" })).toBeInTheDocument();
  expect(screen.getByText(/Approving it sends nothing to any dealer/)).toBeInTheDocument();
});

test("approval sends the lowered limits, ticked disclosures and explicit approval, with no autonomy mode", async () => {
  const calls = stub({ "GET /api/v1/cases/case/mandates/proposal": PROPOSAL });
  render(<NegotiationMandate caseId="case" token="token" />);
  const user = userEvent.setup();
  const ceiling = await screen.findByLabelText("Walk-away ceiling ($)");
  expect(ceiling).toHaveValue(24000);
  expect(ceiling).toHaveAttribute("max", "24000");
  await user.clear(ceiling);
  await user.type(ceiling, "23500");
  await user.click(screen.getByLabelText("I am paying cash"));
  await user.click(screen.getByLabelText(/I approve this Negotiation Mandate/));
  await user.click(screen.getByRole("button", { name: "Approve mandate" }));
  const post = calls.find(call => call.init?.method === "POST")!;
  expect(post.url).toBe("/api/v1/cases/case/mandates");
  const body = JSON.parse(String(post.init!.body));
  expect(body).toMatchObject({ planVersion: 2, targetMinor: 2_300_000, ceilingMinor: 2_350_000,
    disclosures: ["PAYING_CASH"], approved: true });
  expect(body).not.toHaveProperty("autonomyMode");
  expect(new Date(body.expiresAt).getTime()).toBeGreaterThan(Date.now() + 13 * 86_400_000);
  expect((post.init!.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
});

test("the current mandate can be revoked at once and refusals are shown", async () => {
  const calls = stub({
    "GET /api/v1/cases/case/mandates/proposal": PROPOSAL,
    "GET /api/v1/cases/case/mandates": [ACTIVE],
    "POST /api/v1/cases/case/mandates/1/revocation": new Response(JSON.stringify({ detail: "Only the current mandate can be revoked" }), { status: 409 }),
  });
  render(<NegotiationMandate caseId="case" token="token" />);
  const current = await screen.findByRole("region", { name: "Current mandate, version 1" });
  expect(within(current).getByText("$23,500.00")).toBeInTheDocument();
  expect(within(current).getByText("I am paying cash")).toBeInTheDocument();
  await userEvent.setup().click(within(current).getByRole("button", { name: "Revoke mandate now" }));
  expect(calls.some(call => call.url === "/api/v1/cases/case/mandates/1/revocation" && call.init?.method === "POST")).toBe(true);
  expect(await screen.findByRole("alert")).toHaveTextContent("Only the current mandate can be revoked");
  expect(screen.getByRole("heading", { name: "Approve a new version" })).toBeInTheDocument();
});

test("a mandate whose price boundaries changed is shown as no longer applying, with a new approval offered", async () => {
  stub({
    "GET /api/v1/cases/case/mandates/proposal": PROPOSAL,
    "GET /api/v1/cases/case/mandates": [{ ...ACTIVE, status: "PLAN_CHANGED" }],
  });
  render(<NegotiationMandate caseId="case" token="token" />);
  expect(await screen.findByText(/price boundaries changed after mandate version 1 was approved/)).toBeInTheDocument();
  expect(screen.getByText(/No longer applies: price boundaries changed/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Revoke mandate now" })).not.toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Review and approve" })).toBeInTheDocument();
});
