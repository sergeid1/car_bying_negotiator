import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { MailboxConnection } from "./MailboxConnection";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const unconnected = { available: true, providers: ["GOOGLE", "MICROSOFT"], connection: null };
const connected = { available: true, providers: ["GOOGLE", "MICROSOFT"],
  connection: { provider: "GOOGLE", providerLabel: "Gmail", address: "buyer@gmail.example", connectedAt: "2026-10-06T12:00:00Z" } };

test("explains send-only access and opens the provider's consent page to connect", async () => {
  const assign = vi.fn();
  vi.stubGlobal("location", { ...window.location, assign, search: "", pathname: "/", hash: "" });
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input).endsWith("/authorizations")
    ? json({ authorizationUrl: "https://accounts.example/consent?state=s" }) : json(unconnected));
  vi.stubGlobal("fetch", fetcher);
  const { container } = render(<main><h1>Account</h1><h2>Buyer Account</h2><MailboxConnection onSessionEnded={vi.fn()} /></main>);
  expect(await screen.findByText(/cannot read your email/)).toBeInTheDocument();
  expect(screen.getByText(/can still be sent from your own email app/)).toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
  await userEvent.setup().click(screen.getByRole("button", { name: "Connect Outlook" }));
  await waitFor(() => expect(assign).toHaveBeenCalledWith("https://accounts.example/consent?state=s"));
  const [, init] = fetcher.mock.calls.find(([url]) => String(url).endsWith("/authorizations"))!;
  expect(init?.method).toBe("POST");
  expect(JSON.parse(String(init?.body))).toEqual({ provider: "MICROSOFT" });
  expect((init?.headers as Record<string, string>)["Account-Request"]).toBe("1");
});

test("on return from the provider, completes once, clears the code from the address bar and shows the address", async () => {
  window.history.replaceState(null, "", "/?code=the-code&state=the-state&scope=x");
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
    init?.method === "POST" ? json(connected) : json(unconnected));
  vi.stubGlobal("fetch", fetcher);
  render(<MailboxConnection onSessionEnded={vi.fn()} />);
  expect(await screen.findByText(/Gmail is connected/)).toHaveAttribute("role", "status");
  expect(window.location.search).toBe("");
  expect(screen.getByText("buyer@gmail.example")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Disconnect Gmail" })).toBeEnabled();
  const posts = fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
  expect(posts).toHaveLength(1);
  expect(JSON.parse(String(posts[0][1]?.body))).toEqual({ state: "the-state", code: "the-code" });
});

test("a grant that would allow reading email is refused with the reason, and a declined consent connects nothing", async () => {
  window.history.replaceState(null, "", "/?code=read-code&state=state-2");
  vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === "POST"
    ? json({ code: "READ_SCOPE_REFUSED", detail: "The permission you granted would let Deal X-Ray read your email. It never needs that, so nothing was connected." }, 400)
    : json(unconnected)));
  render(<MailboxConnection onSessionEnded={vi.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("nothing was connected");
  expect(await screen.findByRole("button", { name: "Connect Gmail" })).toBeEnabled();
  cleanup();
  window.history.replaceState(null, "", "/?error=access_denied&state=state-3");
  render(<MailboxConnection onSessionEnded={vi.fn()} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("You did not allow sending");
  expect(window.location.search).toBe("");
});

test("disconnecting deletes the access and says how to send instead", async () => {
  vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
    init?.method === "DELETE" ? json(unconnected) : json(connected)));
  render(<MailboxConnection onSessionEnded={vi.fn()} />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Disconnect Gmail" }));
  expect(await screen.findByText(/Gmail is disconnected/)).toHaveTextContent("own email app");
  expect(screen.getByRole("button", { name: "Connect Gmail" })).toBeInTheDocument();
});

test("when connecting is not switched on, every message stays on the own-email-app path", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ available: false, providers: [], connection: null })));
  render(<MailboxConnection onSessionEnded={vi.fn()} />);
  expect(await screen.findByText(/not switched on yet/)).toHaveTextContent("own email app");
  expect(screen.queryByRole("button", { name: /Connect/ })).toBeNull();
});

test("an ended session hands back to the account panel", async () => {
  const ended = vi.fn();
  vi.stubGlobal("fetch", vi.fn(async () => json({ code: "SESSION_ENDED" }, 401)));
  render(<MailboxConnection onSessionEnded={ended} />);
  await waitFor(() => expect(ended).toHaveBeenCalled());
});
