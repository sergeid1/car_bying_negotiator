import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ReportExplanationLoader, ReportExplanationView, type ReportExplanationPayload } from "./ReportExplanation";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const templateExplanation: ReportExplanationPayload = {
  policyVersion: "report-explanation.v1", path: "TEMPLATE", fallbackReason: "MODEL_PATH_OFF", reportVersion: 2, provenance: null,
  scope: "These explanations concern financial consistency only.",
  statements: [{ text: "The financial checks require review before you decide.",
    segments: [{ text: "The financial checks require review before you decide.", amountId: null, amountIndex: null, precision: null }],
    sources: [{ id: "review-status", sourcePointer: "/analysis/reviewStatus", sourceValue: "REVIEW_REQUIRED" }] }],
};

const modelExplanation: ReportExplanationPayload = {
  ...templateExplanation, path: "MODEL", fallbackReason: null,
  provenance: { modelId: "claude-opus-5", promptVersion: "report-explanation-prompt.v1", schemaVersion: "report-explanation-schema.v1" },
  statements: [{ text: "The out-the-door total of $34,800.00 (estimated) reconciles with the quoted total.",
    segments: [
      { text: "The out-the-door total of ", amountId: null, amountIndex: null, precision: null },
      { text: "$34,800.00 (estimated)", amountId: "amount-1", amountIndex: 1, precision: "ESTIMATED" },
      { text: " reconciles with the quoted total.", amountId: null, amountIndex: null, precision: null }],
    sources: [{ id: "quoted-total-reconciliation", sourcePointer: "/evidence/cashEconomics/result/reconciliation/withinTolerance", sourceValue: "true" },
      { id: "amount-1", sourcePointer: "/analysis/materialAmounts/1", sourceValue: "3480000" }] }],
};

it("shows template wording, says which producer made it, and traces each sentence to its finding", () => {
  render(<ReportExplanationView explanation={templateExplanation} />);
  expect(screen.getByRole("heading", { name: "What the findings mean" })).toBeInTheDocument();
  expect(screen.getByText("Template explanation")).toBeInTheDocument();
  expect(screen.getByText(/Generated explanations are switched off\. This is the fixed template wording/)).toBeInTheDocument();
  expect(screen.getByText("The financial checks require review before you decide.")).toBeInTheDocument();
  expect(screen.getByText("/analysis/reviewStatus")).toBeInTheDocument();
  expect(screen.queryByText(/^Model /)).not.toBeInTheDocument();
});

it("links each amount in generated text to the report figure it was rendered from and shows the model versions", () => {
  render(<ReportExplanationView explanation={modelExplanation} />);
  expect(screen.getByText("Generated explanation")).toBeInTheDocument();
  expect(screen.getByText(/Each figure is inserted from the report itself/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "$34,800.00 (estimated)" })).toHaveAttribute("href", "#amount-evidence-1");
  expect(screen.getByText(/report-explanation-prompt\.v1/)).toBeInTheDocument();
});

it("explains why a generated explanation was set aside", () => {
  render(<ReportExplanationView explanation={{ ...templateExplanation, fallbackReason: "UNSUPPORTED_CLAIM" }} />);
  expect(screen.getByText(/set aside because a sentence could not be matched to this report's recorded findings/)).toBeInTheDocument();
});

it("loads the explanation of the identified report with the case credentials", async () => {
  const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(templateExplanation)));
  render(<ReportExplanationLoader caseId="case 1" token="secret" reportVersion={2} />);
  expect(await screen.findByText("Template explanation")).toBeInTheDocument();
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("/api/v1/cases/case%201/reports/2/explanation");
  expect((init?.headers as Record<string, string>)["Case-Access-Token"]).toBe("secret");
});

it("states that the explanation is unavailable without hiding the report", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 404 }));
  render(<ReportExplanationLoader caseId="case" token="token" reportVersion={1} />);
  expect(await screen.findByText(/The explanation of this report is unavailable/)).toBeInTheDocument();
});
