import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { CASE_STEPS, currentStepOf } from "./CaseFlow";
import { CASE_FLOW_CURRENT, CASE_FLOW_STAGES, caseFlowRecord, type CaseFlowStage } from "./CaseFlow.fixture";

function stubCase(stage: CaseFlowStage) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const body = caseFlowRecord(stage, String(input));
    return body === undefined ? new Response("{}", { status: 404 }) : new Response(JSON.stringify(body));
  });
}
async function openCase(stage: CaseFlowStage) {
  stubCase(stage);
  window.history.replaceState(null, "", "/#case=case-1&access=secret");
  render(<App pollDelayMs={0} />);
  const progress = await screen.findByRole("navigation", { name: "Case progress" });
  await within(progress).findByRole("button", { current: "step" });
  return progress;
}
// Stands in for the "I'm at the dealer" flow (ticket 105) so these tests cover only its hand-over to the case page.
vi.mock("./AtTheDealer", () => ({
  DealerCameraEntry: ({ onPhotos }: { onPhotos: (files: File[]) => void }) =>
    <button type="button" onClick={() => onPhotos([new File(["page"], "desk.jpg", { type: "image/jpeg" })])}>Take desk photos</button>,
  AtTheDealer: ({ onDeskCheck }: { onDeskCheck: () => void }) =>
    <button type="button" onClick={onDeskCheck}>Open the full Desk Check</button>,
}));
afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

test("the step names live in one list of four", () => {
  expect(CASE_STEPS.map(step => step.name)).toEqual(["Get quotes", "Compare & negotiate", "At the dealer", "Final check"]);
});

test.each([
  [{}, "quotes"], [{ quote: true }, "compare"], [{ offer: true }, "compare"], [{ offer: true, agreedPrice: true }, "dealer"],
  [{ deskPaperwork: true }, "dealer"], [{ agreedPrice: true, deskPaperwork: true, deskCompared: true }, "final"],
])("progress %j puts the Buyer on %s", (flags, step) => {
  expect(currentStepOf({ quote: false, offer: false, agreedPrice: false, deskPaperwork: false, deskCompared: false, ...flags })).toBe(step);
});

test.each(CASE_FLOW_STAGES)("a case at stage %s opens on its current step, with no hero", async stage => {
  const progress = await openCase(stage);
  expect(screen.queryByText("The Buyer's side of the deal")).toBeNull();
  expect(await screen.findByRole("heading", { level: 1, name: "2021 Honda Accord EX-L" })).toBeVisible();
  expect(screen.getByText("Synthetic Triangle Motors · Raleigh, NC · Paying cash")).toBeVisible();

  const current = within(progress).getByRole("button", { current: "step" });
  expect(current).toHaveTextContent(CASE_FLOW_CURRENT[stage]);
  expect(current).toHaveAttribute("aria-expanded", "true");
  expect(within(progress).getAllByRole("button").filter(button => button.getAttribute("aria-expanded") === "true")).toHaveLength(1);
  const id = CASE_STEPS.find(step => step.name === CASE_FLOW_CURRENT[stage])!.id;
  expect(document.getElementById(`case-step-${id}-body`)).toBeVisible();
});

test("a new case greys later steps with what unlocks them, and shows no error", async () => {
  await openCase("NEW");
  expect(screen.getByText("Unlocks when you have a dealer quote.")).toBeVisible();
  expect(screen.getByText("Unlocks when you agree a price with the dealer.")).toBeVisible();
  expect(screen.getByText("Unlocks when the dealer's paperwork is read.")).toBeVisible();
  expect(await screen.findByRole("heading", { name: "Request an itemized quote" })).toBeVisible();
  expect(screen.queryAllByRole("alert").map(alert => alert.textContent)).toEqual([]);
});

test("finished steps fold into a summary line that reopens them", async () => {
  await openCase("DESK_COMPARED");
  const summary = document.querySelector<HTMLElement>(".case-done-summary")!;
  expect(within(summary).getAllByRole("button").map(button => button.textContent?.split(".")[0]))
    .toEqual(["Get quotes", "Compare & negotiate", "At the dealer"]);
  expect(document.getElementById("case-step-quotes")).not.toBeVisible();
  expect(await screen.findByText(/1 difference from your agreed price/)).toBeVisible();
  await userEvent.click(within(summary).getByRole("button", { name: "Get quotes. A dealer quote is in. Reopen this step" }));
  expect(document.getElementById("case-step-quotes-body")).toBeVisible();
  expect(document.getElementById("case-step-final-body")).not.toBeVisible();
});

test("the progress bar works by keyboard and any step opens by hand", async () => {
  const progress = await openCase("NEW");
  const user = userEvent.setup();
  const final = within(progress).getByRole("button", { name: /Final check/ });
  final.focus();
  await user.keyboard("{Enter}");
  expect(final).toHaveAttribute("aria-expanded", "true");
  expect(document.getElementById("case-step-final-body")).toBeVisible();
  expect(screen.getByText(/this step compares it with your agreed price/)).toBeVisible();
  expect(within(progress).getByRole("button", { current: "step" })).toHaveTextContent("Get quotes");
});

test("the case page's This case button opens the case panel", async () => {
  await openCase("QUOTE");
  const header = document.querySelector<HTMLElement>(".case-flow-header")!;
  await userEvent.click(within(header).getByRole("button", { name: "This case" }));
  expect(await screen.findByRole("dialog")).toBeVisible();
});

test("opening every step of a new case by hand shows what unlocks it, never an error", async () => {
  const progress = await openCase("NEW");
  for (const step of CASE_STEPS) {
    await userEvent.click(within(progress).getByRole("button", { name: new RegExp(step.name) }));
    expect(document.getElementById(`case-step-${step.id}-body`)).toBeVisible();
    expect(screen.queryAllByRole("alert").map(alert => alert.textContent)).toEqual([]);
  }
});

test("finishing the I'm at the dealer flow opens the At the dealer step", async () => {
  const progress = await openCase("QUOTE");
  const dealerStep = within(progress).getByRole("button", { name: /At the dealer/ });
  expect(dealerStep).toHaveAttribute("aria-expanded", "false");
  await userEvent.click(screen.getByRole("button", { name: "Take desk photos" }));
  await userEvent.click(screen.getByRole("button", { name: "Open the full Desk Check" }));
  expect(dealerStep).toHaveAttribute("aria-expanded", "true");
  expect(document.getElementById("case-step-dealer-body")).toBeVisible();
});
