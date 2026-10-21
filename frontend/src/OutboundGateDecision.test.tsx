import { cleanup, render, screen } from "@testing-library/react";
import axe from "axe-core";
import { readFileSync } from "node:fs";
import { afterEach, expect, test } from "vitest";
import { OutboundGateDecision } from "./OutboundGateDecision";
import { en } from "./i18n/en";
import { LanguageProvider } from "./i18n/LanguageProvider";

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

test("every reason the gate can give has catalogue text, so Spanish Buyers never see it in English", () => {
  const gate = readFileSync("../backend/src/main/java/com/dealanalyzer/outbound/OutboundGate.java", "utf8");
  const served = [...gate.matchAll(/add\(reasons, "[A-Z_]+", "([^"]+)"\)/g)].map(match => match[1]);
  expect(served.length).toBeGreaterThan(10);
  expect(new Set(served)).toEqual(new Set(Object.values(en.messages.outboundGateDecision.reasons)));
});

test("a Spanish viewer sees known reasons in Spanish and unknown ones as sent", () => {
  render(<LanguageProvider language="es"><OutboundGateDecision decision={{ pass: false, gateVersion: "outbound-gate.v5", reasons: [
    { code: "UNSUPPORTED_LANGUAGE", text: en.messages.outboundGateDecision.reasons.unsupportedLanguage },
    { code: "PERSONAL_DATA", text: "Your phone number has not been released for this dealer." },
  ] }} /></LanguageProvider>);
  expect(screen.getByText(/no se pueden comprobar con los mensajes aprobados/)).toBeInTheDocument();
  expect(screen.getByText("Your phone number has not been released for this dealer.")).toBeInTheDocument();
});
