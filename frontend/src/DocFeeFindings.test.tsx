import { render, screen } from "@testing-library/react";
import axe from "axe-core";
import { expect, it } from "vitest";
import { DocFeeFindings } from "./DocFeeFindings";
import { RevisedOfferReview } from "./RevisedOfferReview";

const finding = {
  reason: "Doc fee: $300.00 on the paperwork. Texas has a $225 documentary fee safe harbor.",
  buyerAction: "Has your dealership filed for a doc fee above $225 with the OCCC?", uncertain: false,
  observedEvidence: { rule: { citation: "7 TAC 84.205", url: "https://occc.texas.gov/" } },
};

it("shows a state question and citation inside the revised offer review", async () => {
  const { container } = render(<RevisedOfferReview review={{ version: 1, baselineVersion: 1, previousOfferVersion: 1,
    currentOfferVersion: 2, status: "REVIEW_REQUIRED", checkedAt: "2026-10-18", tolerancePolicy: "USD 1.00",
    scope: "Verified document terms", discrepancies: [], cashDeltas: {}, docFeeFindings: [finding] }} />);
  expect(screen.getByText(finding.reason)).toBeInTheDocument();
  expect(screen.getByText(`Ask the dealer: ${finding.buyerAction}`)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: finding.observedEvidence.rule.citation })).toHaveAttribute("href", "https://occc.texas.gov/");
  expect((await axe.run(container, { runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa"] } })).violations).toEqual([]);
});

it("keeps unverified evidence explicit and older reports empty", () => {
  const { rerender, container } = render(<DocFeeFindings findings={[{ ...finding, uncertain: true }]} />);
  expect(screen.getByText(/Read from paperwork; verify/)).toBeInTheDocument();
  rerender(<DocFeeFindings />);
  expect(container).toBeEmptyDOMElement();
});
