import { useEffect, useRef, useState } from "react";
import * as format from "./i18n/format";
import { type Messages, useMessages } from "./i18n/LanguageProvider";
import { findingCode } from "./i18n/findings";

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

type OfferMessages = Messages["money"]["oneTimeOffer"];
type Fill = ReturnType<typeof useMessages>["fill"];

/** An unknown code is still shown, readably, rather than dropped from the terms. */
function word(words: Readonly<Record<string, string>>, code: string, language: string) {
  if (words[code]) return words[code];
  return findingCode(language, code);
}

export function offerPrice(offer: Offer) {
  return format.money(offer.price.amountMinor, { currency: offer.price.currency });
}

function refundTerms(offer: Offer, t: OfferMessages, fill: Fill, language: string) {
  const count = offer.refund.windowDays;
  return offer.refund.terms === "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW"
    ? fill(t.fullRefund, { count })
    : fill(t.otherRefund, { terms: word({}, offer.refund.terms, language), count });
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
  const { m: { money: { oneTimeOffer: t } } } = useMessages();
  if (checkout !== "DEVELOPMENT_NO_CHARGE") return null;
  return <p className="notice"><strong>{t.developmentCheckout}</strong> {t.developmentCheckoutNote}</p>;
}

function OfferDetails({ offer, headingLevel }: { offer: Offer; headingLevel: "h3" | "h4" }) {
  const { m: { money: { oneTimeOffer: t } }, fill, language } = useMessages();
  const Heading = headingLevel;
  return <>
    <Heading>{t.freeHeading}</Heading>
    <ul>{offer.free.map(code => <li key={code}>{word(t.free, code, language)}</li>)}</ul>
    <Heading>{t.paidHeading}</Heading>
    <ul>{offer.paid.map(code => <li key={code}>{word(t.paid, code, language)}</li>)}</ul>
    <Heading>{t.refundsHeading}</Heading>
    <p>{refundTerms(offer, t, fill, language)}</p>
    <Heading>{t.whereHeading}</Heading>
    <p>{offer.jurisdiction ? fill(t.pilotState, { state: offer.jurisdiction.name }) : t.noPilotState}</p>
    <Heading>{t.excludedHeading}</Heading>
    <ul>{offer.excludedPurchaseTypes.map(code => <li key={code}>{word(t.excluded, code, language)}</li>)}</ul>
    <Heading>{t.scopeHeading}</Heading>
    <ul>{offer.scopeLimits.map(code => <li key={code}>{word(t.scopeLimits, code, language)}</li>)}</ul>
  </>;
}

/** Shown on the intake screen, before any upload, only while an offer is active. */
export function OfferTerms() {
  const { m: { money: { oneTimeOffer: t } }, fill } = useMessages();
  const [offer] = useOffer();
  if (!offer) return null;
  return <section className="panel offer-terms" aria-labelledby="offer-terms-heading">
    <div className="section-heading">
      <div>
        <p className="step">{t.termsStep}</p>
        <h2 id="offer-terms-heading">{t.termsHeading}</h2>
      </div>
      <span className="badge">{fill(t.priceOnce, { price: offerPrice(offer) })}</span>
    </div>
    <p>{fill(t.termsIntro, { price: offerPrice(offer) })}</p>
    <DevelopmentCheckoutNotice checkout={offer.checkout} />
    <OfferDetails offer={offer} headingLevel="h3" />
  </section>;
}

/**
 * The payment point before the report. One request key per offer: a retry after a failed request
 * reuses it, so the Buyer cannot buy the same report twice; a changed offer gets a new key.
 */
export function PurchasePoint({ caseId, token, onPurchased }: { caseId: string; token: string; onPurchased: () => void }) {
  const { m: { money: { oneTimeOffer: t } }, fill, fillNodes } = useMessages();
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
          await reloadOffer(t.offerChanged);
          return;
        }
        if (problem?.code === "NO_ACTIVE_OFFER") { await reloadOffer(t.noActiveOffer); return; }
      }
      if (!response.ok) throw new Error("payment-request-failed");
      const purchase = await response.json() as Purchase;
      if (purchase.status === "PAID") { onPurchased(); return; }
      if (purchase.status === "PENDING" && purchase.checkoutUrl) { window.location.assign(purchase.checkoutUrl); return; }
      setMessage(t.stillPending);
    } catch {
      setFailed(t.paymentFailed);
    } finally {
      setBusy(false);
    }
  }

  return <section className="panel purchase-point" aria-labelledby="purchase-point-heading">
    <h2 id="purchase-point-heading">{t.purchaseHeading}</h2>
    {offer === undefined && <p role="status">{t.loading}</p>}
    {offer === null && <>
      <p role="alert">{t.loadFailed}</p>
      <button type="button" onClick={onPurchased}>{t.retry}</button>
    </>}
    {offer && <>
      <p>{fillNodes(t.purchaseIntro, { price: <strong>{offerPrice(offer)}</strong> })}</p>
      <DevelopmentCheckoutNotice checkout={offer.checkout} />
      <details>
        <summary>{t.termsSummary}</summary>
        <OfferDetails offer={offer} headingLevel="h4" />
      </details>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void buy(offer)}>
          {busy ? t.confirming : fill(t.pay, { price: offerPrice(offer) })}
        </button>
      </div>
      {message && <p role="status">{message}</p>}
      {failed && <p role="alert">{failed}</p>}
    </>}
  </section>;
}
