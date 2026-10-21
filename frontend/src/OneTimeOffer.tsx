import { useEffect, useRef, useState } from "react";
import * as format from "./i18n/format";

/** `GET /api/v1/offer`. Codes are stable; every Buyer-facing word below is chosen here. */
export type Offer = {
  status: "AVAILABLE";
  offerId: string;
  kind: "ONE_TIME_PER_CASE";
  price: { amountMinor: number; currency: string };
  paymentPoint: string;
  free: string[];
  paid: string[];
  refund: { windowDays: number; terms: string };
  jurisdiction: { code: string; name: string } | null;
  excludedPurchaseTypes: string[];
  scopeLimits: string[];
  checkout: string;
};

type Purchase = { purchaseId: string; status: "PAID" | "PENDING"; checkoutUrl: string | null; checkout: string };

const FREE: Record<string, string> = {
  CASE_START: "Starting a Buying Case",
  VEHICLE_EVIDENCE: "Vehicle details and listing evidence",
  DOCUMENT_UPLOAD: "Uploading the dealer's documents",
  TERM_VERIFICATION: "Reading and verifying the quoted terms",
  CASE_RECOVERY: "Saved-link and email recovery of your case",
  DOCUMENT_EXPORT: "Exporting your own case data and documents",
  CASE_DELETION: "Deleting your case",
};

const PAID: Record<string, string> = {
  DEAL_ANALYSIS_REPORT: "The Deal Analysis Report",
  REPORT_EXPORT: "HTML and PDF downloads of the report",
  REPORT_EXPLANATION: "The plain-language explanation of the report",
  NEGOTIATION_DRAFTS: "Negotiation message drafts",
  REPLY_ASSISTANCE: "Help drafting replies to the dealer",
  REVISED_OFFER_REVIEW: "Comparison when the dealer changes the quote",
};

const SCOPE_LIMITS: Record<string, string> = {
  NO_DEALER_CONTACT: "We do not contact or negotiate with the dealer for you.",
  NO_LEGAL_OR_TAX_CONCLUSIONS: "No legal or tax conclusions.",
  NO_VEHICLE_HISTORY: "No vehicle history or mechanical condition check.",
  NO_TRADE_IN_VALUATION: "No trade-in valuation.",
  NO_FINAL_CONTRACT_REVIEW: "No review of the final contract you sign.",
};

const EXCLUDED: Record<string, string> = {
  NEW_VEHICLE: "New vehicles",
  LEASE: "Leases",
  PRIVATE_PARTY: "Private-party sales",
  AUCTION: "Auctions",
  MOTORCYCLE: "Motorcycles",
  RV: "Recreational vehicles",
  COMMERCIAL_VEHICLE: "Commercial vehicles",
};

