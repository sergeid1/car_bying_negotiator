import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test } from "vitest";
import { GovernmentFeeNote } from "./GovernmentFees";
import { paddedFeeCheck, uncheckedFees } from "./GovernmentFees.fixture";

afterEach(cleanup);

test("a padded state fee line shows the state's number and the question to ask", async () => {
  render(<GovernmentFeeNote check={paddedFeeCheck} />);
  expect(screen.getByRole("heading", { name: "State fees" })).toBeInTheDocument();
  expect(screen.getByText(/add up to at most \$601.75/)).toBeInTheDocument();
  expect(screen.getByText("Please itemize the state fees on this line.")).toBeInTheDocument();
  expect(screen.getByText(/not yet verified/)).toBeInTheDocument();
  await userEvent.click(screen.getByText("1 note on the state fee lines"));
  expect(screen.getByText(/not counted again with the state fees/)).toBeVisible();
  await userEvent.click(screen.getByText("What North Carolina's fees are for"));
  expect(screen.getByText(/Puts the car's ownership in your name/)).toBeVisible();
  expect(screen.getByText(/set by local rates/)).toBeVisible();
});

test("a state with no schedule says the fees were not checked and asks nothing", () => {
  render(<GovernmentFeeNote check={uncheckedFees} />);
  expect(screen.getByText(/was not checked/)).toBeInTheDocument();
  expect(screen.getByText("No amount on this paperwork was treated as checked.")).toBeInTheDocument();
  expect(screen.queryByText(/Ask at the desk/)).toBeNull();
});
