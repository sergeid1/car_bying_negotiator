import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { readFileSync } from "node:fs";
import { afterEach, expect, test, vi } from "vitest";

const OPTIONS = { supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } };
const DISCLOSURE = "Example Inspections refers Buyers here and receives a flat fee per referral.";

function stubApi(created: unknown) {
  const calls: { url: string; body?: Record<string, unknown> }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const body = url.endsWith("/intake-options") ? OPTIONS
      : url.endsWith("/api/v1/cases") ? created
      : url.endsWith("/vehicle-evidence") ? { identityConflicts: [], reliableForAnalysis: true }
      : url.endsWith("/document-limits") ? { acceptedMediaTypes: ["image/png"], maxFileBytes: 1, maxPages: 1, maxPixels: 1, minWidth: 1, minHeight: 1 }
      : [];
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

async function startCase() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/your zip code/i), "27601");
  await user.type(screen.getByLabelText(/dealer zip code/i), "27604");
  await user.type(screen.getByLabelText(/^vin$/i), "1HGCM82633A004352");
  await user.click(screen.getByRole("button", { name: /start my case/i }));
}

/** The code is read once per page load, so each test loads the client afresh. */
async function freshApp(address: string) {
  window.history.replaceState(null, "", address);
  vi.resetModules();
  return (await import("./App")).default;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

test("the link's channel code leaves the address bar at case start and is sent once with case creation", async () => {
  const calls = stubApi({ case: { id: "case-1", referralDisclosure: null }, accessToken: "secret" });
  const stored = vi.spyOn(Storage.prototype, "setItem");
  const App = await freshApp("/?channel=fee-guide&view=intake#start");
  render(<App />);
  expect(window.location.pathname + window.location.search + window.location.hash).toBe("/?view=intake#start");

  await startCase();
  await waitFor(() => expect(calls.some(call => call.url.endsWith("/vehicle-evidence"))).toBe(true));
  const creation = calls.find(call => call.url.endsWith("/api/v1/cases"))!.body!;
  expect(creation.channel).toBe("fee-guide");
  expect(screen.queryByRole("heading", { name: "How you reached us" })).toBeNull();
  // The code is the only acquisition datum: nothing is kept in browser storage or cookies.
  expect(stored).not.toHaveBeenCalled();
  expect(document.cookie).toBe("");
  // jsdom has no cookie jar or referrer worth spying on, so the rest is read from source.
  const source = readFileSync("src/AcquisitionChannel.tsx", "utf8").replace(/\/\*\*[\s\S]*?\*\//g, "");
  for (const api of ["localStorage", "sessionStorage", "indexedDB", "document.cookie", "referrer", "navigator.", "<script"]) {
    expect(source, `${api} has no place in channel attribution`).not.toContain(api);
  }
});

test("a malformed or absent code is not sent and never blocks the case", async () => {
  const calls = stubApi({ case: { id: "case-1" }, accessToken: "secret" });
  const App = await freshApp("/?channel=%3Cscript%3E");
  render(<App />);
  expect(window.location.search).toBe("");

  await startCase();
  await screen.findByRole("heading", { name: /add the dealer's paperwork/i });
  expect(calls.find(call => call.url.endsWith("/api/v1/cases"))!.body).not.toHaveProperty("channel");
});

test("a referral partnership's disclosure is shown before the upload step and passes the accessibility sweep", async () => {
  stubApi({ case: { id: "case-1", referralDisclosure: DISCLOSURE }, accessToken: "secret" });
  const App = await freshApp("/?channel=inspector");
  const { container } = render(<App />);

  await startCase();
  const disclosure = await screen.findByRole("region", { name: "How you reached us" });
  expect(disclosure).toHaveTextContent(DISCLOSURE);
  const upload = await screen.findByRole("heading", { name: /add the dealer's paperwork/i });
  expect(disclosure.compareDocumentPosition(upload) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  const result = await axe.run(container, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
    rules: { "color-contrast": { enabled: false } },
  });
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});
