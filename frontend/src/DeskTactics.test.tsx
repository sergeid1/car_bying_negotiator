import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, test } from "vitest";
import { DeskTactics } from "./DeskTactics";
import { syntheticTactic, syntheticTradeTactics } from "./DeskTactics.fixture";

afterEach(cleanup);
test("tactics show amounts, documentary status, evidence and question review", () => {
  render(<DeskTactics findings={[syntheticTactic]} />);
  expect(screen.getByText(/Monthly effect: \$40.00/)).toHaveTextContent("Full-term effect: $2,400.00");
  expect(screen.getByText(/Question wording awaits/)).toBeInTheDocument();
  expect(screen.getByText(/page 2/)).toBeInTheDocument();
  expect(screen.getByText(/Policy: desk-tactics.v1/)).toBeInTheDocument();
});
test("missing amounts state why and a clean worksheet adds no panel", () => {
  const { rerender, container } = render(<DeskTactics />);
  expect(container).toBeEmptyDOMElement();
  rerender(<DeskTactics findings={[{ ...syntheticTactic, monthlyEffectMinor: null, totalEffectMinor: null,
    noDollarEffectReason: "Amount financed is missing." }]} />);
  expect(screen.getByText(/Dollar effect unavailable: Amount financed is missing/)).toBeInTheDocument();
});
test("linked trade tactics never create a second worksheet item, and v1 remains visible", () => {
  const { container, rerender } = render(<DeskTactics findings={syntheticTradeTactics} />);
  expect(container).toBeEmptyDOMElement();
  rerender(<DeskTactics findings={[syntheticTactic, ...syntheticTradeTactics]} />);
  expect(screen.getByText("Possible payment packing")).toBeInTheDocument();
  expect(screen.queryByText("Negative equity folded in")).not.toBeInTheDocument();
  expect(screen.getByText(/Policy: desk-tactics.v1/)).toBeInTheDocument();
});
