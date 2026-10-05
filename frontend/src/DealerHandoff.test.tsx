import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
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
  expect(screen.queryByText(/on behalf|Deal X-Ray sent|opt out/i)).toBeNull();
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
