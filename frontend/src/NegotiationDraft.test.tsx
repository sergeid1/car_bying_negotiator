import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { NegotiationDraft, type NegotiationVersion } from "./NegotiationDraft";

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