/** An unknown code is still shown, readably, rather than dropped from the terms. */
function word(words: Record<string, string>, code: string) {
  if (words[code]) return words[code];
  const text = code.toLowerCase().replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function offerPrice(offer: Offer) {
  return format.money(offer.price.amountMinor, { currency: offer.price.currency });
}

function refundTerms(offer: Offer) {
  const days = `${offer.refund.windowDays} day${offer.refund.windowDays === 1 ? "" : "s"}`;
  return offer.refund.terms === "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW"
    ? `Full refund on request within ${days} of payment.`
    : `${word({}, offer.refund.terms)} (${days}).`;
}

/** Anything other than a well-formed available offer means no price and no paywall. */
export async function fetchOffer(): Promise<Offer | null> {
  try {
    const response = await fetch("/api/v1/offer");
    if (!response.ok) return null;
    const body = await response.json();
    return body?.status === "AVAILABLE" && typeof body.offerId === "string" && body.price ? body as Offer : null;
  } catch {
    return null;
  }
}

function useOffer() {
  const [offer, setOffer] = useState<Offer | null>();
  useEffect(() => {
    let cancelled = false;
    void fetchOffer().then(value => { if (!cancelled) setOffer(value); });
    return () => { cancelled = true; };
  }, []);
  return [offer, setOffer] as const;
}

function DevelopmentCheckoutNotice({ checkout }: { checkout: string }) {
  if (checkout !== "DEVELOPMENT_NO_CHARGE") return null;
  return <p className="notice"><strong>Development checkout.</strong> This checkout charges nothing and collects no payment details.</p>;
}

function OfferDetails({ offer, headingLevel }: { offer: Offer; headingLevel: "h3" | "h4" }) {
  const Heading = headingLevel;
  return <>
    <Heading>Free</Heading>
    <ul>{offer.free.map(code => <li key={code}>{word(FREE, code)}</li>)}</ul>
    <Heading>Requires the one-time payment</Heading>
    <ul>{offer.paid.map(code => <li key={code}>{word(PAID, code)}</li>)}</ul>
    <Heading>Refunds</Heading>
    <p>{refundTerms(offer)}</p>
    <Heading>Where it applies</Heading>
    <p>{offer.jurisdiction ? `Pilot state: ${offer.jurisdiction.name}.` : "No pilot state is selected yet."}</p>
    <Heading>Purchases not covered</Heading>
    <ul>{offer.excludedPurchaseTypes.map(code => <li key={code}>{word(EXCLUDED, code)}</li>)}</ul>
    <Heading>What the report does not do</Heading>
    <ul>{offer.scopeLimits.map(code => <li key={code}>{word(SCOPE_LIMITS, code)}</li>)}</ul>
  </>;
}

/** Shown on the intake screen, before any upload, only while an offer is active. */
export function OfferTerms() {
  const [offer] = useOffer();
  if (!offer) return null;
  return <section className="panel offer-terms" aria-labelledby="offer-terms-heading">
    <div className="section-heading">
      <div>
        <p className="step">Price and terms</p>
        <h2 id="offer-terms-heading">What is free and what you pay for</h2>
      </div>
      <span className="badge">{offerPrice(offer)} once</span>
    </div>
    <p>
      Uploading documents and verifying the quoted terms are free. Opening the Deal Analysis Report
      for a Buying Case costs a one-time {offerPrice(offer)}, paid just before the report opens. There
      is no recurring charge, and the price does not depend on the deal you reach.
    </p>
    <DevelopmentCheckoutNotice checkout={offer.checkout} />
    <OfferDetails offer={offer} headingLevel="h3" />
  </section>;
}

/**
 * The payment point before the report. One request key per offer: a retry after a failed request
 * reuses it, so the Buyer cannot buy the same report twice; a changed offer gets a new key.
 */
export function PurchasePoint({ caseId, token, onPurchased }: { caseId: string; token: string; onPurchased: () => void }) {
  const [offer, setOffer] = useOffer();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string>();
  const [failed, setFailed] = useState<string>();
  const keys = useRef<Record<string, string>>({});

  async function reloadOffer(notice: string) {
    const fresh = await fetchOffer();
    setOffer(fresh);
    if (!fresh) { onPurchased(); return; }
    setMessage(notice);
  }

  async function buy(current: Offer) {
    setBusy(true); setFailed(undefined); setMessage(undefined);
    keys.current[current.offerId] ??= crypto.randomUUID();
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/purchases`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Case-Access-Token": token, "Idempotency-Key": keys.current[current.offerId] },
        body: JSON.stringify({ offerId: current.offerId }),
      });
      if (response.status === 409) {
        const problem = await response.json().catch(() => ({}));
        if (problem?.code === "OFFER_CHANGED") {
          await reloadOffer("The price or terms changed since this page loaded. Review the current terms and confirm again.");
          return;
        }
        if (problem?.code === "NO_ACTIVE_OFFER") { await reloadOffer("The offer changed. Review the current terms."); return; }
      }
      if (!response.ok) throw new Error("payment request failed");
      const purchase = await response.json() as Purchase;
      if (purchase.status === "PAID") { onPurchased(); return; }
      if (purchase.status === "PENDING" && purchase.checkoutUrl) { window.location.assign(purchase.checkoutUrl); return; }
      setMessage("Payment is still pending. Select the payment button again to check; it will not charge you twice.");
    } catch {
      setFailed("The payment could not be confirmed. Retry; a retry cannot charge you twice.");
    } finally {
      setBusy(false);
    }
  }

  return <section className="panel purchase-point" aria-labelledby="purchase-point-heading">
    <h2 id="purchase-point-heading">Open your Deal Analysis Report</h2>
    {offer === undefined && <p role="status">Loading the price and terms…</p>}
    {offer === null && <>
      <p role="alert">The price and terms could not be loaded. Your documents and verified terms are saved.</p>
      <button type="button" onClick={onPurchased}>Retry opening the report</button>
    </>}
    {offer && <>
      <p>
        Your verified terms are saved. Opening the report for this Buying Case is a one-time payment
        of <strong>{offerPrice(offer)}</strong>. Your documents, verified terms, case recovery, data
        export, and case deletion stay available without paying.
      </p>
      <DevelopmentCheckoutNotice checkout={offer.checkout} />
      <details>
        <summary>Price terms, refunds, and scope</summary>
        <OfferDetails offer={offer} headingLevel="h4" />
      </details>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void buy(offer)}>
          {busy ? "Confirming payment…" : `Pay ${offerPrice(offer)} and open the report`}
        </button>
      </div>
      {message && <p role="status">{message}</p>}
      {failed && <p role="alert">{failed}</p>}
    </>}
  </section>;
}
