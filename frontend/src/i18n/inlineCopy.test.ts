// @vitest-environment node
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parseSync } from "vite";
import { describe, expect, it } from "vitest";

/**
 * Buyer-visible text belongs in the message catalogues (ticket 145). This flags text written inline in a component:
 * JSX text, copy attributes such as `aria-label` and `alt`, strings rendered as JSX children, and sentences handed to
 * state setters or thrown as errors, which the screens show. Files not yet moved are listed in PENDING; the list may
 * only shrink, and a file leaves it as soon as it is clean.
 */
const SOURCE = join(__dirname, "..");

/** Text that is the same in every language. */
const ALLOWED = [/^otdog$/, /^[\s\p{P}\p{S}\d]*$/u];

const COPY_ATTRIBUTES = new Set(["aria-label", "aria-description", "aria-roledescription", "aria-valuetext",
  "aria-placeholder", "alt", "title", "placeholder", "label"]);

/** Components whose copy has not moved into the catalogue yet, one list per journey. Remove a file when its copy moves. */

/** Journey: starting a case, sign-in and the Buyer Account; copy in en/account.ts. */
const ACCOUNT_PENDING: string[] = [];

/** Journey: the dealer's paperwork and the report; copy in en/paperwork.ts. */
const PAPERWORK_PENDING = [
  "AgreedPrice.tsx",
  "CoreDealReport.tsx",
  "DealerAddOns.tsx",
  "DealerDocumentUpload.tsx",
  "DocFeeFindings.tsx",
  "EvidenceSource.tsx",
  "GovernmentFees.tsx",
  "OfferVerification.tsx",
  "PilotFeedback.tsx",
  "ReportExplanation.tsx",
  "ReportTotalMeasure.tsx",
  "RevisedOfferReview.tsx",
  "SampleReport.tsx",
  "VehicleSafety.tsx",
  "VerifiedOfferAnalysis.tsx",
];

/** Journey: at the dealer; copy in en/dealer.ts. */
const DEALER_PENDING = [
  "AtTheDealer.tsx",
  "CaseFlow.tsx",
  "DeskCheck.tsx",
  "DeskTactics.tsx",
  "TestDriveAppointment.tsx",
  "TradeCheck.tsx",
  "TradeValueChecks.tsx",
  "TradeVehicle.tsx",
];

/** Journey: messages to the dealer; copy in en/messages.ts. */
const MESSAGES_PENDING = [
  "DealerHandoff.tsx",
  "DealerRelay.tsx",
  "MailboxConnection.tsx",
  "NegotiationConversation.tsx",
  "NegotiationDraft.tsx",
  "NegotiationMandate.tsx",
  "OutboundGateDecision.tsx",
  "QuoteComparison.tsx",
  "QuoteRequest.tsx",
];

/** Journey: insurance, loans, financing and the offer; copy in en/money.ts. */
const MONEY_PENDING = [
  "DealerFinancing.tsx",
  "InsuranceQuotes.tsx",
  "LoanPreApprovals.tsx",
  "OneTimeOffer.tsx",
  "OutsideFinancing.tsx",
  "PricePlan.tsx",
];

/** Journey: Buying Journeys, listings, guides, the content pages and Toto; copy in en/content.ts. */
const CONTENT_PENDING = [
  "BuyingJourneys.tsx",
  "CandidateVehicles.tsx",
  "ExplainerGuides.tsx",
  "HowItWorks.tsx",
  "HowOtdogMakesMoney.tsx",
  "JourneyCandidates.tsx",
  "ListingCapture.tsx",
  "ReferralPartnerKit.tsx",
  "TotoChat.tsx",
];

const PENDING = [...ACCOUNT_PENDING, ...PAPERWORK_PENDING, ...DEALER_PENDING, ...MESSAGES_PENDING, ...MONEY_PENDING, ...CONTENT_PENDING];

type Node = { type: string; start: number; [key: string]: unknown };

function isNode(value: unknown): value is Node {
  return typeof value === "object" && value !== null && typeof (value as Node).type === "string";
}

function children(node: Node): Node[] {
  return Object.values(node).flatMap(value => Array.isArray(value) ? value.filter(isNode) : isNode(value) ? [value] : []);
}

function allowed(text: string) {
  const trimmed = text.replace(/\s+/g, " ").trim();
  return ALLOWED.some(pattern => pattern.test(trimmed));
}

/** Reads like a sentence or a label rather than a code: "Unavailable" or "not stated", not "QUEUED" or "idle". */
function prose(text: string) {
  return !allowed(text) && !/^[A-Z0-9_]+$/.test(text) && (/\s/.test(text.trim()) || /^[A-Z][a-z]/.test(text));
}

