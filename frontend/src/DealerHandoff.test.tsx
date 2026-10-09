import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerHandoff, DealerReplyPaste, MAILTO_LIMIT, mailtoLink } from "./DealerHandoff";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const body = "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352 (listing: https://dealer.example/v?a=1&b=2), including the selling price, taxes, all fees and all add-ons?\n\nIs there flexibility in the written selling price?";
const handoff = (state = "HANDED_OFF", text = body) => ({ messageId: "m1", sendPath: "OWN_EMAIL_APP", state, dealerAddress: "sales@dealer.example",
  subject: "Request for an itemized vehicle quote", body: text, handedOffAt: "2026-10-05T12:00:00Z",
  sentAt: state === "SENT" ? "2026-10-05T12:05:00Z" : null });
function mock(...responses: unknown[]) {
  const queue = [...responses];
  const fetcher = vi.fn(async (_url: string, _init?: RequestInit) => {
    const next = queue.length > 1 ? queue.shift() : queue[0];
    return next instanceof Response ? next : new Response(JSON.stringify(next));
  });
  vi.stubGlobal("fetch", fetcher); return fetcher;
}

test("the mailto link percent-encodes the subject and body with CRLF line breaks and keeps the address readable", () => {
  const link = mailtoLink("sales@dealer.example", "Quote & terms?", "Line one\nLine two & more");
  expect(link).toBe("mailto:sales@dealer.example?subject=Quote%20%26%20terms%3F&body=Line%20one%0D%0ALine%20two%20%26%20more");
  const decoded = new URL(link);
  expect(decodeURIComponent(decoded.search.split("&body=")[1])).toBe("Line one\r\nLine two & more");
});

test("shows the exact approved text as not sent yet, opens it in the email app, and copies subject and body with an announcement", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn(async () => undefined);
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
  mock(handoff());
  const { container } = render(<main><h1>Case</h1><DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} /></main>);
  expect(await screen.findByText(/Not sent yet/)).toBeInTheDocument();
  expect(screen.getByText(body, { normalizer: text => text })).toBeInTheDocument();
  expect(screen.queryByText(/on behalf|otdog sent|opt out/i)).toBeNull();
  const open = screen.getByRole("link", { name: "Open in my email app" });
  expect(open).toHaveAttribute("href", mailtoLink("sales@dealer.example", "Request for an itemized vehicle quote", body));
  await user.click(screen.getByRole("button", { name: "Copy subject" }));
  expect(writeText).toHaveBeenLastCalledWith("Request for an itemized vehicle quote");
  expect(await screen.findByText("Subject copied.")).toHaveAttribute("role", "status");
  await user.click(screen.getByRole("button", { name: "Copy message" }));
  expect(writeText).toHaveBeenLastCalledWith(body);
  expect(await screen.findByText("Message copied.")).toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
});

test("a message too long for a mailto link offers Copy with a plain explanation instead", async () => {
  const long = "Could you confirm the full payment schedule and full-term cost in writing?\n\n".repeat(40).trim();
  expect(mailtoLink("sales@dealer.example", "Subject", long).length).toBeGreaterThan(MAILTO_LIMIT);
  mock(handoff("HANDED_OFF", long));
  render(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} />);
  expect(await screen.findByText(/too long to open in an email app reliably/)).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Open in my email app" })).toBeNull();
  expect(screen.getByRole("button", { name: "Copy message" })).toBeEnabled();
});

