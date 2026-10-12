/** Synthetic case records for each stage of the guided case page (ticket 112), shared by unit and browser tests. */
export type CaseFlowStage = "NEW" | "QUOTE" | "VERIFIED_OFFER" | "AGREED_PRICE" | "DESK_PAPERWORK" | "DESK_COMPARED";

export const CASE_FLOW_STAGES: CaseFlowStage[] = ["NEW", "QUOTE", "VERIFIED_OFFER", "AGREED_PRICE", "DESK_PAPERWORK", "DESK_COMPARED"];

const agreedPrice = { offerVersion: 1, baselineVersion: 1, vin: "1HGCM82633A004352", sellingPriceMinor: 2500000,
  outTheDoorMinor: 2680000, acceptedAt: "2026-10-04T12:00:00Z" };
const deskBase = { source: null, agreedPrice, paperwork: null, findings: [], otherDifferences: [], unchangedTerms: 0,
  totalEffect: { basis: "UNAVAILABLE", currency: "USD", outTheDoorDeltaMinor: null, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: null },
  freshCase: null, dealReviewVersion: null };
const paperwork = { documentId: "desk-1", uploadedAt: "2026-10-05T15:00:00Z", offerVersion: null, vin: "1HGCM82633A004352" };

/** The JSON body each case endpoint returns at a stage, or undefined for "not found". */
export function caseFlowRecord(stage: CaseFlowStage, path: string): unknown {
  const at = CASE_FLOW_STAGES.indexOf(stage);
  if (/\/api\/v1\/cases\/[^/]+$/.test(path)) return { id: "case-1", dealerLocation: "Raleigh, NC", purchaseMethod: "CASH" };
  if (path.endsWith("/vehicle-evidence")) return { vehicle: { vin: "1HGCM82633A004352", year: 2021, make: "Honda", model: "Accord", trim: "EX-L" },
    listingSnapshot: { dealer: { id: "dealer-1", name: "Synthetic Triangle Motors", city: "Raleigh", state: "NC" } },
    identityConflicts: [], reliableForAnalysis: true };
  if (path.endsWith("/quote-requests")) return { negotiationState: at >= 1 ? "AWAITING_BUYER" : "NO_MANDATE", requests: [],
    dealers: [{ id: "dealer-1", name: "Synthetic Triangle Motors" }],
    documents: at >= 1 ? [{ documentId: "quote-1", outcome: "ACCEPTED", offerVersion: at >= 2 ? 1 : null, sourceKind: "ATTACHMENT", messageId: "message-1" }] : [] };
  if (path.endsWith("/quote-acceptance")) return { requests: [], agreedPrice: null };
  if (path.endsWith("/appointment")) return { agreedPrice: null, messages: [], appointments: [], confirmations: [], contactFields: [] };
  if (path.endsWith("/negotiation-conversation")) return { negotiationState: "NO_MANDATE", entries: [], conversations: [] };
  if (path.endsWith("/relay")) return { address: "case-1@replies.example.test", forwardingAvailable: false, messages: [] };
  if (path.endsWith("/mandates/proposal")) return { available: false, unavailableReason: "Set your price limits first.", planVersion: null,
    currency: null, targetMinor: null, ceilingMinor: null, disclosures: [] };
  if (path.endsWith("/mandates")) return [];
  if (path.endsWith("/dealer-offers/latest")) return at >= 2 ? { id: "offer-1", version: 1, status: "VERIFIED" } : undefined;
  if (path.endsWith("/desk-check")) {
    if (at < 3) return { ...deskBase, status: "NO_AGREED_PRICE", agreedPrice: null };
    if (at === 3) return { ...deskBase, status: "AWAITING_PAPERWORK" };
    if (at === 4) return { ...deskBase, status: "READING", paperwork };
    return { ...deskBase, status: "DIFFERENCES", source: "READ_FROM_PAPERWORK", paperwork,
      findings: [{ group: "ADDED", term: "line:etch#1", label: "Window etch", kind: "ADDED", agreed: null,
        desk: { amountMinor: 49900, currency: "USD" }, delta: null, unit: "USD_MINOR", severity: "MATERIAL", verified: false,
        evidence: { pageNumber: 1 }, buyerAction: "Ask the dealer to remove this charge." }],
      totalEffect: { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 49900, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: 49900 } };
  }
  return undefined;
}

/** The step the case page opens on at each stage. */
export const CASE_FLOW_CURRENT: Record<CaseFlowStage, string> = {
  NEW: "Get quotes", QUOTE: "Compare & negotiate", VERIFIED_OFFER: "Compare & negotiate",
  AGREED_PRICE: "At the dealer", DESK_PAPERWORK: "At the dealer", DESK_COMPARED: "Final check",
};
