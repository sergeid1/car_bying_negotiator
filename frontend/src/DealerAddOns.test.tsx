import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { DealerAddOnsNote } from "./DealerAddOns";
import { namedAddOns, readAddOns } from "./DealerAddOns.fixture";

afterEach(cleanup);

it("lists every named add-on with its amount, kind wording and question, and the unrecognised charge too", () => {
  render(<DealerAddOnsNote check={namedAddOns} />);
  const list = screen.getByRole("list");
  expect(within(list).getAllByRole("listitem")).toHaveLength(5);
  expect(screen.getByText(namedAddOns.summary)).toBeInTheDocument();
  const etch = within(list).getAllByRole("listitem")[0];
  expect(etch).toHaveTextContent("VIN ETCH, $299: VIN etching. Usually optional and negotiable.");
  expect(within(etch).getByText(/etched into the windows/)).toBeInTheDocument();
  expect(within(etch).getByText(/Are the windows already etched/)).toBeInTheDocument();
  expect(screen.getByText(/Financed with the car, it also adds interest/)).toBeInTheDocument();
  expect(screen.getByText(/A price increase on the car, not a service/)).toBeInTheDocument();
  expect(within(list).getAllByRole("listitem")[3]).toHaveTextContent("Exterior Shine, $499: Possibly paint protection.");
  expect(within(list).getAllByRole("listitem")[4]).toHaveTextContent("Unrecognised charge — ask what it is.");
  expect(screen.getByText(namedAddOns.listingNote!)).toBeInTheDocument();
});

it("keeps an unread amount and unverified lines explicit, and can leave the questions to other findings", () => {
  render(<DealerAddOnsNote check={readAddOns} question={false} />);
  expect(screen.getByText(/amount not read/)).toBeInTheDocument();
  expect(screen.getByText("Read from the paperwork; these lines are not yet verified.")).toBeInTheDocument();
  expect(screen.queryByText("Ask the dealer")).toBeNull();
});
