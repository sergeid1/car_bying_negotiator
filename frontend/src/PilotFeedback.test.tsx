import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { FindingFeedback, OutcomeFeedback } from "./PilotFeedback";
afterEach(() => vi.unstubAllGlobals());

it("submits three separate finding ratings without sending finding prose", async () => {
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
  vi.stubGlobal("fetch", fetcher);
  render(<FindingFeedback caseId="case" token="secret" reportVersion={2} findingPointer="/analysis/reviewStatus" />);
  fireEvent.change(screen.getByLabelText("Accurate"), { target: { value: "YES" } });
  fireEvent.change(screen.getByLabelText("Understandable"), { target: { value: "NO" } });
  fireEvent.change(screen.getByLabelText("Useful"), { target: { value: "UNSURE" } });
  fireEvent.click(screen.getByRole("button", { name: "Send finding feedback" }));
  await screen.findByText("Finding feedback saved.");
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ reportVersion: 2, findingPointer: "/analysis/reviewStatus", accurate: "YES", understandable: "NO", useful: "UNSURE" });
});

it("allows action feedback without terms and keeps failures retryable", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce({ ok: false }).mockResolvedValueOnce({ ok: true, json: async () => ({}) });
  vi.stubGlobal("fetch", fetcher);
  render(<OutcomeFeedback caseId="case" token="secret" />);
  expect(fetcher).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Action taken (optional)"), { target: { value: "WALKED_AWAY" } });
  fireEvent.click(screen.getByRole("button", { name: "Send outcome feedback" }));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "Send outcome feedback" }));
  await waitFor(() => expect(screen.getByText("Outcome feedback saved.")).toBeInTheDocument());
  const body = JSON.parse(fetcher.mock.calls[1][1].body);
  expect(body.action).toBe("WALKED_AWAY");
  expect(body).not.toHaveProperty("outTheDoorMinor");
  expect(body).not.toHaveProperty("willingToPay");
});
