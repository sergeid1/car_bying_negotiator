import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, test } from "vitest";
import { MarketEvidenceSection } from "./CoreDealReport";

afterEach(cleanup);

test("shows frozen retrieval, observation window and uncertainty in ordinary wording", () => {
  const paragraphs = ["Market data retrieved: 2026-10-02T12:00:00Z.",
    "Observation window: 2026-09-20T12:00:00Z to 2026-09-30T12:00:00Z.",
    "Uncertainty: medium confidence from 4 usable comparables. This is an estimated selling-price range."];
  render(<MarketEvidenceSection evidence={{ status: "AVAILABLE", paragraphs }} />);
  for (const paragraph of paragraphs) expect(screen.getByText(paragraph)).toBeVisible();
});

test.each(["Too few compatible, current comparables (2) to support a market conclusion. No market rating is drawn.",
  "Market evidence is unavailable. Provider schema drift; market analysis is unavailable. No market rating is drawn.",
  "Market evidence is unavailable: the stored snapshot is stale or has an invalid retrieval time. No market rating is drawn."])
  ("states the saved limitation: %s", paragraph => {
    render(<MarketEvidenceSection evidence={{ status: "UNAVAILABLE", paragraphs: [paragraph] }} />);
    expect(screen.getByText(paragraph)).toBeVisible();
  });