test("I sent it records the send once; a refusal says why and changes nothing", async () => {
  const user = userEvent.setup();
  const onChanged = vi.fn();
  const fetcher = mock(handoff(), handoff("SENT"));
  render(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={onChanged} />);
  await user.click(await screen.findByRole("button", { name: "I sent it" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/handoffs/m1/sent",
    expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "Case-Access-Token": "secret" }) })));
  expect(await screen.findByText(/You sent this/)).toBeInTheDocument();
  expect(onChanged).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("button", { name: "I sent it" })).toBeNull();

  cleanup();
  mock(handoff(), new Response(JSON.stringify({ detail: "The current mandate or policy no longer permits this draft. It was not marked sent." }), { status: 409 }));
  render(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={onChanged} />);
  await user.click(await screen.findByRole("button", { name: "I sent it" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("It was not marked sent.");
  expect(screen.getByText(/Not sent yet/)).toBeInTheDocument();
});

test("a handed-off message can be discarded", async () => {
  const user = userEvent.setup();
  const fetcher = mock(handoff(), handoff("DISCARDED"));
  render(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} />);
  await user.click(await screen.findByRole("button", { name: "Discard this message" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/handoffs/m1/discard", expect.objectContaining({ method: "POST" })));
  expect(await screen.findByText(/Discarded. It was not sent./)).toBeInTheDocument();
});

test("a pasted dealer reply is posted as multipart with the conversation's credentials", async () => {
  const user = userEvent.setup();
  const onAdded = vi.fn();
  const fetcher = mock({ outcome: "RECEIVED", messageId: "in1" });
  const { container } = render(<main><h1>Case</h1><h2>Quote</h2><h3>Quote request</h3><DealerReplyPaste caseId="c1" token="secret" quoteRequestId="q1"
    dealerAddress="sales@dealer.example" onAdded={onAdded} /></main>);
  await user.click(screen.getByRole("button", { name: "Add reply" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Paste the dealer's reply");
  expect(fetcher).not.toHaveBeenCalled();
  await user.type(screen.getByRole("textbox", { name: "Dealer's reply" }), "Selling price $25,000.");
  await user.click(screen.getByRole("button", { name: "Add reply" }));
  await waitFor(() => expect(onAdded).toHaveBeenCalled());
  const [url, init] = fetcher.mock.calls[0];
  expect(url).toBe("/api/v1/cases/c1/quote-requests/q1/replies");
  expect((init!.body as FormData).get("text")).toBe("Selling price $25,000.");
  expect(init!.headers).toEqual(expect.objectContaining({ "Case-Access-Token": "secret", "Idempotency-Key": expect.any(String) }));
  expect(screen.getByText(/Reply added/)).toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
});

// Ticket 83: a signed-in Buyer with a connected mailbox sends from it; unclear outcomes wait for the Buyer.
const mailbox = { available: true, providers: ["GOOGLE"],
  connection: { provider: "GOOGLE", providerLabel: "Gmail", address: "buyer@gmail.example", connectedAt: "2026-10-06T12:00:00Z" } };
const viaMailbox = (state: string) => ({ ...handoff(state), sendPath: "CONNECTED_MAILBOX" });
function route(routes: Record<string, unknown[]>) {
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${url}`;
    const queue = routes[key];
    if (!queue) throw new Error(`Unexpected ${key}`);
    const next = queue.length > 1 ? queue.shift() : queue[0];
    return next instanceof Response ? next : new Response(JSON.stringify(next));
  });
  vi.stubGlobal("fetch", fetcher); return fetcher;
}
const signedIn = (node: React.ReactNode) => <AccountSignedInContext.Provider value={true}>{node}</AccountSignedInContext.Provider>;

test("Send from my email sends the approved text through the connected account, next to the own-email-app path", async () => {
  const user = userEvent.setup();
  const onChanged = vi.fn();
  const fetcher = route({ "GET /api/v1/account/mailbox": [mailbox], "GET /api/v1/cases/c1/handoffs/m1": [handoff()],
    "POST /api/v1/cases/c1/handoffs/m1/mailbox-send": [viaMailbox("SENT")] });
  const { container } = render(signedIn(<main><h1>Case</h1><DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={onChanged} /></main>));
  expect(await screen.findByText("buyer@gmail.example")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Open in my email app" })).toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
  await user.click(screen.getByRole("button", { name: "Send from my email" }));
  expect(await screen.findByText(/Sent from your connected email/)).toBeInTheDocument();
  const [, init] = fetcher.mock.calls.find(([url]) => url.endsWith("/mailbox-send"))!;
  expect(init?.headers).toEqual(expect.objectContaining({ "Case-Access-Token": "secret", "Account-Request": "1" }));
  expect(onChanged).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("button", { name: "Send from my email" })).toBeNull();
});

test("an unclear send asks the Buyer to check their Sent folder; It was sent, Send again and the email app are their choice", async () => {
  const user = userEvent.setup();
  const fetcher = route({ "GET /api/v1/account/mailbox": [mailbox], "GET /api/v1/cases/c1/handoffs/m1": [viaMailbox("DELIVERY_UNKNOWN")],
    "POST /api/v1/cases/c1/handoffs/m1/sent": [viaMailbox("SENT")] });
  const { container } = render(signedIn(<main><h1>Case</h1><DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} /></main>));
  expect(await screen.findByText(/did not say whether this message was sent/)).toBeInTheDocument();
  expect(screen.getByText(/Check the Sent folder/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Send again" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "It was not sent: use my email app" })).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Discard this message" })).toBeNull();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
  await user.click(screen.getByRole("button", { name: "It was sent" }));
  expect(await screen.findByText(/Sent from your connected email/)).toBeInTheDocument();
  expect(fetcher.mock.calls.filter(([url]) => url.endsWith("/mailbox-send"))).toHaveLength(0);

  cleanup();
  route({ "GET /api/v1/account/mailbox": [mailbox], "GET /api/v1/cases/c1/handoffs/m1": [viaMailbox("DELIVERY_UNKNOWN")],
    "POST /api/v1/cases/c1/handoffs/m1/own-email-app": [handoff()] });
  render(signedIn(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} />));
  await user.click(await screen.findByRole("button", { name: "It was not sent: use my email app" }));
  expect(await screen.findByRole("link", { name: "Open in my email app" })).toBeInTheDocument();
});

test("a refused mailbox send says why, sends nothing, and leaves the own-email-app path open", async () => {
  const user = userEvent.setup();
  route({ "GET /api/v1/account/mailbox": [mailbox], "GET /api/v1/cases/c1/handoffs/m1": [handoff()],
    "POST /api/v1/cases/c1/handoffs/m1/mailbox-send": [new Response(JSON.stringify({ code: "MAILBOX_NOT_CONNECTED", ownEmailApp: true,
      detail: "Your email is not connected, so nothing was sent. Send it from your own email app, or connect Gmail or Outlook on your account page." }), { status: 409 })] });
  render(signedIn(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} />));
  await user.click(await screen.findByRole("button", { name: "Send from my email" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("nothing was sent");
  expect(screen.getByRole("link", { name: "Open in my email app" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "I sent it" })).toBeEnabled();
});

test("without a connection or a sign-in, only the own-email-app path is offered", async () => {
  route({ "GET /api/v1/account/mailbox": [{ available: true, providers: ["GOOGLE"], connection: null }],
    "GET /api/v1/cases/c1/handoffs/m1": [handoff()] });
  render(signedIn(<DealerHandoff caseId="c1" token="secret" messageId="m1" onChanged={vi.fn()} />));
  expect(await screen.findByRole("link", { name: "Open in my email app" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Send from my email" })).toBeNull();
});
