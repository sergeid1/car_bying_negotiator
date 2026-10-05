import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { TestDriveAppointment, type TestDriveView } from "./TestDriveAppointment";
import { AccountSignedInContext } from "./BuyerAccount";
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const pass = { pass: true, gateVersion: "outbound-gate.v2", reasons: [] };
const slots = [
  { option: 1, startsAt: "2026-10-10T15:00:00Z", endsAt: "2026-10-10T16:00:00Z", label: "Saturday, October 10, 2026, 10:00 to 11:00 (America/Chicago)" },
  { option: 2, startsAt: "2026-10-10T16:00:00Z", endsAt: "2026-10-10T17:00:00Z", label: "Saturday, October 10, 2026, 11:00 to 12:00 (America/Chicago)" },
];
const proposal: TestDriveView["messages"][number] = { id: "p1", kind: "PROPOSAL", appointmentId: null, slots, release: [], state: "SENT", createdAt: "2026-10-04T12:00:00Z",
  sentAt: "2026-10-04T12:01:00Z", gatedDraft: { draft: { text: "The Buyer would like to arrange a test drive." }, decision: pass } };
const agreed: TestDriveView = { agreedPrice: { id: "ap", offerVersion: 3, baselineVersion: 2, outTheDoorMinor: 2680000 },
  dealerAddress: "sales@dealer.example", contactFields: ["FIRST_NAME", "LAST_NAME", "EMAIL", "PHONE"], messages: [], appointments: [], confirmations: [] };
const respond = (view: TestDriveView) => vi.fn(async () => new Response(JSON.stringify(view)));

test("stays hidden until an Agreed Price exists", async () => {
  const fetcher = respond({ ...agreed, agreedPrice: null, contactFields: [] });
  vi.stubGlobal("fetch", fetcher);
  const { container } = render(<TestDriveAppointment caseId="c1" token="secret" />);
  await waitFor(() => expect(fetcher).toHaveBeenCalled());
  await waitFor(() => expect(container).toBeEmptyDOMElement());
});

test("proposes availability windows with the case credential only", async () => {
  const fetcher = respond(agreed);
  vi.stubGlobal("fetch", fetcher);
  render(<TestDriveAppointment caseId="c1" token="secret" />);
  expect(await screen.findByText(/out-the-door total \$26,800.00/)).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Time zone"), { target: { value: "America/Chicago" } });
  fireEvent.change(screen.getByLabelText("Starts"), { target: { value: "2026-10-10T10:00" } });
  fireEvent.change(screen.getByLabelText("Ends"), { target: { value: "2026-10-10T12:00" } });
  fireEvent.click(screen.getByRole("button", { name: "Prepare proposed times" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/proposals", expect.objectContaining({
    method: "POST", body: JSON.stringify({ appointmentId: null, timeZone: "America/Chicago", windows: [{ start: "2026-10-10T10:00", end: "2026-10-10T12:00" }] }),
  })));
  const call = (fetcher.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith("/proposals"))!;
  const headers = call[1].headers as Record<string, string>;
  expect(headers["Case-Access-Token"]).toBe("secret");
  expect(headers["Account-Request"]).toBe("1");
  expect(headers["Account-Session-Token"]).toBeUndefined();
});

test("a dealer reply is shown for confirmation and shares nothing unless the Buyer ticks a field", async () => {
  const fetcher = respond({ ...agreed, messages: [proposal],
    confirmations: [{ proposalId: "p1", messageId: "m9", receivedAt: "2026-10-04T13:00:00Z", excerpt: "Option 2 works for us.", detectedOption: 2 }] });
  vi.stubGlobal("fetch", fetcher);
  render(<TestDriveAppointment caseId="c1" token="secret" />);
  expect(await screen.findByText("Option 2 works for us.")).toBeInTheDocument();
  expect(screen.getByText(/appears to choose option 2/)).toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: "Time the dealer confirmed" })).toHaveValue("2");
  for (const name of ["First name", "Surname", "Email", "Phone number"]) expect(screen.getByRole("checkbox", { name })).not.toBeChecked();
  fireEvent.click(screen.getByRole("checkbox", { name: "Email" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm this time and review the booking message" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/bookings", expect.objectContaining({
    method: "POST", body: JSON.stringify({ proposalId: "p1", messageId: "m9", option: 2, release: ["EMAIL"] }),
    headers: expect.objectContaining({ "Account-Request": "1", "Case-Access-Token": "secret" }),
  })));
});

