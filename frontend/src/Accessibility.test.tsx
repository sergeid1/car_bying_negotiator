import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test } from "vitest";
import App from "./App";
import { CaseLifecycle } from "./CaseLifecycle";
import { PricePlan } from "./PricePlan";
import { CaseDownloads, RecoveryEmailOptIn } from "./CasePortability";
afterEach(cleanup);

test("core entry, recovery, price approval, downloads and deletion have no automated AA violations", async () => {
  const { container } = render(<><App /><main aria-label="Saved case controls">
    <PricePlan caseId="case" token="token" />
    <RecoveryEmailOptIn caseId="case" token="token" />
    <CaseDownloads caseId="case" token="token" reportVersion={1} />
    <CaseLifecycle caseId="case" token="token" onRevoked={() => {}} />
  </main></>);
  const result = await axe.run(container, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
    // Layout/contrast require the real-browser walkthrough; jsdom has no rendering engine.
    rules: { "color-contrast": { enabled: false } },
  });
  expect(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.html) }))).toEqual([]);
});

test("keyboard users can skip to the case workflow", async () => {
  render(<App />);
  const user = userEvent.setup();
  await user.tab();
  expect(screen.getByRole("link", { name: "Skip to case workflow" })).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(screen.getByRole("region", { name: "Open an existing Buying Case" })).toHaveFocus();
});