/** The strings an expression can render: literal branches of conditionals, `||`/`??` fallbacks and template text. */
function renderedText(expression: Node): { text: string; at: number }[] {
  switch (expression.type) {
    case "Literal": return typeof expression.value === "string" ? [{ text: expression.value, at: expression.start }] : [];
    case "TemplateLiteral": return [
      ...(expression.quasis as Node[]).map(quasi => ({ text: (quasi.value as { cooked: string }).cooked, at: quasi.start })),
      ...(expression.expressions as Node[]).flatMap(renderedText)];
    case "ConditionalExpression": return [...renderedText(expression.consequent as Node), ...renderedText(expression.alternate as Node)];
    case "LogicalExpression": return [...renderedText(expression.left as Node), ...renderedText(expression.right as Node)];
    case "BinaryExpression": return expression.operator === "+"
      ? [...renderedText(expression.left as Node), ...renderedText(expression.right as Node)] : [];
    case "ParenthesizedExpression": case "TSAsExpression": return renderedText(expression.expression as Node);
    default: return [];
  }
}

function calleeName(call: Node) {
  const callee = call.callee as Node;
  return callee.type === "Identifier" ? callee.name as string
    : callee.type === "MemberExpression" ? ((callee.property as Node).name as string) : "";
}

export function inlineCopy(file: string, source: string) {
  const { program, errors } = parseSync(file, source, { lang: file.endsWith(".tsx") ? "tsx" : "ts" });
  if (errors.length) throw new Error(`${file} did not parse: ${errors[0].message}`);
  const found: string[] = [];
  const line = (at: number) => source.slice(0, at).split("\n").length;
  const flag = (text: string, at: number) => found.push(`${file}:${line(at)} ${JSON.stringify(text.replace(/\s+/g, " ").trim())}`);
  const visit = (node: Node) => {
    if (node.type === "JSXText" && !allowed(node.value as string) && /\p{L}/u.test(node.value as string)) flag(node.value as string, node.start);
    if (node.type === "JSXExpressionContainer")
      for (const { text, at } of renderedText(node.expression as Node)) if (/\p{L}/u.test(text) && !allowed(text)) flag(text, at);
    if (node.type === "JSXAttribute") {
      // Only copy attributes are text; a class name or an href is not. Nested JSX in any attribute is still visited.
      const value = node.value as Node | null;
      const expression = value?.type === "JSXExpressionContainer" ? value.expression as Node : value;
      if (expression && COPY_ATTRIBUTES.has((node.name as Node).name as string))
        for (const { text, at } of renderedText(expression)) if (/\p{L}/u.test(text) && !allowed(text)) flag(text, at);
      if (expression) visit(expression);
      return;
    }
    if (node.type === "CallExpression" || node.type === "NewExpression") {
      const name = node.type === "NewExpression" ? ((node.callee as Node).name as string) : calleeName(node);
      if (/^set[A-Z]/.test(name) || name === "Error")
        for (const argument of node.arguments as Node[]) for (const { text, at } of renderedText(argument)) if (prose(text)) flag(text, at);
    }
    if (node.type === "AssignmentExpression" && source.slice((node.left as Node).start, (node.left as Node & { end: number }).end) === "document.title")
      for (const { text, at } of renderedText(node.right as Node)) if (prose(text)) flag(text, at);
    for (const child of children(node)) visit(child);
  };
  visit(program as unknown as Node);
  return found;
}

function components() {
  return readdirSync(SOURCE).filter(name => /\.tsx?$/.test(name) && !/\.(test|spec|fixture)\.tsx?$/.test(name)
    && !name.endsWith(".d.ts")).sort();
}

describe("Buyer-visible copy lives in the catalogues", () => {
  it("finds no inline copy outside the files still pending migration", () => {
    const found = components().filter(name => !PENDING.includes(name))
      .flatMap(name => inlineCopy(name, readFileSync(join(SOURCE, name), "utf8")));
    expect(found, "Move these strings into frontend/src/i18n/en.ts").toEqual([]);
  });

  it("keeps the pending list honest: every listed file exists and still has inline copy", () => {
    const names = components();
    for (const name of PENDING) {
      expect(names, `${name} no longer exists; remove it from PENDING`).toContain(name);
      expect(inlineCopy(name, readFileSync(join(SOURCE, name), "utf8")).length,
        `${name} has no inline copy left; remove it from PENDING`).toBeGreaterThan(0);
    }
  });

  it("flags the forms inline copy takes", () => {
    const source = `const a = <p title="Hi there">Hello {ok ? "Yes" : "No"}</p>;
      setError("Something went wrong."); throw new Error("Could not save"); document.title = "Report";
      const b = <p className="muted" aria-hidden="true">{m.text} · {count}</p>; setStage("QUEUED"); setMode("idle");
      const c = <a href="/">otdog</a>;`;
    expect(inlineCopy("example.tsx", source).map(entry => entry.replace(/^example\.tsx:\d+ /, ""))).toEqual(
      ['"Hi there"', '"Hello"', '"Yes"', '"No"', '"Something went wrong."', '"Could not save"', '"Report"']);
  });
});