test("approving a booking sends the exact text with the account session and names the released fields", async () => {
  const text = "Thank you for confirming. The Buyer will attend the test drive on Saturday.";
  const fetcher = respond({ ...agreed, messages: [proposal, { ...proposal, id: "b1", kind: "BOOKING", appointmentId: "a1", slots: [slots[1]],
    release: ["FIRST_NAME", "EMAIL"], state: "DRAFT", sentAt: null, gatedDraft: { draft: { text }, decision: pass } }] });
  vi.stubGlobal("fetch", fetcher);
  render(<TestDriveAppointment caseId="c1" token="secret" />);
  expect(await screen.findByText(/only these details are added below the message for this dealer: First name, Email/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Approve this text, share the chosen details and book" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/messages/b1/approval", expect.objectContaining({
    method: "POST", body: JSON.stringify({ approved: true, text }), headers: expect.objectContaining({ "Account-Request": "1" }),
  })));
});

test("a blocked draft cannot be approved", async () => {
  vi.stubGlobal("fetch", respond({ ...agreed, messages: [{ ...proposal, state: "DRAFT",
    gatedDraft: { ...proposal.gatedDraft, decision: { pass: false, gateVersion: "v2", reasons: [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] } } }] }));
  render(<TestDriveAppointment caseId="c1" token="secret" />);
  expect(await screen.findByRole("button", { name: "Approve this text and send" })).toBeDisabled();
});

test("account state updates after sign-in and proposal approval carries the request marker", async () => {
  const fetcher = respond({ ...agreed, messages: [proposal, { ...proposal, id: "p2", state: "DRAFT", sentAt: null }],
    confirmations: [{ proposalId: "p1", messageId: "m9", receivedAt: "2026-10-04T13:00:00Z", excerpt: "Option 2 works.", detectedOption: 2 }] });
  vi.stubGlobal("fetch", fetcher);
  const panel = (signedIn: boolean) => <AccountSignedInContext.Provider value={signedIn}>
    <TestDriveAppointment caseId="c1" token="secret" />
  </AccountSignedInContext.Provider>;
  const { rerender } = render(panel(false));
  expect(await screen.findByText("Sign in to your Buyer Account in this browser to book and choose contact details.")).toBeVisible();
  rerender(panel(true));
  expect(screen.queryByText("Sign in to your Buyer Account in this browser to book and choose contact details.")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Approve this text and send" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/messages/p2/approval",
    expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "Account-Request": "1", "Case-Access-Token": "secret" }) })));
  expect((fetcher.mock.calls as unknown as [string][]).some(([url]) => url === "/api/v1/account")).toBe(false);
});

test("a booked test drive offers its calendar file, moving and a relayed cancellation", async () => {
  const create = vi.fn(() => "blob:ics"), revoke = vi.fn();
  vi.stubGlobal("URL", { createObjectURL: create, revokeObjectURL: revoke });
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  const view = { ...agreed, messages: [proposal], appointments: [{ id: "a1", option: 2, startsAt: slots[1].startsAt, endsAt: slots[1].endsAt,
    label: slots[1].label, state: "SCHEDULED", replacesAppointmentId: null,
    release: { fields: ["EMAIL"], state: "RELEASED", decidedAt: "2026-10-04T13:00:00Z", releasedAt: "2026-10-04T13:01:00Z" } }] } as TestDriveView;
  const fetcher = vi.fn(async (url: string) => new Response(url.endsWith("/calendar") ? "BEGIN:VCALENDAR" : JSON.stringify(view)));
  vi.stubGlobal("fetch", fetcher);
  render(<TestDriveAppointment caseId="c1" token="secret" />);
  expect(await screen.findByText("Contact details shared with this dealer: Email.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Prepare proposed times" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: `Download calendar file for ${slots[1].label}` }));
  await waitFor(() => expect(click).toHaveBeenCalled());
  expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/appointments/a1/calendar", { headers: { "Case-Access-Token": "secret" } });
  fireEvent.click(screen.getByRole("button", { name: "Move this test drive" }));
  expect(screen.getByRole("form", { name: "New availability" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Prepare a cancellation message" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/appointment/appointments/a1/cancellation",
    expect.objectContaining({ method: "POST" })));
});
