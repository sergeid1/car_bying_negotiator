import { cleanup, render, screen } from "@testing-library/react";
import axe from "axe-core";
import { afterEach, expect, test } from "vitest";
import { OutboundGateDecision } from "./OutboundGateDecision";

afterEach(cleanup);

test("a stored refusal shows all plain words reasons accessibly", async () => {
  const { container } = render(<OutboundGateDecision decision={{ pass: false, gateVersion: "outbound-gate.v1", reasons: [
    { code: "ABOVE_CEILING", text: "The draft includes an amount above your walk-away ceiling." },
    { code: "PERSONAL_DATA", text: "Your phone number has not been released for this dealer." },
  ] }} />);
  expect(screen.getByRole("heading", { name: "Draft blocked by the policy check" })).toBeInTheDocument();
  expect(screen.getAllByRole("listitem")).toHaveLength(2);
  expect(screen.getByText(/above your walk-away ceiling/)).toBeInTheDocument();
  expect(screen.getByText(/phone number has not been released/)).toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
});

test("a stored pass explains that a current check and Buyer approval are still needed", () => {
  render(<OutboundGateDecision decision={{ pass: true, gateVersion: "outbound-gate.v1", reasons: [] }} />);
  expect(screen.getByRole("heading", { name: "Draft passed the policy check" })).toBeInTheDocument();
  expect(screen.getByText(/Sending requires a current check and your approval/)).toBeInTheDocument();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
