import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DealerReplyAssistant, NegotiationDraft, type NegotiationVersion, type ReplySuggestion } from "./NegotiationDraft";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it("shows supporting findings and copies or downloads buyer edits without changing the generated draft", async () => {
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: copy } });
  const version: NegotiationVersion = { reportVersion: 1, generatedAt: "2026-09-09", sourceReport: "/reports/1",
    language: { policyVersion: "grounded-language.v1", scope: "Financial consistency only",
      items: [{ sourcePointer: "/analysis/reviewStatus", sourceValue: '"REVIEW_REQUIRED"',
        explanation: "Financial checks require review.", question: "Could you explain the terms?" }],
      draft: "Could you explain the terms?" } };
  render(<NegotiationDraft version={version} />);
  expect(screen.getByText('"REVIEW_REQUIRED"')).toBeInTheDocument();
  fireEvent.change(screen.getByRole("textbox", { name: "Your negotiation draft" }), { target: { value: "My edited question" } });
  fireEvent.click(screen.getByRole("button", { name: "Copy draft" }));
  await waitFor(() => expect(copy).toHaveBeenCalledWith("My edited question"));
  const download = screen.getByRole("link", { name: "Download draft" });
  expect(decodeURIComponent(download.getAttribute("href")!)).toContain("My edited question");
  expect(download).toHaveAttribute("download", "negotiation-report-1.txt");
  expect(version.language.draft).toBe("Could you explain the terms?");
  expect(screen.getByText(/Buyer edits are not verified/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /send/i })).not.toBeInTheDocument();
});

const templateReply: ReplySuggestion = {
  policyVersion: "grounded-reply.v1", path: "TEMPLATE", fallbackReason: "MODEL_PATH_OFF", reportVersion: 3,
  dealerOfferId: "offer-1", dealerOfferVersion: 2, omittedStatements: 0, provenance: null,
  scope: "This reply states only figures recorded in this report.",
  statements: [
    { kind: "OPENING", text: "Thank you for your response.", sourcePointer: null, sourceValue: null },
    { kind: "FACT", text: "The current written offer lists a selling price of $32,000.", sourcePointer: "/evidence/cashEconomics/result/lineItems/0/amount/minorUnits", sourceValue: "3200000" },
    { kind: "PROPOSAL", text: "I would consider a revised written offer with a selling price of $30,000.", sourcePointer: null, sourceValue: null },
  ],
  draft: "Thank you for your response.\n\nThe current written offer lists a selling price of $32,000. I would consider a revised written offer with a selling price of $30,000.",
  proposal: { currency: "USD", sellingPriceMinor: 3000000, ceilingMinor: 3000000, sellingWalkAwayMinor: 3050000, outTheDoorWalkAwayMinor: 3300000 },
};

it("drafts a reply to a pasted Dealer response, says which producer made it, and leaves sending to the Buyer", async () => {
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: copy } });
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(templateReply)));
  render(<DealerReplyAssistant caseId="case 1" token="secret" reportVersion={3} />);
  expect(screen.getByRole("button", { name: "Draft a reply" })).toBeDisabled();
  fireEvent.change(screen.getByRole("textbox", { name: "Dealer's response" }), { target: { value: "Best we can do is $31,000." } });
  fireEvent.click(screen.getByRole("button", { name: "Draft a reply" }));
  expect(await screen.findByText("Template reply")).toBeInTheDocument();
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("/api/v1/cases/case%201/reports/3/negotiation/replies");
  expect(init?.method).toBe("POST");
  expect((init?.headers as Record<string, string>)["Case-Access-Token"]).toBe("secret");
  expect(JSON.parse(init?.body as string)).toEqual({ dealerResponse: "Best we can do is $31,000." });
  expect(screen.getByText(/Generated replies are switched off\. This reply comes from the fixed template/)).toBeInTheDocument();
  expect(screen.getByText(/does not read the Dealer's response/)).toBeInTheDocument();
  expect(screen.getByText(/Counter selling price \$30,000\.00, within your approved selling-price walk-away limit of \$30,500\.00 and out-the-door limit of \$33,000\.00/)).toBeInTheDocument();
  expect(screen.getByText("/evidence/cashEconomics/result/lineItems/0/amount/minorUnits")).toBeInTheDocument();
  const editor = screen.getByRole("textbox", { name: "Your reply" });
  expect(editor).toHaveValue(templateReply.draft);
  fireEvent.change(editor, { target: { value: "My edited reply" } });
  fireEvent.click(screen.getByRole("button", { name: "Copy reply" }));
  await waitFor(() => expect(copy).toHaveBeenCalledWith("My edited reply"));
  expect(decodeURIComponent(screen.getByRole("link", { name: "Download reply" }).getAttribute("href")!)).toContain("My edited reply");
  expect(screen.getByText(/the product does not contact the Dealer/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /send/i })).not.toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it("labels a generated reply and reports statements left out for lack of a recorded source", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ ...templateReply, path: "MODEL", fallbackReason: null,
    omittedStatements: 2, proposal: null, provenance: { modelId: "claude-opus-5", promptVersion: "reply-draft-prompt.v1", schemaVersion: "reply-draft-schema.v1" } })));
  render(<DealerReplyAssistant caseId="case" token="token" reportVersion={3} />);
  fireEvent.change(screen.getByRole("textbox", { name: "Dealer's response" }), { target: { value: "No." } });
  fireEvent.click(screen.getByRole("button", { name: "Draft a reply" }));
  expect(await screen.findByText("Generated reply")).toBeInTheDocument();
  expect(screen.getByText(/checked in code against this report's recorded figures and your approved limits/)).toBeInTheDocument();
  expect(screen.getByText("2 generated statements were left out because they could not be traced to a recorded source.")).toBeInTheDocument();
  expect(screen.queryByText(/Counter selling price/)).not.toBeInTheDocument();
});

it("shows why a reply could not be drafted", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ detail: "Paste the Dealer's response to draft a reply." }), { status: 400 }));
  render(<DealerReplyAssistant caseId="case" token="token" reportVersion={3} />);
  fireEvent.change(screen.getByRole("textbox", { name: "Dealer's response" }), { target: { value: "x" } });
  fireEvent.click(screen.getByRole("button", { name: "Draft a reply" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Paste the Dealer's response to draft a reply.");
  expect(screen.queryByRole("textbox", { name: "Your reply" })).not.toBeInTheDocument();
});
