import { StrictMode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
import type { CoreReport } from "./CoreDealReport";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test("StrictMode opens one session and sends elapsed time only when the Buyer checks the total", async () => {
  let now = 100;
  vi.spyOn(performance, "now").mockImplementation(() => now);
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, options) => {
    const body = JSON.parse(String(options?.body));
    return new Response(JSON.stringify(body.reportVersion ? { id: "session" } : { correct: body.answerMinor === 2850012 }));
  });
  render(<StrictMode><ReportTotalMeasure caseId="case" token="token" report={{ version: 2 } as CoreReport} /></StrictMode>);
  expect(fetcher).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByText("Optional pilot check: find the total"));
  fireEvent.change(screen.getByLabelText("Total in the report (USD)"), { target: { value: "28500.12" } });
  now = 2600;
  fireEvent.submit(screen.getByRole("button", { name: "Check total" }).closest("form")!);
  await screen.findByText("Total correctly identified. Pilot time saved.");
  expect(fetcher.mock.calls[1][0]).toBe("/api/v1/cases/case/feedback/report-total/session");
  expect(JSON.parse(String(fetcher.mock.calls[1][1]?.body))).toEqual({ answerMinor: 2850012, unknown: false, durationMillis: 2500 });
  expect(screen.getByRole("button", { name: "Check total" })).toBeDisabled();
});
